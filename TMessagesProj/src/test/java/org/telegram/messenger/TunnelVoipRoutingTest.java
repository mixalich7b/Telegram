package org.telegram.messenger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TunnelVoipRoutingTest {

    private static final int UDP_RELAY = 1;
    private static final int TCP_RELAY = 2;

    @Test
    public void directRoutePreservesRequestedVoipPolicy() {
        assertFalse(TunnelVoipRouting.shouldUseTunnel(null));
        assertTrue(TunnelVoipRouting.shouldEnableP2p(true, null));
        assertFalse(TunnelVoipRouting.shouldEnableP2p(false, null));
        assertEquals(UDP_RELAY, TunnelVoipRouting.selectEndpointType(false, null, UDP_RELAY, TCP_RELAY));
        assertEquals(TCP_RELAY, TunnelVoipRouting.selectEndpointType(true, null, UDP_RELAY, TCP_RELAY));
    }

    @Test
    public void wireGuardTunnelPreservesP2pAndRelayType() {
        assertTunnelPreservesVoipPolicy(new TunnelProxySettings(TunnelProtocol.WIREGUARD, "", 0, "", "", false));
    }

    @Test
    public void amneziaWGTunnelPreservesP2pAndRelayType() {
        assertTunnelPreservesVoipPolicy(new TunnelProxySettings(TunnelProtocol.AMNEZIA_WG, "", 0, "", "", false));
    }

    @Test
    public void disabledTunnelVoipRoutingRemovesProxy() {
        TunnelProxySettings proxySettings = new TunnelProxySettings(TunnelProtocol.WIREGUARD, "", 0, "", "", false);

        assertEquals(proxySettings, TunnelVoipRouting.selectProxySettings(true, proxySettings));
        assertNull(TunnelVoipRouting.selectProxySettings(false, proxySettings));
    }

    @Test
    public void blockedTunnelStillUsesTunnelVoipPolicy() {
        TunnelProxySettings blocked = new TunnelProxySettings(TunnelProtocol.AMNEZIA_WG, "", 0, "", "", true);

        assertTunnelPreservesVoipPolicy(blocked);
        assertTrue(blocked.blocked);
    }

    private static void assertTunnelPreservesVoipPolicy(TunnelProxySettings proxySettings) {
        assertTrue(TunnelVoipRouting.shouldUseTunnel(proxySettings));
        assertTrue(TunnelVoipRouting.shouldEnableP2p(true, proxySettings));
        assertFalse(TunnelVoipRouting.shouldEnableP2p(false, proxySettings));
        assertEquals(UDP_RELAY, TunnelVoipRouting.selectEndpointType(false, proxySettings, UDP_RELAY, TCP_RELAY));
        assertEquals(TCP_RELAY, TunnelVoipRouting.selectEndpointType(true, proxySettings, UDP_RELAY, TCP_RELAY));
    }
}
