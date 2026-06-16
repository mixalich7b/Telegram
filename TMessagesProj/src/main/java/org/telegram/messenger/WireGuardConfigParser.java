package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WireGuardConfigParser {

    private static final String SECTION_INTERFACE = "interface";
    private static final String SECTION_PEER = "peer";
    private static final String[] AMNEZIA_INTERFACE_KEYS = new String[]{
            "jc", "jmin", "jmax",
            "s1", "s2", "s3", "s4",
            "h1", "h2", "h3", "h4",
            "i1", "i2", "i3", "i4", "i5"
    };

    private WireGuardConfigParser() {
    }

    public static WireGuardProfile parse(String configText, String fallbackName) {
        return parse(configText, fallbackName, null);
    }

    public static WireGuardProfile parse(String configText, String fallbackName, TunnelProtocol forcedProtocol) {
        if (configText == null) {
            throw new IllegalArgumentException("WireGuard config is empty");
        }

        WireGuardProfile profile = new WireGuardProfile();
        if (forcedProtocol != null) {
            profile.protocol = forcedProtocol;
        }
        String currentSection = "";
        int interfaceSections = 0;
        int peerSections = 0;

        String[] lines = configText.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.US);
                if (SECTION_INTERFACE.equals(currentSection)) {
                    interfaceSections++;
                } else if (SECTION_PEER.equals(currentSection)) {
                    peerSections++;
                } else {
                    currentSection = "";
                }
                continue;
            }

            int equalsIndex = line.indexOf('=');
            if (equalsIndex <= 0) {
                throw new IllegalArgumentException("invalid WireGuard config line " + (i + 1));
            }
            String key = line.substring(0, equalsIndex).trim().toLowerCase(Locale.US);
            String value = line.substring(equalsIndex + 1).trim();

            if (SECTION_INTERFACE.equals(currentSection)) {
                applyInterfaceValue(profile, key, value, forcedProtocol);
            } else if (SECTION_PEER.equals(currentSection)) {
                if (isAmneziaInterfaceKey(key)) {
                    throw new IllegalArgumentException("AmneziaWG keys must be in Interface section");
                }
                applyPeerValue(profile, key, value);
            }
        }

        if (interfaceSections != 1) {
            throw new IllegalArgumentException("WireGuard config must contain exactly one Interface section");
        }
        if (peerSections != 1) {
            throw new IllegalArgumentException("WireGuard config must contain exactly one Peer section");
        }

        profile.name = normalizeName(fallbackName, profile.peerEndpoint, profile.protocol);
        profile.normalize();
        String validationError = profile.validate();
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }
        return profile;
    }

    public static String[] parseList(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = value.split(",");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            part = part.trim();
            if (!part.isEmpty()) {
                values.add(part);
            }
        }
        return values.toArray(new String[0]);
    }

    private static void applyInterfaceValue(WireGuardProfile profile, String key, String value, TunnelProtocol forcedProtocol) {
        switch (key) {
            case "privatekey":
                profile.privateKey = value;
                break;
            case "address":
                profile.localAddresses = parseList(value);
                break;
            case "dns":
                profile.dnsServers = parseList(value);
                break;
            case "mtu":
                profile.mtu = parsePositiveInt(value, "invalid WireGuard MTU");
                break;
            case "jc":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaJc = parsePositiveInt(value, "invalid AmneziaWG Jc");
                break;
            case "jmin":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaJmin = parsePositiveInt(value, "invalid AmneziaWG Jmin");
                break;
            case "jmax":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaJmax = parsePositiveInt(value, "invalid AmneziaWG Jmax");
                break;
            case "s1":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaS1 = parsePositiveInt(value, "invalid AmneziaWG S1");
                break;
            case "s2":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaS2 = parsePositiveInt(value, "invalid AmneziaWG S2");
                break;
            case "s3":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaS3 = parsePositiveInt(value, "invalid AmneziaWG S3");
                break;
            case "s4":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaS4 = parsePositiveInt(value, "invalid AmneziaWG S4");
                break;
            case "h1":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaH1 = value;
                break;
            case "h2":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaH2 = value;
                break;
            case "h3":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaH3 = value;
                break;
            case "h4":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaH4 = value;
                break;
            case "i1":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaI1 = value;
                break;
            case "i2":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaI2 = value;
                break;
            case "i3":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaI3 = value;
                break;
            case "i4":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaI4 = value;
                break;
            case "i5":
                markAmneziaWG(profile, forcedProtocol);
                profile.amneziaI5 = value;
                break;
        }
    }

    private static void markAmneziaWG(WireGuardProfile profile, TunnelProtocol forcedProtocol) {
        if (forcedProtocol == TunnelProtocol.WIREGUARD) {
            throw new IllegalArgumentException("AmneziaWG keys are not valid for WireGuard profiles");
        }
        profile.protocol = TunnelProtocol.AMNEZIA_WG;
    }

    private static void applyPeerValue(WireGuardProfile profile, String key, String value) {
        switch (key) {
            case "publickey":
                profile.peerPublicKey = value;
                break;
            case "presharedkey":
                profile.presharedKey = value;
                break;
            case "allowedips":
                profile.allowedIps = parseList(value);
                break;
            case "endpoint":
                profile.peerEndpoint = value;
                break;
            case "persistentkeepalive":
                profile.persistentKeepaliveSeconds = parsePositiveInt(value, "invalid WireGuard persistent keepalive");
                break;
        }
    }

    private static int parsePositiveInt(String value, String error) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
    }

    private static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuote = !inQuote;
            } else if (!inQuote && (ch == '#' || ch == ';')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static boolean isAmneziaInterfaceKey(String key) {
        for (String candidate : AMNEZIA_INTERFACE_KEYS) {
            if (candidate.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeName(String fallbackName, String endpoint, TunnelProtocol protocol) {
        if (fallbackName != null) {
            fallbackName = fallbackName.trim();
            if (!fallbackName.isEmpty()) {
                if (fallbackName.endsWith(".conf") && fallbackName.length() > ".conf".length()) {
                    return fallbackName.substring(0, fallbackName.length() - ".conf".length());
                }
                return fallbackName;
            }
        }
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            return endpoint.trim();
        }
        return protocol == TunnelProtocol.AMNEZIA_WG ? "AmneziaWG" : "WireGuard";
    }
}
