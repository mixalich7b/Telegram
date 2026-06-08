package org.telegram.messenger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WireGuardProfileTest {

    @Test
    public void endpointValidationAcceptsSupportedHosts() {
        assertTrue(WireGuardProfile.isValidEndpoint("wg.example.com:51820"));
        assertTrue(WireGuardProfile.isValidEndpoint("wg-1.example.co.uk:1"));
        assertTrue(WireGuardProfile.isValidEndpoint("wg.example.com.:65535"));
        assertTrue(WireGuardProfile.isValidEndpoint("localhost:51820"));
        assertTrue(WireGuardProfile.isValidEndpoint("192.0.2.10:51820"));
        assertTrue(WireGuardProfile.isValidEndpoint("[2001:db8::1]:51820"));
    }

    @Test
    public void endpointValidationRejectsInvalidHosts() {
        assertFalse(WireGuardProfile.isValidEndpoint("bad host:51820"));
        assertFalse(WireGuardProfile.isValidEndpoint("_wg.example.com:51820"));
        assertFalse(WireGuardProfile.isValidEndpoint("-wg.example.com:51820"));
        assertFalse(WireGuardProfile.isValidEndpoint("wg-.example.com:51820"));
        assertFalse(WireGuardProfile.isValidEndpoint("wg..example.com:51820"));
        assertFalse(WireGuardProfile.isValidEndpoint("999.999.999.999:51820"));
        assertFalse(WireGuardProfile.isValidEndpoint("[wg.example.com]:51820"));
        assertFalse(WireGuardProfile.isValidEndpoint("[2001:db8:::1]:51820"));
    }

    @Test
    public void endpointValidationRejectsInvalidPortsAndForms() {
        assertFalse(WireGuardProfile.isValidEndpoint("wg.example.com"));
        assertFalse(WireGuardProfile.isValidEndpoint("wg.example.com:0"));
        assertFalse(WireGuardProfile.isValidEndpoint("wg.example.com:70000"));
        assertFalse(WireGuardProfile.isValidEndpoint("wg.example.com:abc"));
        assertFalse(WireGuardProfile.isValidEndpoint("2001:db8::1:51820"));
    }
}
