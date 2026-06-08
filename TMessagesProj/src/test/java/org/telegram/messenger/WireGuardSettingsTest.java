package org.telegram.messenger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class WireGuardSettingsTest {

    @Test
    public void profileSerializationRoundTripsAllFields() {
        WireGuardProfile profile = new WireGuardProfile();
        profile.id = "profile-1";
        profile.name = "Office";
        profile.privateKey = key(1);
        profile.localAddresses = new String[]{"10.7.0.2/32", "fd00::2/128"};
        profile.dnsServers = new String[]{"1.1.1.1"};
        profile.mtu = 1280;
        profile.peerPublicKey = key(2);
        profile.presharedKey = key(3);
        profile.peerEndpoint = "wg.example.com:51820";
        profile.allowedIps = new String[]{"0.0.0.0/0", "::/0"};
        profile.persistentKeepaliveSeconds = 25;
        profile.createdAt = 101;
        profile.updatedAt = 202;

        byte[] serialized = WireGuardSecureStore.serializeProfiles(Arrays.asList(profile));
        ArrayList<WireGuardProfile> profiles = WireGuardSecureStore.deserializeProfiles(serialized);

        assertEquals(1, profiles.size());
        WireGuardProfile restored = profiles.get(0);
        assertEquals("profile-1", restored.id);
        assertEquals("Office", restored.name);
        assertEquals(key(1), restored.privateKey);
        assertArrayEquals(new String[]{"10.7.0.2/32", "fd00::2/128"}, restored.localAddresses);
        assertArrayEquals(new String[]{"1.1.1.1"}, restored.dnsServers);
        assertEquals(1280, restored.mtu);
        assertEquals(key(2), restored.peerPublicKey);
        assertEquals(key(3), restored.presharedKey);
        assertEquals("wg.example.com:51820", restored.peerEndpoint);
        assertArrayEquals(new String[]{"0.0.0.0/0", "::/0"}, restored.allowedIps);
        assertEquals(25, restored.persistentKeepaliveSeconds);
        assertEquals(101, restored.createdAt);
        assertEquals(202, restored.updatedAt);
    }

    @Test
    public void profileSerializationHandlesEmptyList() {
        byte[] serialized = WireGuardSecureStore.serializeProfiles(new ArrayList<>());

        assertEquals(0, WireGuardSecureStore.deserializeProfiles(serialized).size());
        assertEquals(0, WireGuardSecureStore.deserializeProfiles(new byte[0]).size());
    }

    @Test
    public void encryptedProfileEnvelopeRoundTripsSerializedProfiles() throws Exception {
        WireGuardProfile profile = new WireGuardProfile();
        profile.id = "profile-1";
        profile.name = "Office";
        profile.privateKey = key(1);
        profile.localAddresses = new String[]{"10.7.0.2/32"};
        profile.peerPublicKey = key(2);
        profile.peerEndpoint = "wg.example.com:51820";
        profile.allowedIps = new String[]{"0.0.0.0/0"};

        byte[] serialized = WireGuardSecureStore.serializeProfiles(Arrays.asList(profile));
        String encrypted = WireGuardSecureStore.encryptProfiles(serialized, testKey());
        byte[] decrypted = WireGuardSecureStore.decryptProfiles(encrypted, testKey());

        assertArrayEquals(serialized, decrypted);
        assertEquals(1, WireGuardSecureStore.deserializeProfiles(decrypted).size());
    }

    @Test
    public void encryptedProfileEnvelopeRejectsWrongKey() throws Exception {
        byte[] serialized = WireGuardSecureStore.serializeProfiles(new ArrayList<>());
        String encrypted = WireGuardSecureStore.encryptProfiles(serialized, testKey());

        try {
            WireGuardSecureStore.decryptProfiles(encrypted, wrongTestKey());
            fail("Expected WireGuard profile ciphertext to reject the wrong key");
        } catch (Exception expected) {
            // expected
        }
    }

    private static String key(int value) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) value;
        }
        return Base64.getEncoder().encodeToString(key);
    }

    private static SecretKeySpec testKey() {
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 7);
        return new SecretKeySpec(key, "AES");
    }

    private static SecretKeySpec wrongTestKey() {
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 8);
        return new SecretKeySpec(key, "AES");
    }
}
