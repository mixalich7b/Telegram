package org.telegram.messenger;

public final class WireGuardProxySettings {
    public final String host;
    public final int port;
    public final String username;
    public final String password;
    public final boolean blocked;

    WireGuardProxySettings(String host, int port, String username, String password, boolean blocked) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.blocked = blocked;
    }
}
