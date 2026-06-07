package org.telegram.messenger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WireGuardVoipRoutingTest {

    @Test
    public void p2pIsDisabledWhenWireGuardProxyIsPresent() {
        WireGuardProxySettings proxySettings = new WireGuardProxySettings("127.0.0.1", 12345, "user", "pass", false);

        assertFalse(WireGuardVoipRouting.shouldEnableP2p(true, proxySettings));
        assertFalse(WireGuardVoipRouting.shouldEnableP2p(false, proxySettings));
    }

    @Test
    public void p2pFollowsRequestedValueWithoutWireGuard() {
        assertTrue(WireGuardVoipRouting.shouldEnableP2p(true, null));
        assertFalse(WireGuardVoipRouting.shouldEnableP2p(false, null));
    }

    @Test
    public void endpointTypeIsForcedToTcpRelayWhenWireGuardProxyIsPresent() {
        WireGuardProxySettings proxySettings = new WireGuardProxySettings("127.0.0.1", 12345, "user", "pass", false);

        assertEquals(3, WireGuardVoipRouting.selectEndpointType(false, proxySettings, 2, 3));
    }

    @Test
    public void endpointTypeFollowsDebugTcpPreferenceWithoutWireGuard() {
        assertEquals(2, WireGuardVoipRouting.selectEndpointType(false, null, 2, 3));
        assertEquals(3, WireGuardVoipRouting.selectEndpointType(true, null, 2, 3));
    }

    @Test
    public void blockedProxyStillRoutesThroughWireGuardPolicy() {
        WireGuardProxySettings proxySettings = new WireGuardProxySettings("127.0.0.1", 1, "", "", true);

        assertTrue(WireGuardVoipRouting.shouldUseWireGuard(proxySettings));
        assertFalse(WireGuardVoipRouting.shouldEnableP2p(true, proxySettings));
        assertEquals(3, WireGuardVoipRouting.selectEndpointType(false, proxySettings, 2, 3));
    }
}
