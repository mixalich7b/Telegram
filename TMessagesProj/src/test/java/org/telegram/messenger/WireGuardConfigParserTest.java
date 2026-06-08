package org.telegram.messenger;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WireGuardConfigParserTest {

    @Test
    public void parseAcceptsStandardWireGuardConfig() {
        WireGuardProfile profile = WireGuardConfigParser.parse(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32, fd00::2/128\n" +
                        "DNS = 1.1.1.1, 2606:4700:4700::1111\n" +
                        "MTU = 1280\n" +
                        "\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "PresharedKey = " + key(3) + "\n" +
                        "AllowedIPs = 0.0.0.0/0, ::/0\n" +
                        "Endpoint = wg.example.com:51820\n" +
                        "PersistentKeepalive = 25\n",
                "office.conf");

        assertEquals("office", profile.name);
        assertEquals(key(1), profile.privateKey);
        assertArrayEquals(new String[]{"10.7.0.2/32", "fd00::2/128"}, profile.localAddresses);
        assertArrayEquals(new String[]{"1.1.1.1", "2606:4700:4700::1111"}, profile.dnsServers);
        assertEquals(1280, profile.mtu);
        assertEquals(key(2), profile.peerPublicKey);
        assertEquals(key(3), profile.presharedKey);
        assertEquals("wg.example.com:51820", profile.peerEndpoint);
        assertArrayEquals(new String[]{"0.0.0.0/0", "::/0"}, profile.allowedIps);
        assertEquals(25, profile.persistentKeepaliveSeconds);
        assertNull(profile.validate());
    }

    @Test
    public void parseAcceptsCommentsAndBracketedIpv6Endpoint() {
        WireGuardProfile profile = WireGuardConfigParser.parse(
                "# comment\n" +
                        "[Interface]\n" +
                        "PrivateKey = " + key(1) + " # inline comment\n" +
                        "Address = 10.7.0.2/32\n" +
                        "; another comment\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 149.154.160.0/20\n" +
                        "Endpoint = [2001:db8::1]:51820\n",
                "");

        assertEquals("[2001:db8::1]:51820", profile.name);
        assertEquals("[2001:db8::1]:51820", profile.peerEndpoint);
        assertArrayEquals(new String[]{"149.154.160.0/20"}, profile.allowedIps);
    }

    @Test
    public void parseRejectsMissingRequiredValues() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "Endpoint = wg.example.com:51820\n");

        assertTrue(error.getMessage().contains("ALLOWED_IPS"));
    }

    @Test
    public void parseRejectsInvalidBase64Key() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = not-a-key\n" +
                        "Address = 10.7.0.2/32\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = wg.example.com:51820\n");

        assertTrue(error.getMessage().contains("WireGuard keys") || error.getMessage().contains("base64"));
    }

    @Test
    public void parseRejectsInvalidEndpointPort() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = wg.example.com:70000\n");

        assertTrue(error.getMessage().contains("endpoint"));
    }

    @Test
    public void parseRejectsMultiplePeersForFirstUiStage() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = wg.example.com:51820\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(3) + "\n" +
                        "AllowedIPs = ::/0\n" +
                        "Endpoint = wg2.example.com:51820\n");

        assertTrue(error.getMessage().contains("Peer"));
    }

    private static IllegalArgumentException expectParseError(String config) {
        try {
            WireGuardConfigParser.parse(config, "bad.conf");
            throw new AssertionError("Expected parse failure");
        } catch (IllegalArgumentException e) {
            return e;
        }
    }

    private static String key(int value) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) value;
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
