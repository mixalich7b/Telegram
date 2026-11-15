#pragma once

#include <cinttypes>
#include "tg_tunnel/TunnelLog.h"

namespace tgnet {

// Diagnostic state only. All access is serialized on the owning tgnet queue.
class TunnelConnectionTrace {
public:
    void setContext(uint32_t dc, int type) { dc_ = dc; type_ = type; }
    void clear() { attempt_ = 0; }
    void begin(int account, int64_t generation, uint64_t attempt, int64_t now) {
        if (!TG_TUNNEL_LOGS_ENABLED) return;
        account_ = account;
        generation_ = generation;
        attempt_ = attempt;
        started_ = now;
        connected_ = written_ = read_ = -1;
        closed_ = ready_ = false;
        event("connection_start", now);
    }
    void connected(int64_t now) {
        if (!active() || closed_ || connected_ >= 0) return;
        connected_ = now;
        event("connection_tcp_ready", now);
    }
    void io(bool reading, int64_t bytes, int64_t now) {
        if (!active() || closed_ || bytes <= 0) return;
        auto &first = reading ? read_ : written_;
        if (first >= 0) return;
        first = now;
        event(reading ? "tgnet_first_read" : "tgnet_first_write", now);
    }
    bool needsIo(bool reading) const {
        return active() && !closed_ && (reading ? read_ : written_) < 0;
    }
    void ready(int64_t now) {
        if (!active() || closed_ || ready_) return;
        ready_ = true;
        event("connection_mtproto_ready", now);
    }
    void close(int64_t now, int reason, int error) {
        if (!active() || closed_) return;
        event("connection_closed", now, reason, error);
        closed_ = true;
    }
    void event(const char *name, int64_t now, int reason = 0, int error = 0) const {
        if (!active()) return;
        TG_TUNNEL_LOG_D("tunnel_trace event=%s attempt=%" PRIu64 " account=%d generation=%" PRId64
                " dc=%u type=%d elapsed_ms=%" PRId64 " tcp_elapsed_ms=%" PRId64
                " write_elapsed_ms=%" PRId64 " read_elapsed_ms=%" PRId64
                " phase=%s reason=%d error=%d",
                name, attempt_, account_, generation_, dc_, type_, now - started_,
                age(connected_, now), age(written_, now), age(read_, now),
                ready_ ? "ready" : read_ >= 0 ? "received" : written_ >= 0 ? "sent"
                        : connected_ >= 0 ? "connected" : "dialing", reason, error);
    }
    bool active() const { return TG_TUNNEL_LOGS_ENABLED && attempt_ != 0; }
    uint64_t attempt() const { return attempt_; }
    int64_t generation() const { return generation_; }

private:
    static int64_t age(int64_t timestamp, int64_t now) { return timestamp < 0 ? -1 : now - timestamp; }
    uint64_t attempt_ = 0;
    int account_ = 0;
    int64_t generation_ = 0;
    uint32_t dc_ = 0;
    int type_ = 0;
    int64_t started_ = 0;
    int64_t connected_ = -1, written_ = -1, read_ = -1;
    bool closed_ = false, ready_ = false;
};

// One instance per account; flush before changing its route generation/state.
class TunnelBlockedTrace {
public:
    void record(int account, int64_t generation, int64_t now) {
        if (!TG_TUNNEL_LOGS_ENABLED) return;
        if (started_ >= 0 && now - started_ < 1000) {
            ++suppressed_;
            return;
        }
        flush(account, generation, now);
        started_ = now;
        TG_TUNNEL_LOG_D("tunnel_trace event=route_blocked account=%d generation=%" PRId64,
                account, generation);
    }
    void flush(int account, int64_t generation, int64_t now) {
        if (!TG_TUNNEL_LOGS_ENABLED) return;
        if (suppressed_ != 0) {
            TG_TUNNEL_LOG_D("tunnel_trace event=route_blocked_summary account=%d generation=%" PRId64
                    " suppressed=%" PRIu64 " elapsed_ms=%" PRId64,
                    account, generation, suppressed_, now - started_);
        }
        suppressed_ = 0;
        started_ = -1;
    }
private:
    int64_t started_ = -1;
    uint64_t suppressed_ = 0;
};

} // namespace tgnet
