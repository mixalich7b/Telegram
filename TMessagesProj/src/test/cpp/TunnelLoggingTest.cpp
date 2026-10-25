#include "tg_tunnel/TunnelLog.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>

namespace fs = std::filesystem;
static std::vector<std::string> messages;
static std::mutex messagesMutex;
// Model both app-wide logging states. The tunnel logger must ignore them.
bool LOGS_ENABLED = false;

int __android_log_write(int, const char *tag, const char *message) {
    assert(std::strcmp(tag, "Telegram/Tunnel") == 0);
    std::lock_guard<std::mutex> lock(messagesMutex);
    messages.emplace_back(message);
    return 0;
}

static std::string read(const fs::path &path) {
    std::ifstream file(path);
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

int main() {
    char temporary[] = "/tmp/tunnel-log-test-XXXXXX";
    assert(mkdtemp(temporary) != nullptr);
    const fs::path root(temporary);
    const auto live = root / "live";
    const auto snapshot = root / "snapshot";
    fs::create_directories(live);
    fs::create_directories(snapshot);
    if (TG_TUNNEL_LOGS_ENABLED) {
        std::ofstream(live / "tunnel.log") << "previous-process\n";
    }
    // Messages arriving before Java supplies the directory must be retained too.
    TG_TUNNEL_LOG_D("early-native");
    assert(!tgTunnelLogInit(""));
    assert(tgTunnelLogInit(live.c_str()) == TG_TUNNEL_LOGS_ENABLED);
    for (bool generalLogs : {false, true}) {
        LOGS_ENABLED = generalLogs;
        messages.clear();
        int evaluations = 0;
        TG_TUNNEL_LOG_D("attempt=%d", ++evaluations);
        TG_TUNNEL_LOG_E("failed=%d", ++evaluations);
        TG_TUNNEL_LOG_STREAM(ANDROID_LOG_INFO) << "connected=" << ++evaluations;
        // Java has already emitted logcat; its enqueue API must not duplicate it.
        tgTunnelLogEnqueue(ANDROID_LOG_DEBUG, "java-record");
        // Go calls this shared C ABI directly.
        tgTunnelLogWrite(ANDROID_LOG_ERROR, "go-record");
        const bool copied = tgTunnelLogSnapshot(snapshot.c_str());
        if (TG_TUNNEL_LOGS_ENABLED) {
            assert(copied);
            assert(evaluations == 3);
            assert((messages == std::vector<std::string>{"attempt=1", "failed=2", "connected=3", "go-record"}));
            const auto data = read(snapshot / "tunnel.log");
            for (const auto *expected : {"previous-process", "early-native", "attempt=1", "failed=2", "connected=3", "java-record", "go-record"}) {
                assert(data.find(expected) != std::string::npos);
            }
        } else {
            assert(!copied);
            assert(evaluations == 0);
            assert(messages.empty());
            assert(fs::is_empty(live));
        }
    }
    if (TG_TUNNEL_LOGS_ENABLED) {
        const auto oldSnapshot = read(snapshot / "tunnel.log");
        std::vector<std::thread> producers;
        for (int thread = 0; thread < 4; ++thread) {
            producers.emplace_back([thread] {
                for (int line = 0; line < 50; ++line) {
                    TG_TUNNEL_LOG_D("parallel_%d_%d", thread, line);
                }
            });
        }
        for (auto &thread : producers) {
            thread.join();
        }
        const auto concurrentSnapshot = root / "concurrent";
        fs::create_directory(concurrentSnapshot);
        assert(tgTunnelLogSnapshot(concurrentSnapshot.c_str()));
        const auto parallelData = read(concurrentSnapshot / "tunnel.log");
        for (int thread = 0; thread < 4; ++thread) {
            for (int line = 0; line < 50; ++line) {
                assert(parallelData.find("parallel_" + std::to_string(thread) + "_" + std::to_string(line) + "\n") != std::string::npos);
            }
        }
        assert(read(snapshot / "tunnel.log") == oldSnapshot);
        // A normal-size queue burst crosses the 4 MiB rotation boundary.
        const std::string large(8192, 'x');
        for (int line = 0; line < 600; ++line) {
            tgTunnelLogEnqueue(ANDROID_LOG_DEBUG, large.c_str());
        }
        assert(tgTunnelLogSnapshot(concurrentSnapshot.c_str()));
        assert(fs::file_size(live / "tunnel.log") <= 4 * 1024 * 1024);
        assert(fs::file_size(live / "tunnel.1.log") <= 4 * 1024 * 1024);
        assert(fs::exists(concurrentSnapshot / "tunnel.1.log"));
        tgTunnelLogClear();
        const auto cleared = root / "cleared";
        fs::create_directory(cleared);
        assert(tgTunnelLogSnapshot(cleared.c_str()));
        assert(fs::is_empty(live));
        tgTunnelLogEnqueue(ANDROID_LOG_INFO, "after-clear");
        assert(tgTunnelLogSnapshot(cleared.c_str()));
        assert(read(cleared / "tunnel.log").find("after-clear") != std::string::npos);
        assert(read(cleared / "tunnel.log").find("early-native") == std::string::npos);
        // An export failure is reported, and must not stop later file logging.
        assert(!tgTunnelLogSnapshot((root / "missing" / "snapshot").c_str()));
        tgTunnelLogEnqueue(ANDROID_LOG_INFO, "after-export-failure");
        assert(tgTunnelLogSnapshot(cleared.c_str()));
        assert(read(cleared / "tunnel.log").find("after-export-failure") != std::string::npos);
        tgTunnelLogClear();
        assert(tgTunnelLogSnapshot(cleared.c_str()));
        fs::remove(live);
        TG_TUNNEL_LOG_E("file-unavailable");
        assert(tgTunnelLogSnapshot(cleared.c_str()));
        assert(messages.back().find("file log open failed") != std::string::npos);
        fs::create_directory(live);
        tgTunnelLogEnqueue(ANDROID_LOG_INFO, "after-file-recovery");
        assert(tgTunnelLogSnapshot(cleared.c_str()));
        assert(read(cleared / "tunnel.log").find("after-file-recovery") != std::string::npos);
    }
    fs::remove_all(root);
}
