package org.telegram.messenger;

public enum TunnelProtocol {
    WIREGUARD("wireguard", "WireGuard"),
    AMNEZIA_WG("amnezia_wg", "AmneziaWG");

    private final String storageValue;
    private final String displayName;

    TunnelProtocol(String storageValue, String displayName) {
        this.storageValue = storageValue;
        this.displayName = displayName;
    }

    public String storageValue() {
        return storageValue;
    }

    public String displayName() {
        return displayName;
    }

    public static TunnelProtocol fromStorageValue(String value) {
        if (value != null) {
            for (TunnelProtocol protocol : values()) {
                if (protocol.storageValue.equals(value)) {
                    return protocol;
                }
            }
        }
        return WIREGUARD;
    }
}
