package org.telegram.messenger;

public final class WireGuardProxySettings extends TunnelProxySettings {
    WireGuardProxySettings(String host, int port, String username, String password, boolean blocked) {
        super(TunnelProtocol.WIREGUARD, host, port, username, password, blocked);
    }

    WireGuardProxySettings(TunnelProtocol protocol, String host, int port, String username, String password, boolean blocked) {
        super(protocol, host, port, username, password, blocked);
    }

    static WireGuardProxySettings from(TunnelProxySettings settings) {
        if (settings == null) {
            return null;
        }
        return new WireGuardProxySettings(settings.protocol, settings.host, settings.port, settings.username, settings.password, settings.blocked);
    }
}
