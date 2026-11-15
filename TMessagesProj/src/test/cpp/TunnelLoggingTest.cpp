#include "tg_tunnel/TunnelLog.h"
#include "tgnet/TunnelConnectionDiagnostics.h"
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
        messages.clear();
        tgnet::TunnelConnectionTrace trace;
        trace.setContext(2, 16);
        trace.begin(0, 7, 11, 1000);
        trace.connected(1020);
        trace.io(false, 0, 1030);
        trace.io(false, -1, 1030);
        assert(trace.needsIo(false) == static_cast<bool>(TG_TUNNEL_LOGS_ENABLED));
        trace.io(false, 64, 1040);
        assert(!trace.needsIo(false));
        trace.io(false, 64, 1050);
        trace.io(true, 32, 1080);
        trace.io(true, 32, 1090);
        trace.ready(1100);
        trace.ready(1110);
        trace.close(1120, 2, 0);
        trace.close(1130, 2, 0);
        trace.io(true, 32, 1140);
        if (TG_TUNNEL_LOGS_ENABLED) {
            assert(messages.size() == 6);
            const auto &ready = messages[4];
            assert(ready.find("attempt=11 account=0 generation=7 dc=2 type=16") != std::string::npos);
            assert(ready.find("elapsed_ms=100 tcp_elapsed_ms=80 write_elapsed_ms=60 read_elapsed_ms=20") != std::string::npos);
            assert(messages[5].find("reason=2 error=0") != std::string::npos);
        } else {
            assert(messages.empty());
        }
        messages.clear();
        trace.begin(0, 8, 12, 2000);
        trace.event("test_retry", 2010);
        if (TG_TUNNEL_LOGS_ENABLED) {
            assert(messages.back().find("attempt=12 account=0 generation=8") != std::string::npos);
            assert(messages.back().find("tcp_elapsed_ms=-1 write_elapsed_ms=-1 read_elapsed_ms=-1") != std::string::npos);
        }
        messages.clear();
        tgnet::TunnelBlockedTrace blocked;
        blocked.record(0, 7, 1000);
        for (int i = 1; i <= 1000; ++i) blocked.record(0, 7, 1000 + i / 10);
        blocked.record(0, 7, 2000);
        blocked.record(0, 7, 2001);
        blocked.flush(0, 7, 2010);
        blocked.flush(0, 7, 2011);
        blocked.record(0, 8, 2020);
        if (TG_TUNNEL_LOGS_ENABLED) {
            assert(messages.size() == 5);
            assert(messages[1].find("suppressed=1000") != std::string::npos);
            assert(messages[3].find("generation=7 suppressed=1") != std::string::npos);
            assert(messages[4].find("generation=8") != std::string::npos);
        } else {
            assert(messages.empty());
        }
        messages.clear();
        trace.clear();
        trace.ready(3000);
        trace.io(true, 64, 3010);
        trace.event("direct_connection", 3020);
        assert(messages.empty());
        if (TG_TUNNEL_LOGS_ENABLED) {
            assert(tgTunnelLogSnapshot(snapshot.c_str()));
            const auto diagnostics = read(snapshot / "tunnel.log");
            assert(diagnostics.find("event=connection_mtproto_ready attempt=11 account=0 generation=7") != std::string::npos);
            assert(diagnostics.find("event=route_blocked_summary account=0 generation=7 suppressed=1000") != std::string::npos);
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
