package org.telegram.messenger;

public final class WireGuardVoipRouting {

    private WireGuardVoipRouting() {
    }

    public static boolean shouldUseWireGuard(WireGuardProxySettings proxySettings) {
        return TunnelVoipRouting.shouldUseTunnel(proxySettings);
    }

    public static boolean shouldEnableP2p(boolean requestedEnableP2p, WireGuardProxySettings proxySettings) {
        return TunnelVoipRouting.shouldEnableP2p(requestedEnableP2p, proxySettings);
    }

    public static int selectEndpointType(boolean requestedTcpRelay, WireGuardProxySettings proxySettings, int udpRelayType, int tcpRelayType) {
        return TunnelVoipRouting.selectEndpointType(requestedTcpRelay, proxySettings, udpRelayType, tcpRelayType);
    }
}
