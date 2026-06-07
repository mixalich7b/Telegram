package org.telegram.messenger;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WireGuardUserspaceConfigTest {

    @Test
    public void keyToHexConvertsThirtyTwoByteBase64Key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }

        String hex = WireGuardUserspaceConfig.keyToHex(Base64.getEncoder().encodeToString(key));

        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", hex);
    }

    @Test(expected = IllegalArgumentException.class)
    public void keyToHexRejectsWrongLengthKey() {
        WireGuardUserspaceConfig.keyToHex(Base64.getEncoder().encodeToString(new byte[31]));
    }

    @Test
    public void validateReportsMissingRequiredValues() {
        String validation = WireGuardUserspaceConfig.validate("", "", "", new String[0], new String[0]);

        assertTrue(validation.contains("PRIVATE_KEY"));
        assertTrue(validation.contains("PEER_PUBLIC_KEY"));
        assertTrue(validation.contains("PEER_ENDPOINT"));
        assertTrue(validation.contains("LOCAL_ADDRESSES"));
        assertTrue(validation.contains("ALLOWED_IPS"));
    }

    @Test
    public void validateAcceptsCompleteConfig() {
        assertNull(WireGuardUserspaceConfig.validate(key(1), key(2), "127.0.0.1:51820", new String[]{"10.0.0.2"}, new String[]{"0.0.0.0/0"}));
    }

    @Test
    public void buildCreatesUserspaceIpcConfig() {
        String config = WireGuardUserspaceConfig.build(
                key(1),
                key(2),
                "",
                "127.0.0.1:51820",
                25,
                new String[]{"0.0.0.0/0", "::/0"});

        assertTrue(config.contains("private_key=0101010101010101010101010101010101010101010101010101010101010101\n"));
        assertTrue(config.contains("replace_peers=true\n"));
        assertTrue(config.contains("public_key=0202020202020202020202020202020202020202020202020202020202020202\n"));
        assertTrue(config.contains("endpoint=127.0.0.1:51820\n"));
        assertTrue(config.contains("persistent_keepalive_interval=25\n"));
        assertTrue(config.contains("replace_allowed_ips=true\n"));
        assertTrue(config.contains("allowed_ip=0.0.0.0/0\n"));
        assertTrue(config.contains("allowed_ip=::/0\n"));
        assertFalse(config.contains("preshared_key="));
    }

    @Test
    public void buildAddsPresharedKeyWhenConfigured() {
        String config = WireGuardUserspaceConfig.build(
                key(1),
                key(2),
                key(3),
                "127.0.0.1:51820",
                25,
                new String[]{"0.0.0.0/0"});

        assertTrue(config.contains("preshared_key=0303030303030303030303030303030303030303030303030303030303030303\n"));
    }

    private static String key(int value) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) value;
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
