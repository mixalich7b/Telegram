#include "TunnelLogApi.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cerrno>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <deque>
#include <fstream>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <sys/stat.h>
#include <unistd.h>
#include <utime.h>

#ifndef TG_TUNNEL_LOGS_ENABLED
#define TG_TUNNEL_LOGS_ENABLED 1
#endif

namespace {
constexpr size_t maxFileBytes = 4 * 1024 * 1024;
constexpr size_t maxQueuedMessages = 1024;
constexpr size_t maxMessageBytes = 8192;
constexpr const char *tag = "Telegram/Tunnel";

struct Entry {
    int priority = ANDROID_LOG_INFO;
    std::string message;
    std::chrono::system_clock::time_point time;
    std::string snapshotDirectory;
    std::shared_ptr<std::promise<bool>> completion;
    bool clear = false;
};

class FileSink {
public:
    bool init(const char *path) {
        std::lock_guard<std::mutex> lock(mutex);
        if (started) {
            return true;
        }
        if (path == nullptr || *path == '\0') {
            return false;
        }
        directory = path;
        std::thread([this] { run(); }).detach();
        started = true;
        return true;
    }

    void enqueue(int priority, const char *message) {
        Entry entry;
        entry.priority = priority;
        entry.message.assign(message, strnlen(message, maxMessageBytes));
        entry.time = std::chrono::system_clock::now();
        std::lock_guard<std::mutex> lock(mutex);
        if (queue.size() >= maxQueuedMessages) {
            ++dropped;
            return;
        }
        queue.push_back(std::move(entry));
        changed.notify_one();
    }

    bool snapshot(const char *path) {
        auto completion = std::make_shared<std::promise<bool>>();
        auto result = completion->get_future();
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!started) {
                return false;
            }
            Entry entry;
            entry.snapshotDirectory = path;
            entry.completion = completion;
            // Control operations must not be dropped when the message queue is full.
            queue.push_back(std::move(entry));
            changed.notify_one();
        }
        return result.get();
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex);
        if (!started) {
            return;
        }
        Entry entry;
        entry.clear = true;
        queue.push_back(std::move(entry));
        changed.notify_one();
    }

private:
    std::mutex mutex;
    std::condition_variable changed;
    std::deque<Entry> queue;
    size_t dropped = 0;
    bool started = false;
    std::string directory;
    FILE *file = nullptr;
    size_t fileBytes = 0;

    std::string currentPath() const { return directory + "/tunnel.log"; }
    std::string previousPath() const { return directory + "/tunnel.1.log"; }

    void close() {
        if (file != nullptr) {
            fclose(file);
            file = nullptr;
        }
        fileBytes = 0;
    }

    void append(const Entry &entry) {
        if (file == nullptr) {
            file = fopen(currentPath().c_str(), "a");
            if (file == nullptr) {
                __android_log_write(ANDROID_LOG_ERROR, tag, "tunnel file log open failed; logcat remains available");
                return;
            }
            struct stat info{};
            if (fstat(fileno(file), &info) == 0) {
                fileBytes = static_cast<size_t>(info.st_size);
            }
        }
        const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(entry.time.time_since_epoch()).count();
        const time_t seconds = static_cast<time_t>(millis / 1000);
        struct tm utc{};
        gmtime_r(&seconds, &utc);
        char timestamp[40];
        strftime(timestamp, sizeof(timestamp), "%Y-%m-%dT%H:%M:%S", &utc);
        char prefix[80];
        snprintf(prefix, sizeof(prefix), "%s.%03lldZ %c ", timestamp,
                 static_cast<long long>(millis % 1000), entry.priority >= ANDROID_LOG_ERROR ? 'E' :
                 entry.priority == ANDROID_LOG_WARN ? 'W' : entry.priority == ANDROID_LOG_INFO ? 'I' : 'D');
        std::string line = std::string(prefix) + entry.message + "\n";
        if (fileBytes + line.size() > maxFileBytes) {
            close();
            if (rename(currentPath().c_str(), previousPath().c_str()) != 0) {
                __android_log_write(ANDROID_LOG_ERROR, tag, "tunnel file log rotation failed");
                return;
            }
            file = fopen(currentPath().c_str(), "a");
            if (file == nullptr) {
                return;
            }
        }
        const size_t written = fwrite(line.data(), 1, line.size(), file);
        fileBytes += written;
        if (written != line.size() || fflush(file) != 0) {
            __android_log_write(ANDROID_LOG_ERROR, tag, "tunnel file log write failed; logcat remains available");
            close();
        }
    }

    bool copySnapshot(const std::string &target) {
        for (const char *name : {"tunnel.log", "tunnel.1.log"}) {
            const std::string source = directory + "/" + name;
            struct stat info{};
            if (stat(source.c_str(), &info) != 0) {
                if (errno == ENOENT) {
                    continue;
                }
                return false;
            }
            std::ifstream input(source, std::ios::binary);
            std::ofstream output(target + "/" + name, std::ios::binary | std::ios::trunc);
            if (!input || !output) {
                return false;
            }
            char buffer[65536];
            while (input) {
                input.read(buffer, sizeof(buffer));
                output.write(buffer, input.gcount());
            }
            output.flush();
            if (input.bad() || !output) {
                return false;
            }
            output.close();
            struct utimbuf times{info.st_atime, info.st_mtime};
            if (!output || utime((target + "/" + name).c_str(), &times) != 0) {
                return false;
            }
        }
        return true;
    }

    void run() {
        for (;;) {
            Entry entry;
            size_t lost;
            {
                std::unique_lock<std::mutex> lock(mutex);
                changed.wait(lock, [this] { return !queue.empty(); });
                entry = std::move(queue.front());
                queue.pop_front();
                lost = dropped;
                dropped = 0;
            }
            try {
                if (lost != 0) {
                    Entry warning;
                    warning.priority = ANDROID_LOG_WARN;
                    warning.time = std::chrono::system_clock::now();
                    warning.message = "tunnel_trace event=file_log_overflow dropped=" + std::to_string(lost);
                    append(warning);
                }
                if (entry.clear) {
                    close();
                    remove(currentPath().c_str());
                    remove(previousPath().c_str());
                } else if (entry.completion) {
                    entry.completion->set_value(copySnapshot(entry.snapshotDirectory));
                } else {
                    append(entry);
                }
            } catch (...) {
                // Logging failures must not unwind into JNI, cgo or routing threads.
                __android_log_write(ANDROID_LOG_ERROR, tag, "tunnel file log worker failed; logcat remains available");
                if (entry.completion) {
                    try { entry.completion->set_value(false); } catch (...) { }
                }
            }
        }
    }
};

