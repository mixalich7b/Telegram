package org.telegram.messenger;

import java.net.Inet6Address;
import java.net.InetAddress;
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
        boolean bracketedHost = host.startsWith("[") && host.endsWith("]");
        if (bracketedHost) {
            host = host.substring(1, host.length() - 1).trim();
        }
        if (host.isEmpty() || !isValidEndpointHost(host, bracketedHost)) {
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

    private static boolean isValidEndpointHost(String host, boolean bracketedHost) {
        if (bracketedHost) {
            return isValidIpv6Literal(host);
        }
        if (host.indexOf(':') >= 0 || hasWhitespaceOrControl(host)) {
            return false;
        }
        if (isValidIpv4Literal(host)) {
            return true;
        }
        if (looksLikeIpv4Literal(host)) {
            return false;
        }
        return isValidDomainName(host);
    }

    private static boolean isValidIpv6Literal(String host) {
        if (host.indexOf(':') < 0 || hasWhitespaceOrControl(host)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address instanceof Inet6Address;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isValidIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (ch < '0' || ch > '9') {
                    return false;
                }
                value = value * 10 + (ch - '0');
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeIpv4Literal(String host) {
        if (host.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch != '.' && (ch < '0' || ch > '9')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidDomainName(String host) {
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                return false;
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char ch = label.charAt(i);
                boolean alpha = ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z';
                boolean digit = ch >= '0' && ch <= '9';
                if (!alpha && !digit && ch != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasWhitespaceOrControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                return true;
            }
        }
        return false;
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
