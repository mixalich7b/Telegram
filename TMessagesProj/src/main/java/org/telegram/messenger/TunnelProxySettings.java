package org.telegram.messenger;

public class TunnelProxySettings {
    public final TunnelProtocol protocol;
    public final String host;
    public final int port;
    public final String username;
    public final String password;
    public final boolean blocked;

    TunnelProxySettings(TunnelProtocol protocol, String host, int port, String username, String password, boolean blocked) {
        this.protocol = protocol == null ? TunnelProtocol.WIREGUARD : protocol;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.blocked = blocked;
    }
}
