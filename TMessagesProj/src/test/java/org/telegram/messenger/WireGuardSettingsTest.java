package org.telegram.messenger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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

        String serialized = WireGuardSettings.serializeProfiles(Arrays.asList(profile));
        ArrayList<WireGuardProfile> profiles = WireGuardSettings.deserializeProfiles(serialized);

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
        String serialized = WireGuardSettings.serializeProfiles(new ArrayList<>());

        assertEquals(0, WireGuardSettings.deserializeProfiles(serialized).size());
        assertEquals(0, WireGuardSettings.deserializeProfiles("").size());
    }

    private static String key(int value) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) value;
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
