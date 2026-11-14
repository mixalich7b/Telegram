package org.telegram.messenger;

import org.junit.Test;
import static org.junit.Assert.*;

public class IpVersionPolicyTest {
    @Test public void appliesWithoutActiveSessions() {
        IpVersionPolicy policy = new IpVersionPolicy(true);
        assertTrue(policy.request(false));
        assertFalse(policy.isEnabled());
        assertFalse(policy.request(false));
    }

    @Test public void waitsForEverySessionAndKeepsLatestRequest() {
        IpVersionPolicy policy = new IpVersionPolicy(true);
        policy.beginSession();
        policy.beginSession();
        assertFalse(policy.request(false));
        assertTrue(policy.isEnabled());
        assertFalse(policy.endSession());
        assertTrue(policy.isEnabled());
        assertTrue(policy.endSession());
        assertFalse(policy.isEnabled());
    }

    @Test public void togglingBackCancelsPendingChange() {
        IpVersionPolicy policy = new IpVersionPolicy(false);
        policy.beginSession();
        assertFalse(policy.request(true));
        assertFalse(policy.request(false));
        assertFalse(policy.endSession());
        assertFalse(policy.isEnabled());
    }
}
