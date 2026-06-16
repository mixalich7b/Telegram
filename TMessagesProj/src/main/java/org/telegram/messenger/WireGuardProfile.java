package org.telegram.messenger;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WireGuardProfile {

    public static final int DEFAULT_MTU = 1420;
    public static final int DEFAULT_PERSISTENT_KEEPALIVE_SECONDS = 0;
    private static final long MAX_UINT32 = 0xffffffffL;
    private static final int MAX_CPS_GENERATED_LENGTH = 65535;
    private static final Pattern CPS_TAG_PATTERN = Pattern.compile("<([^<>]+)>");
    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]+");

    public String id;
    public TunnelProtocol protocol;
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
    public int amneziaJc;
    public int amneziaJmin;
    public int amneziaJmax;
    public int amneziaS1;
    public int amneziaS2;
    public int amneziaS3;
    public int amneziaS4;
    public String amneziaH1;
    public String amneziaH2;
    public String amneziaH3;
    public String amneziaH4;
    public String amneziaI1;
    public String amneziaI2;
    public String amneziaI3;
    public String amneziaI4;
    public String amneziaI5;
    public long createdAt;
    public long updatedAt;

    public WireGuardProfile() {
        id = "";
        protocol = TunnelProtocol.WIREGUARD;
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
        amneziaH1 = "";
        amneziaH2 = "";
        amneziaH3 = "";
        amneziaH4 = "";
        amneziaI1 = "";
        amneziaI2 = "";
        amneziaI3 = "";
        amneziaI4 = "";
        amneziaI5 = "";
    }

    public WireGuardProfile copy() {
        WireGuardProfile copy = new WireGuardProfile();
        copy.id = id;
        copy.protocol = protocol;
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
        copy.amneziaJc = amneziaJc;
        copy.amneziaJmin = amneziaJmin;
        copy.amneziaJmax = amneziaJmax;
        copy.amneziaS1 = amneziaS1;
        copy.amneziaS2 = amneziaS2;
        copy.amneziaS3 = amneziaS3;
        copy.amneziaS4 = amneziaS4;
        copy.amneziaH1 = amneziaH1;
        copy.amneziaH2 = amneziaH2;
        copy.amneziaH3 = amneziaH3;
        copy.amneziaH4 = amneziaH4;
        copy.amneziaI1 = amneziaI1;
        copy.amneziaI2 = amneziaI2;
        copy.amneziaI3 = amneziaI3;
        copy.amneziaI4 = amneziaI4;
        copy.amneziaI5 = amneziaI5;
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
        if (protocol == TunnelProtocol.AMNEZIA_WG) {
            validationError = validateAmneziaWG();
            if (validationError != null) {
                return validationError;
            }
        }

        return null;
    }

    public String buildUserspaceConfig() {
        normalize();
        return WireGuardUserspaceConfig.build(
                this);
    }

    public void normalize() {
        id = nullToEmpty(id);
        if (protocol == null) {
            protocol = TunnelProtocol.WIREGUARD;
        }
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
        amneziaH1 = nullToEmpty(amneziaH1).trim();
        amneziaH2 = nullToEmpty(amneziaH2).trim();
        amneziaH3 = nullToEmpty(amneziaH3).trim();
        amneziaH4 = nullToEmpty(amneziaH4).trim();
        amneziaI1 = nullToEmpty(amneziaI1).trim();
        amneziaI2 = nullToEmpty(amneziaI2).trim();
        amneziaI3 = nullToEmpty(amneziaI3).trim();
        amneziaI4 = nullToEmpty(amneziaI4).trim();
        amneziaI5 = nullToEmpty(amneziaI5).trim();
    }

    public String getDisplayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (peerEndpoint != null && !peerEndpoint.trim().isEmpty()) {
            return peerEndpoint.trim();
        }
        return protocol == TunnelProtocol.AMNEZIA_WG ? "AmneziaWG" : "WireGuard";
    }

    public String getProtocolDisplayName() {
        return (protocol == null ? TunnelProtocol.WIREGUARD : protocol).displayName();
    }

    boolean hasAmneziaWGFields() {
        return amneziaJc != 0
                || amneziaJmin != 0
                || amneziaJmax != 0
                || amneziaS1 != 0
                || amneziaS2 != 0
                || amneziaS3 != 0
                || amneziaS4 != 0
                || !nullToEmpty(amneziaH1).trim().isEmpty()
                || !nullToEmpty(amneziaH2).trim().isEmpty()
                || !nullToEmpty(amneziaH3).trim().isEmpty()
                || !nullToEmpty(amneziaH4).trim().isEmpty()
                || !nullToEmpty(amneziaI1).trim().isEmpty()
                || !nullToEmpty(amneziaI2).trim().isEmpty()
                || !nullToEmpty(amneziaI3).trim().isEmpty()
                || !nullToEmpty(amneziaI4).trim().isEmpty()
                || !nullToEmpty(amneziaI5).trim().isEmpty();
    }

    private String validateAmneziaWG() {
        if (amneziaJc < 0) {
            return "invalid AmneziaWG Jc";
        }
        boolean hasJunkGroup = amneziaJc > 0 || amneziaJmin > 0 || amneziaJmax > 0;
        if (hasJunkGroup) {
            if (amneziaJc <= 0 || amneziaJmin <= 0 || amneziaJmax <= 0 || amneziaJmin > amneziaJmax) {
                return "invalid AmneziaWG Jc/Jmin/Jmax";
            }
        }
        if (amneziaS1 < 0 || amneziaS2 < 0 || amneziaS3 < 0 || amneziaS4 < 0) {
            return "invalid AmneziaWG padding";
        }

        HeaderRange[] ranges = new HeaderRange[]{
                parseHeaderRange(amneziaH1, "H1"),
                parseHeaderRange(amneziaH2, "H2"),
                parseHeaderRange(amneziaH3, "H3"),
                parseHeaderRange(amneziaH4, "H4")
        };
        for (HeaderRange range : ranges) {
            if (range != null && !range.valid) {
                return "invalid AmneziaWG header " + range.name;
            }
        }
        for (int i = 0; i < ranges.length; i++) {
            HeaderRange lhs = ranges[i];
            if (lhs == null || !lhs.present) {
                continue;
            }
            for (int j = i + 1; j < ranges.length; j++) {
                HeaderRange rhs = ranges[j];
                if (rhs != null && rhs.present && lhs.overlaps(rhs)) {
                    return "overlapping AmneziaWG headers";
                }
            }
        }

        String signatureError = validateCps(amneziaI1, "I1");
        if (signatureError != null) {
            return signatureError;
        }
        signatureError = validateCps(amneziaI2, "I2");
        if (signatureError != null) {
            return signatureError;
        }
        signatureError = validateCps(amneziaI3, "I3");
        if (signatureError != null) {
            return signatureError;
        }
        signatureError = validateCps(amneziaI4, "I4");
        if (signatureError != null) {
            return signatureError;
        }
        return validateCps(amneziaI5, "I5");
    }

    private static HeaderRange parseHeaderRange(String value, String name) {
        value = nullToEmpty(value).trim();
        if (value.isEmpty()) {
            return null;
        }
        int separator = value.indexOf('-');
        try {
            if (separator < 0) {
                long parsed = parseUint32(value);
                return new HeaderRange(name, parsed, parsed, true, true);
            }
            if (separator != value.lastIndexOf('-')) {
                return new HeaderRange(name, 0, 0, false, true);
            }
            long start = parseUint32(value.substring(0, separator).trim());
            long end = parseUint32(value.substring(separator + 1).trim());
            return new HeaderRange(name, start, end, start <= end, true);
        } catch (NumberFormatException e) {
            return new HeaderRange(name, 0, 0, false, true);
        }
    }

    private static long parseUint32(String value) {
        if (value == null || value.isEmpty()) {
            throw new NumberFormatException();
        }
        long parsed = Long.parseLong(value);
        if (parsed < 0 || parsed > MAX_UINT32) {
            throw new NumberFormatException();
        }
        return parsed;
    }

    private static String validateCps(String value, String name) {
        value = nullToEmpty(value).trim();
        if (value.isEmpty()) {
            return null;
        }
        int position = 0;
        Matcher matcher = CPS_TAG_PATTERN.matcher(value);
        while (position < value.length()) {
            while (position < value.length() && Character.isWhitespace(value.charAt(position))) {
                position++;
            }
            if (position >= value.length()) {
                break;
            }
            matcher.region(position, value.length());
            if (!matcher.lookingAt()) {
                return "invalid AmneziaWG signature " + name;
            }
            if (!validateCpsTag(matcher.group(1))) {
                return "invalid AmneziaWG signature " + name;
            }
            position = matcher.end();
        }
        return null;
    }

    private static boolean validateCpsTag(String tag) {
        tag = tag == null ? "" : tag.trim();
        String[] parts = tag.split("\\s+", -1);
        if (parts.length == 0 || parts[0].isEmpty()) {
            return false;
        }
        String op = parts[0];
        if ("t".equals(op) || "d".equals(op) || "ds".equals(op)) {
            return true;
        }
        if (parts.length < 2) {
            return false;
        }
        String argument = parts[1];
        if ("b".equals(op)) {
            if (argument.startsWith("0x") || argument.startsWith("0X")) {
                argument = argument.substring(2);
            }
            return !argument.isEmpty() && argument.length() % 2 == 0 && HEX_PATTERN.matcher(argument).matches();
        }
        if ("r".equals(op) || "rd".equals(op) || "rc".equals(op) || "dz".equals(op)) {
            try {
                int length = Integer.parseInt(argument);
                return length >= 0 && length <= MAX_CPS_GENERATED_LENGTH;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
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

    private static final class HeaderRange {
        final String name;
        final long start;
        final long end;
        final boolean valid;
        final boolean present;

        HeaderRange(String name, long start, long end, boolean valid, boolean present) {
            this.name = name;
            this.start = start;
            this.end = end;
            this.valid = valid;
            this.present = present;
        }

        boolean overlaps(HeaderRange other) {
            return start <= other.end && other.start <= end;
        }
    }
}
