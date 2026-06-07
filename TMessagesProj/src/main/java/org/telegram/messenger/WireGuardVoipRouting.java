package org.telegram.messenger;

public final class WireGuardVoipRouting {

    private WireGuardVoipRouting() {
    }

    public static boolean shouldUseWireGuard(WireGuardProxySettings proxySettings) {
        return proxySettings != null;
    }

    public static boolean shouldEnableP2p(boolean requestedEnableP2p, WireGuardProxySettings proxySettings) {
        return proxySettings == null && requestedEnableP2p;
    }

    public static int selectEndpointType(boolean requestedTcpRelay, WireGuardProxySettings proxySettings, int udpRelayType, int tcpRelayType) {
        return requestedTcpRelay || proxySettings != null ? tcpRelayType : udpRelayType;
    }
}
