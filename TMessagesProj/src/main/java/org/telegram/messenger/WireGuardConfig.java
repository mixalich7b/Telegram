package org.telegram.messenger;

public final class WireGuardConfig {

    public static final boolean ENABLED = false;

    public static final String NATIVE_LIBRARY_NAME = "tg-wg";
    public static final String SOCKS_BIND_HOST = "127.0.0.1";
    public static final int SOCKS_BIND_PORT = 0;
    public static final int BLOCKED_PROXY_PORT = 1;

    public static final int MTU = 1420;

    public static final String PRIVATE_KEY = "";
    public static final String PEER_PUBLIC_KEY = "";
    public static final String PRESHARED_KEY = "";
    public static final String PEER_ENDPOINT = "";
    public static final int PERSISTENT_KEEPALIVE_SECONDS = 25;

    public static final String[] LOCAL_ADDRESSES = new String[]{

    };

    public static final String[] DNS_SERVERS = new String[]{

    };

    public static final String[] ALLOWED_IPS = new String[]{
            "0.0.0.0/0",
            "::/0",
    };

    private WireGuardConfig() {
    }

    public static String buildUserspaceConfig() {
        return WireGuardUserspaceConfig.build(
                PRIVATE_KEY,
                PEER_PUBLIC_KEY,
                PRESHARED_KEY,
                PEER_ENDPOINT,
                PERSISTENT_KEEPALIVE_SECONDS,
                ALLOWED_IPS);
    }

    public static String validate() {
        return WireGuardUserspaceConfig.validate(
                PRIVATE_KEY,
                PEER_PUBLIC_KEY,
                PEER_ENDPOINT,
                LOCAL_ADDRESSES,
                ALLOWED_IPS);
    }
}