FileSink &sink() {
    // Process lifetime: Android terminates the process without joining native workers.
    static auto *instance = new FileSink();
    return *instance;
}
}

void tgTunnelLogWrite(int priority, const char *message) {
    if (TG_TUNNEL_LOGS_ENABLED && message != nullptr) {
        __android_log_write(priority, tag, message);
        tgTunnelLogEnqueue(priority, message);
    }
}

void tgTunnelLogEnqueue(int priority, const char *message) {
    if (TG_TUNNEL_LOGS_ENABLED && message != nullptr) {
        try {
            sink().enqueue(priority, message);
        } catch (...) {
            __android_log_write(ANDROID_LOG_ERROR, tag, "tunnel file log enqueue failed; logcat remains available");
        }
    }
}

int tgTunnelLogInit(const char *directory) {
    if (TG_TUNNEL_LOGS_ENABLED) {
        try {
            return sink().init(directory);
        } catch (...) {
            __android_log_write(ANDROID_LOG_ERROR, tag, "tunnel file log initialization failed; logcat remains available");
        }
    }
    return false;
}

int tgTunnelLogSnapshot(const char *directory) {
    if (TG_TUNNEL_LOGS_ENABLED && directory != nullptr) {
        try {
            return sink().snapshot(directory);
        } catch (...) {
            __android_log_write(ANDROID_LOG_ERROR, tag, "tunnel file log snapshot failed");
        }
    }
    return false;
}

void tgTunnelLogClear() {
    if (TG_TUNNEL_LOGS_ENABLED) {
        try {
            sink().clear();
        } catch (...) {
            __android_log_write(ANDROID_LOG_ERROR, tag, "tunnel file log clear failed");
        }
    }
}

#ifdef __ANDROID__
#include <jni.h>
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_TunnelLog_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *value = env->GetStringUTFChars(path, nullptr);
    bool result = false;
    if (value != nullptr) {
        result = tgTunnelLogInit(value);
        env->ReleaseStringUTFChars(path, value);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_TunnelLog_nativeWriteFile(JNIEnv *env, jclass, jint priority, jstring message) {
    const char *value = env->GetStringUTFChars(message, nullptr);
    if (value != nullptr) {
        tgTunnelLogEnqueue(priority, value);
        env->ReleaseStringUTFChars(message, value);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_TunnelLog_nativeSnapshot(JNIEnv *env, jclass, jstring path) {
    const char *value = env->GetStringUTFChars(path, nullptr);
    bool result = false;
    if (value != nullptr) {
        result = tgTunnelLogSnapshot(value);
        env->ReleaseStringUTFChars(path, value);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_TunnelLog_nativeClear(JNIEnv *, jclass) {
    tgTunnelLogClear();
}
#endif
