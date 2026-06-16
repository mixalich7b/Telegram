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
        assertEquals(TunnelProtocol.WIREGUARD, profile.protocol);
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
    public void parseDetectsAmneziaWGConfig() {
        WireGuardProfile profile = WireGuardConfigParser.parse(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "Jc = 4\n" +
                        "Jmin = 50\n" +
                        "Jmax = 100\n" +
                        "S1 = 87\n" +
                        "S2 = 65\n" +
                        "S3 = 43\n" +
                        "S4 = 21\n" +
                        "H1 = 1000000000-1000000001\n" +
                        "H2 = 2000000000-2000000002\n" +
                        "H3 = 3000000000-3000000003\n" +
                        "H4 = 4000000000-4000000004\n" +
                        "I1 = <b f6ab><d ignored><ds ignored><dz 2><t ignored>\n" +
                        "I2 = <r 8>\n" +
                        "I3 = <rd 4>\n" +
                        "I4 = <rc 5>\n" +
                        "I5 = <b 0x0a0b>\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = awg.example.com:51820\n",
                "awg.conf");

        assertEquals(TunnelProtocol.AMNEZIA_WG, profile.protocol);
        assertEquals(4, profile.amneziaJc);
        assertEquals(50, profile.amneziaJmin);
        assertEquals(100, profile.amneziaJmax);
        assertEquals(87, profile.amneziaS1);
        assertEquals(65, profile.amneziaS2);
        assertEquals("1000000000-1000000001", profile.amneziaH1);
        assertEquals("4000000000-4000000004", profile.amneziaH4);
        assertEquals("<b f6ab><d ignored><ds ignored><dz 2><t ignored>", profile.amneziaI1);
        assertNull(profile.validate());
    }

    @Test
    public void parseForcedAmneziaWGAllowsStandardWireGuardConfig() {
        WireGuardProfile profile = WireGuardConfigParser.parse(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = awg.example.com:51820\n",
                "manual-awg.conf",
                TunnelProtocol.AMNEZIA_WG);

        assertEquals("manual-awg", profile.name);
        assertEquals(TunnelProtocol.AMNEZIA_WG, profile.protocol);
        assertNull(profile.validate());
    }

    @Test
    public void parseForcedWireGuardRejectsAmneziaWGKeys() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "S1 = 8\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = wg.example.com:51820\n",
                TunnelProtocol.WIREGUARD);

        assertTrue(error.getMessage().contains("AmneziaWG keys"));
    }

    @Test
    public void parseRejectsInvalidAmneziaWGJunkGroup() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "Jc = 4\n" +
                        "Jmin = 512\n" +
                        "Jmax = 64\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = awg.example.com:51820\n");

        assertTrue(error.getMessage().contains("Jc"));
    }

    @Test
    public void parseRejectsOverlappingAmneziaWGHeaders() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "H1 = 100-200\n" +
                        "H2 = 200-300\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = awg.example.com:51820\n");

        assertTrue(error.getMessage().contains("overlapping"));
    }

    @Test
    public void parseRejectsInvalidAmneziaWGCps() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "I1 = <b 0x0>\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = awg.example.com:51820\n");

        assertTrue(error.getMessage().contains("signature"));
    }

    @Test
    public void parseRejectsAmneziaWGKeysInPeerSection() {
        IllegalArgumentException error = expectParseError(
                "[Interface]\n" +
                        "PrivateKey = " + key(1) + "\n" +
                        "Address = 10.7.0.2/32\n" +
                        "[Peer]\n" +
                        "PublicKey = " + key(2) + "\n" +
                        "AllowedIPs = 0.0.0.0/0\n" +
                        "Endpoint = awg.example.com:51820\n" +
                        "Jc = 4\n");

        assertTrue(error.getMessage().contains("Interface section"));
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
        return expectParseError(config, null);
    }

    private static IllegalArgumentException expectParseError(String config, TunnelProtocol forcedProtocol) {
        try {
            WireGuardConfigParser.parse(config, "bad.conf", forcedProtocol);
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
