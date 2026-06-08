package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WireGuardConfigParser {

    private static final String SECTION_INTERFACE = "interface";
    private static final String SECTION_PEER = "peer";

    private WireGuardConfigParser() {
    }

    public static WireGuardProfile parse(String configText, String fallbackName) {
        if (configText == null) {
            throw new IllegalArgumentException("WireGuard config is empty");
        }

        WireGuardProfile profile = new WireGuardProfile();
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
                applyInterfaceValue(profile, key, value);
            } else if (SECTION_PEER.equals(currentSection)) {
                applyPeerValue(profile, key, value);
            }
        }

        if (interfaceSections != 1) {
            throw new IllegalArgumentException("WireGuard config must contain exactly one Interface section");
        }
        if (peerSections != 1) {
            throw new IllegalArgumentException("WireGuard config must contain exactly one Peer section");
        }

        profile.name = normalizeName(fallbackName, profile.peerEndpoint);
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

    private static void applyInterfaceValue(WireGuardProfile profile, String key, String value) {
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
        }
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

    private static String normalizeName(String fallbackName, String endpoint) {
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
        return "WireGuard";
    }
}
