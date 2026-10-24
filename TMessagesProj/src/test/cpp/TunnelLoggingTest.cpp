#include "tg_tunnel/TunnelLog.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static std::vector<std::string> messages;
// Model both app-wide logging states. The tunnel logger must ignore them.
bool LOGS_ENABLED = false;

int __android_log_vprint(int, const char *tag, const char *format, va_list args) {
    assert(std::strcmp(tag, "Telegram/Tunnel") == 0);
    char buffer[256];
    std::vsnprintf(buffer, sizeof(buffer), format, args);
    messages.emplace_back(buffer);
    return 0;
}

int main() {
    for (bool generalLogs : {false, true}) {
        LOGS_ENABLED = generalLogs;
        messages.clear();
        int evaluations = 0;
        TG_TUNNEL_LOG_D("attempt=%d", ++evaluations);
        TG_TUNNEL_LOG_E("failed=%d", ++evaluations);
        TG_TUNNEL_LOG_STREAM(ANDROID_LOG_INFO) << "connected=" << ++evaluations;
        if (TG_TUNNEL_LOGS_ENABLED) {
            assert(evaluations == 3);
            assert((messages == std::vector<std::string>{"attempt=1", "failed=2", "connected=3"}));
        } else {
            assert(evaluations == 0);
            assert(messages.empty());
        }
    }
}
