#pragma once

#include <atomic>
#include <cstdint>
#include <string>

namespace tgnet {
// Shared by tgnet and WebRTC inside libtmessages. Policy changes are deferred
// by Java while a VoIP instance is alive.
inline std::atomic<bool> allowIpv6{true};
inline bool ipv6Enabled() { return allowIpv6.load(); }
inline void setIpv6Enabled(bool enabled) { allowIpv6.store(enabled); }
inline uint8_t effectiveIpStrategy(uint8_t strategy) { return ipv6Enabled() ? strategy : 0; }
inline bool addressAllowed(const std::string &address) {
    return ipv6Enabled() || address.find(':') == std::string::npos;
}
}
