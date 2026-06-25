package org.telegram.messenger;

public final class TunnelVoipRouting {

    private TunnelVoipRouting() {
    }

    public static TunnelProxySettings selectProxySettings(boolean tunnelVoipEnabled, TunnelProxySettings proxySettings) {
        return tunnelVoipEnabled ? proxySettings : null;
    }

    public static boolean shouldUseTunnel(TunnelProxySettings proxySettings) {
        return proxySettings != null;
    }

    public static boolean shouldEnableP2p(boolean requestedEnableP2p, TunnelProxySettings proxySettings) {
        return requestedEnableP2p;
    }

    public static int selectEndpointType(boolean requestedTcpRelay, TunnelProxySettings proxySettings, int udpRelayType, int tcpRelayType) {
        return requestedTcpRelay || proxySettings != null ? tcpRelayType : udpRelayType;
    }
}
