package org.telegram.messenger;

public final class TunnelVoipRouting {

    private TunnelVoipRouting() {
    }

    public static boolean shouldUseTunnel(TunnelProxySettings proxySettings) {
        return proxySettings != null;
    }

    public static boolean shouldEnableP2p(boolean requestedEnableP2p, TunnelProxySettings proxySettings) {
        return proxySettings == null && requestedEnableP2p;
    }

    public static int selectEndpointType(boolean requestedTcpRelay, TunnelProxySettings proxySettings, int udpRelayType, int tcpRelayType) {
        return requestedTcpRelay || proxySettings != null ? tcpRelayType : udpRelayType;
    }
}
