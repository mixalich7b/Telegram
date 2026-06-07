package org.telegram.messenger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class WireGuardUserspaceConfig {

    private WireGuardUserspaceConfig() {
    }

    static String build(String privateKey, String peerPublicKey, String presharedKey, String peerEndpoint, int keepaliveSeconds, String[] allowedIps) {
        StringBuilder builder = new StringBuilder();
        builder.append("private_key=").append(keyToHex(privateKey)).append('\n');
        builder.append("replace_peers=true\n");
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
