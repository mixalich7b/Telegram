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

constexpr int kTunnelTcpRawReflectorOption = 0x40000000;

bool IsTunnelProxy(const Proxy *proxy);
bool IsTunnelProxy(const absl::optional<Proxy> &proxy);

std::unique_ptr<rtc::PacketSocketFactory> CreateTunnelPacketSocketFactory(rtc::Thread *thread);
std::unique_ptr<rtc::NetworkManager> CreateTunnelNetworkManager();
std::unique_ptr<webrtc::AsyncDnsResolverFactoryInterface> CreateTunnelAsyncDnsResolverFactory(rtc::Thread *thread);

} // namespace tgcalls

#endif
