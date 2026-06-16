package org.telegram.messenger;

public final class TunnelConfigParser {

    private TunnelConfigParser() {
    }

    public static WireGuardProfile parse(String configText, String fallbackName) {
        return WireGuardConfigParser.parse(configText, fallbackName);
    }

    public static WireGuardProfile parse(String configText, String fallbackName, TunnelProtocol forcedProtocol) {
        return WireGuardConfigParser.parse(configText, fallbackName, forcedProtocol);
    }

    public static String[] parseList(String value) {
        return WireGuardConfigParser.parseList(value);
    }
}
