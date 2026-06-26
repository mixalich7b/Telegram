#ifndef TGCALLS_TUNNEL_PACKET_SOCKET_FACTORY_H
#define TGCALLS_TUNNEL_PACKET_SOCKET_FACTORY_H

#include "api/packet_socket_factory.h"
#include "rtc_base/network.h"
#include "rtc_base/thread.h"
#include "Instance.h"

#include "absl/types/optional.h"

#include <memory>
#include <vector>

namespace tgcalls {

bool IsTunnelProxy(const Proxy *proxy);
bool IsTunnelProxy(const absl::optional<Proxy> &proxy);

std::unique_ptr<rtc::PacketSocketFactory> CreateTunnelPacketSocketFactory(rtc::Thread *thread);
std::unique_ptr<rtc::NetworkManager> CreateTunnelNetworkManager();

} // namespace tgcalls

#endif
