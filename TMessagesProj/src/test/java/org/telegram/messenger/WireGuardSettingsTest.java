package org.telegram.messenger;

import org.junit.Test;
import org.telegram.tgnet.SerializedData;

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
        profile.protocol = TunnelProtocol.AMNEZIA_WG;
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
        profile.amneziaJc = 4;
        profile.amneziaJmin = 50;
        profile.amneziaJmax = 100;
        profile.amneziaS1 = 87;
        profile.amneziaS2 = 65;
        profile.amneziaS3 = 10;
        profile.amneziaS4 = 11;
        profile.amneziaH1 = "100-200";
        profile.amneziaH2 = "300";
        profile.amneziaH3 = "400-450";
        profile.amneziaH4 = "500";
        profile.amneziaI1 = "<b 0x0102>";
        profile.amneziaI2 = "<r 8>";
        profile.amneziaI3 = "<rd 4>";
        profile.amneziaI4 = "<rc 5>";
        profile.amneziaI5 = "<t ignored>";
        profile.createdAt = 101;
        profile.updatedAt = 202;

        byte[] serialized = WireGuardSecureStore.serializeProfiles(Arrays.asList(profile));
        ArrayList<WireGuardProfile> profiles = WireGuardSecureStore.deserializeProfiles(serialized);

        assertEquals(1, profiles.size());
        WireGuardProfile restored = profiles.get(0);
        assertEquals("profile-1", restored.id);
        assertEquals(TunnelProtocol.AMNEZIA_WG, restored.protocol);
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
        assertEquals(4, restored.amneziaJc);
        assertEquals(50, restored.amneziaJmin);
        assertEquals(100, restored.amneziaJmax);
        assertEquals(87, restored.amneziaS1);
        assertEquals(65, restored.amneziaS2);
        assertEquals(10, restored.amneziaS3);
        assertEquals(11, restored.amneziaS4);
        assertEquals("100-200", restored.amneziaH1);
        assertEquals("300", restored.amneziaH2);
        assertEquals("400-450", restored.amneziaH3);
        assertEquals("500", restored.amneziaH4);
        assertEquals("<b 0x0102>", restored.amneziaI1);
        assertEquals("<r 8>", restored.amneziaI2);
        assertEquals("<rd 4>", restored.amneziaI3);
        assertEquals("<rc 5>", restored.amneziaI4);
        assertEquals("<t ignored>", restored.amneziaI5);
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
    public void legacyWireGuardProfileSerializationMigratesToWireGuardProtocol() {
        byte[] serialized = serializeLegacyV1Profile();
        ArrayList<WireGuardProfile> profiles = WireGuardSecureStore.deserializeProfiles(serialized);

        assertEquals(1, profiles.size());
        assertEquals(TunnelProtocol.WIREGUARD, profiles.get(0).protocol);
        assertEquals("legacy", profiles.get(0).id);
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

    private static byte[] serializeLegacyV1Profile() {
        SerializedData data = new SerializedData();
        try {
            data.writeInt32(-1);
            data.writeByte(1);
            data.writeInt32(1);
            data.writeString("legacy");
            data.writeString("Legacy");
            data.writeString(key(1));
            writeStringArray(data, new String[]{"10.7.0.2/32"});
            writeStringArray(data, new String[]{"1.1.1.1"});
            data.writeInt32(1420);
            data.writeString(key(2));
            data.writeString("");
            data.writeString("wg.example.com:51820");
            writeStringArray(data, new String[]{"0.0.0.0/0"});
            data.writeInt32(25);
            data.writeInt64(101);
            data.writeInt64(202);
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    private static void writeStringArray(SerializedData data, String[] values) {
        data.writeInt32(values.length);
        for (String value : values) {
            data.writeString(value);
        }
    }
}
