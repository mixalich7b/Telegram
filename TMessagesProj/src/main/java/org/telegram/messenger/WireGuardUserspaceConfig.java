package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class WireGuardUserspaceConfig {

    private WireGuardUserspaceConfig() {
    }

    static String build(WireGuardProfile profile) {
        if (profile == null) {
            return "";
        }
        profile.normalize();
        return build(
                profile.privateKey,
                profile.peerPublicKey,
                profile.presharedKey,
                profile.peerEndpoint,
                profile.persistentKeepaliveSeconds,
                profile.allowedIps,
                profile.protocol,
                profile);
    }

    static String build(String privateKey, String peerPublicKey, String presharedKey, String peerEndpoint, int keepaliveSeconds, String[] allowedIps) {
        return build(privateKey, peerPublicKey, presharedKey, peerEndpoint, keepaliveSeconds, allowedIps, TunnelProtocol.WIREGUARD, null);
    }

    private static String build(String privateKey, String peerPublicKey, String presharedKey, String peerEndpoint, int keepaliveSeconds, String[] allowedIps, TunnelProtocol protocol, WireGuardProfile profile) {
        StringBuilder builder = new StringBuilder();
        builder.append("private_key=").append(keyToHex(privateKey)).append('\n');
        builder.append("replace_peers=true\n");
        if (protocol == TunnelProtocol.AMNEZIA_WG && profile != null) {
            appendAmneziaWG(builder, profile);
        }
        builder.append("public_key=").append(keyToHex(peerPublicKey)).append('\n');
        if (presharedKey != null && !presharedKey.isEmpty()) {
            builder.append("preshared_key=").append(keyToHex(presharedKey)).append('\n');
        }
        builder.append("endpoint=").append(peerEndpoint).append('\n');
        builder.append("persistent_keepalive_interval=").append(keepaliveSeconds).append('\n');
        builder.append("replace_allowed_ips=true\n");
        for (String allowedIp : allowedIps) {
            builder.append("allowed_ip=").append(allowedIp).append('\n');
        }
        return builder.toString();
    }

    private static void appendAmneziaWG(StringBuilder builder, WireGuardProfile profile) {
        appendPositive(builder, "jc", profile.amneziaJc);
        appendPositive(builder, "jmin", profile.amneziaJmin);
        appendPositive(builder, "jmax", profile.amneziaJmax);
        appendPositive(builder, "s1", profile.amneziaS1);
        appendPositive(builder, "s2", profile.amneziaS2);
        appendPositive(builder, "s3", profile.amneziaS3);
        appendPositive(builder, "s4", profile.amneziaS4);
        appendString(builder, "h1", profile.amneziaH1);
        appendString(builder, "h2", profile.amneziaH2);
        appendString(builder, "h3", profile.amneziaH3);
        appendString(builder, "h4", profile.amneziaH4);
        appendString(builder, "i1", profile.amneziaI1);
        appendString(builder, "i2", profile.amneziaI2);
        appendString(builder, "i3", profile.amneziaI3);
        appendString(builder, "i4", profile.amneziaI4);
        appendString(builder, "i5", profile.amneziaI5);
    }

    private static void appendPositive(StringBuilder builder, String key, int value) {
        if (value > 0) {
            builder.append(key).append('=').append(value).append('\n');
        }
    }

    private static void appendString(StringBuilder builder, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            builder.append(key).append('=').append(value.trim()).append('\n');
        }
    }

    static String validate(String privateKey, String peerPublicKey, String peerEndpoint, String[] localAddresses, String[] allowedIps) {
        List<String> missing = new ArrayList<>();
        if (privateKey == null || privateKey.isEmpty()) {
            missing.add("PRIVATE_KEY");
        }
        if (peerPublicKey == null || peerPublicKey.isEmpty()) {
            missing.add("PEER_PUBLIC_KEY");
        }
        if (peerEndpoint == null || peerEndpoint.isEmpty()) {
            missing.add("PEER_ENDPOINT");
        }
        if (localAddresses == null || localAddresses.length == 0) {
            missing.add("LOCAL_ADDRESSES");
        }
        if (allowedIps == null || allowedIps.length == 0) {
            missing.add("ALLOWED_IPS");
        }
        if (!missing.isEmpty()) {
            return "missing WireGuard config values: " + missing;
        }
        return null;
    }

    static String keyToHex(String key) {
        byte[] bytes = Base64.getDecoder().decode(key);
        if (bytes.length != 32) {
            throw new IllegalArgumentException("WireGuard keys must decode to 32 bytes");
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if (unsigned < 16) {
                builder.append('0');
            }
            builder.append(Integer.toHexString(unsigned));
        }
        return builder.toString();
    }
}
