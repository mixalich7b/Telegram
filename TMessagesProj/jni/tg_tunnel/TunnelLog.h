#pragma once

#include <android/log.h>
#include <cstdarg>
#include <sstream>

#ifndef TG_TUNNEL_LOGS_ENABLED
#define TG_TUNNEL_LOGS_ENABLED 1
#endif

// Deliberately bypass tgnet FileLog and WebRTC sinks: both have global gates.
inline void tgTunnelLog(int priority, const char *format, ...) __attribute__((format(printf, 2, 3)));
inline void tgTunnelLog(int priority, const char *format, ...) {
    if (!TG_TUNNEL_LOGS_ENABLED) {
        return;
    }
    va_list args;
    va_start(args, format);
    __android_log_vprint(priority, "Telegram/Tunnel", format, args);
    va_end(args);
}

class TgTunnelLogLine {
public:
    explicit TgTunnelLogLine(int priority) : priority(priority) {}
    ~TgTunnelLogLine() { tgTunnelLog(priority, "%s", message.str().c_str()); }
    std::ostream &stream() { return message; }
private:
    int priority;
    std::ostringstream message;
};

#define TG_TUNNEL_LOG_D(...) do { if (TG_TUNNEL_LOGS_ENABLED) tgTunnelLog(ANDROID_LOG_DEBUG, __VA_ARGS__); } while (0)
#define TG_TUNNEL_LOG_E(...) do { if (TG_TUNNEL_LOGS_ENABLED) tgTunnelLog(ANDROID_LOG_ERROR, __VA_ARGS__); } while (0)
#define TG_TUNNEL_LOG_STREAM(priority) if (!TG_TUNNEL_LOGS_ENABLED) {} else TgTunnelLogLine(priority).stream()
