package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;

public final class WireGuardProfile {

    public static final int DEFAULT_MTU = 1420;
    public static final int DEFAULT_PERSISTENT_KEEPALIVE_SECONDS = 0;

    public String id;
    public String name;
    public String privateKey;
    public String[] localAddresses;
    public String[] dnsServers;
    public int mtu;
    public String peerPublicKey;
    public String presharedKey;
    public String peerEndpoint;
    public String[] allowedIps;
    public int persistentKeepaliveSeconds;
    public long createdAt;
    public long updatedAt;

    public WireGuardProfile() {
        id = "";
        name = "";
        privateKey = "";
        localAddresses = new String[0];
        dnsServers = new String[0];
        mtu = DEFAULT_MTU;
        peerPublicKey = "";
        presharedKey = "";
        peerEndpoint = "";
        allowedIps = new String[0];
        persistentKeepaliveSeconds = DEFAULT_PERSISTENT_KEEPALIVE_SECONDS;
    }

    public WireGuardProfile copy() {
        WireGuardProfile copy = new WireGuardProfile();
        copy.id = id;
        copy.name = name;
        copy.privateKey = privateKey;
        copy.localAddresses = copyArray(localAddresses);
        copy.dnsServers = copyArray(dnsServers);
        copy.mtu = mtu;
        copy.peerPublicKey = peerPublicKey;
        copy.presharedKey = presharedKey;
        copy.peerEndpoint = peerEndpoint;
        copy.allowedIps = copyArray(allowedIps);
        copy.persistentKeepaliveSeconds = persistentKeepaliveSeconds;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        return copy;
    }

    public String validate() {
        normalize();

        String validationError = WireGuardUserspaceConfig.validate(
                privateKey,
                peerPublicKey,
                peerEndpoint,
                localAddresses,
                allowedIps);
        if (validationError != null) {
            return validationError;
        }

        try {
            WireGuardUserspaceConfig.keyToHex(privateKey);
            WireGuardUserspaceConfig.keyToHex(peerPublicKey);
            if (presharedKey != null && !presharedKey.isEmpty()) {
                WireGuardUserspaceConfig.keyToHex(presharedKey);
            }
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        if (!isValidEndpoint(peerEndpoint)) {
            return "invalid WireGuard endpoint";
        }
        if (mtu <= 0 || mtu > 65535) {
            return "invalid WireGuard MTU";
        }
        if (persistentKeepaliveSeconds < 0 || persistentKeepaliveSeconds > 65535) {
            return "invalid WireGuard persistent keepalive";
        }

        return null;
    }

    public String buildUserspaceConfig() {
        normalize();
        return WireGuardUserspaceConfig.build(
                privateKey,
                peerPublicKey,
                presharedKey,
                peerEndpoint,
                persistentKeepaliveSeconds,
                allowedIps);
    }

    public void normalize() {
        id = nullToEmpty(id);
        name = nullToEmpty(name);
        privateKey = nullToEmpty(privateKey).trim();
        localAddresses = normalizeArray(localAddresses);
        dnsServers = normalizeArray(dnsServers);
        if (mtu <= 0) {
            mtu = DEFAULT_MTU;
        }
        peerPublicKey = nullToEmpty(peerPublicKey).trim();
        presharedKey = nullToEmpty(presharedKey).trim();
        peerEndpoint = nullToEmpty(peerEndpoint).trim();
        allowedIps = normalizeArray(allowedIps);
        if (persistentKeepaliveSeconds < 0) {
            persistentKeepaliveSeconds = DEFAULT_PERSISTENT_KEEPALIVE_SECONDS;
        }
    }

    public String getDisplayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (peerEndpoint != null && !peerEndpoint.trim().isEmpty()) {
            return peerEndpoint.trim();
        }
        return "WireGuard";
    }

    static boolean isValidEndpoint(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        endpoint = endpoint.trim();
        if (endpoint.isEmpty()) {
            return false;
        }

        int portSeparator;
        if (endpoint.charAt(0) == '[') {
            int close = endpoint.indexOf(']');
            if (close <= 1 || close + 1 >= endpoint.length() || endpoint.charAt(close + 1) != ':') {
                return false;
            }
            portSeparator = close + 1;
        } else {
            portSeparator = endpoint.lastIndexOf(':');
            if (portSeparator <= 0 || portSeparator != endpoint.indexOf(':')) {
                return false;
            }
        }

        String host = endpoint.substring(0, portSeparator).trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1).trim();
        }
        if (host.isEmpty()) {
            return false;
        }

        String portString = endpoint.substring(portSeparator + 1).trim();
        if (portString.isEmpty()) {
            return false;
        }
        for (int i = 0; i < portString.length(); i++) {
            char ch = portString.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        try {
            int port = Integer.parseInt(portString);
            return port > 0 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String[] normalizeArray(String[] values) {
        if (values == null || values.length == 0) {
            return new String[0];
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            value = value.trim();
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return normalized.toArray(new String[0]);
    }

    private static String[] copyArray(String[] values) {
        if (values == null || values.length == 0) {
            return new String[0];
        }
        String[] copy = new String[values.length];
        System.arraycopy(values, 0, copy, 0, values.length);
        return copy;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
