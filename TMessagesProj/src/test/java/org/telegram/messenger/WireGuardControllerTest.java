package org.telegram.messenger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WireGuardControllerTest {

    @Test
    public void disabledConfigDoesNotStartOrApplyTunnelRoute() {
        FakeConfig config = new FakeConfig();
        config.enabled = false;
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();

        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        assertFalse(controller.applyTunnelSettingsForAccount(0));
        assertEquals(0, nativeRuntime.startCalls);
        assertEquals(0, tunnelStateSink.calls.size());
        assertNull(controller.getFailureReason());
    }

    @Test
    public void successfulStartAppliesActiveTunnelRoute() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();

        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        assertTrue(controller.applyTunnelSettingsForAccount(2));

        assertEquals(1, nativeRuntime.startCalls);
        assertTrue(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertEquals(1, tunnelStateSink.calls.size());
        TunnelStateCall call = tunnelStateSink.calls.get(0);
        assertEquals(2, call.account);
        assertTrue(call.enabled);
        assertFalse(call.blocked);
    }

    @Test
    public void nativeFailureAppliesBlockedTunnelRoute() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        nativeRuntime.failure = new RuntimeException("boom");
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();

        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals(1, nativeRuntime.startCalls);
        assertFalse(controller.isRunningForTests());
        assertEquals("unable to start WireGuard runtime", controller.getFailureReason());
        assertEquals(1, tunnelStateSink.calls.size());
        TunnelStateCall call = tunnelStateSink.calls.get(0);
        assertTrue(call.enabled);
        assertTrue(call.blocked);
    }

    @Test
    public void nativeErrorStatusAppliesBlockedTunnelRoute() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        nativeRuntime.status = -7;
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();

        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals("nativeStart returned error -7", controller.getFailureReason());
        assertTrue(tunnelStateSink.calls.get(0).enabled);
        assertTrue(tunnelStateSink.calls.get(0).blocked);
    }

    @Test
    public void validationFailureDoesNotStartNativeRuntime() {
        FakeConfig config = new FakeConfig();
        config.validationError = "missing WireGuard config values: [PRIVATE_KEY]";
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();

        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals(0, nativeRuntime.startCalls);
        assertEquals(config.validationError, controller.getFailureReason());
        assertTrue(tunnelStateSink.calls.get(0).enabled);
        assertTrue(tunnelStateSink.calls.get(0).blocked);
    }

    @Test
    public void startIsAttemptedOnlyOnce() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();

        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        assertTrue(controller.applyTunnelSettingsForAccount(1));
        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals(1, nativeRuntime.startCalls);
        assertEquals(2, tunnelStateSink.calls.size());
        assertFalse(tunnelStateSink.calls.get(0).blocked);
        assertFalse(tunnelStateSink.calls.get(1).blocked);
    }

    @Test
    public void applyTunnelSettingsForAllAccountsUsesActiveTunnelRoute() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();
        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        assertTrue(controller.applyTunnelSettingsForAllAccounts(3));

        assertEquals(1, nativeRuntime.startCalls);
        assertEquals(3, tunnelStateSink.calls.size());
        for (int account = 0; account < 3; account++) {
            TunnelStateCall call = tunnelStateSink.calls.get(account);
            assertEquals(account, call.account);
            assertTrue(call.enabled);
            assertFalse(call.blocked);
        }
    }

    @Test
    public void getProxySettingsReturnsBlockedEndpointWhenStartupFails() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        nativeRuntime.status = -1;
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();
        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        TunnelProxySettings proxySettings = controller.getProxySettings();

        assertTrue(proxySettings.blocked);
        assertEquals("", proxySettings.host);
        assertEquals(0, proxySettings.port);
        assertEquals("", proxySettings.username);
        assertEquals("", proxySettings.password);
    }

    @Test
    public void getProxySettingsPreservesAmneziaWGProtocol() {
        FakeConfig config = new FakeConfig();
        config.protocol = TunnelProtocol.AMNEZIA_WG;
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();
        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        TunnelProxySettings proxySettings = controller.getProxySettings();

        assertEquals(TunnelProtocol.AMNEZIA_WG, proxySettings.protocol);
        assertFalse(proxySettings.blocked);
        assertEquals(0, proxySettings.port);
    }

    @Test
    public void networkChangedIsForwardedOnlyWhenRunning() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();
        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        controller.onNetworkChanged(2);
        assertEquals(0, nativeRuntime.networkChangedCalls);

        controller.applyTunnelSettingsForAccount(1);
        controller.onNetworkChanged(2);
        assertEquals(1, nativeRuntime.networkChangedCalls);
    }

    @Test
    public void networkChangedFailureRestartsRuntimeAndReappliesTunnelRoute() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();
        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        controller.applyTunnelSettingsForAccount(1);
        nativeRuntime.networkChangedStatus = -9;

        controller.onNetworkChanged(2);

        assertEquals(1, nativeRuntime.networkChangedCalls);
        assertEquals(1, nativeRuntime.stopCalls);
        assertEquals(2, nativeRuntime.startCalls);
        assertTrue(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertEquals(3, tunnelStateSink.calls.size());
        assertFalse(tunnelStateSink.calls.get(1).blocked);
        assertFalse(tunnelStateSink.calls.get(2).blocked);
        assertEquals(0, tunnelStateSink.calls.get(1).account);
        assertEquals(1, tunnelStateSink.calls.get(2).account);
    }

    @Test
    public void networkChangedRestartFailureAppliesBlockedTunnelRoute() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();
        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        controller.applyTunnelSettingsForAccount(1);
        nativeRuntime.networkChangedStatus = -1;
        nativeRuntime.status = -5;

        controller.onNetworkChanged(2);

        assertEquals(1, nativeRuntime.networkChangedCalls);
        assertEquals(1, nativeRuntime.stopCalls);
        assertEquals(2, nativeRuntime.startCalls);
        assertFalse(controller.isRunningForTests());
        assertEquals("nativeStart returned error -5", controller.getFailureReason());
        assertTrue(tunnelStateSink.calls.get(1).blocked);
        assertTrue(tunnelStateSink.calls.get(2).blocked);
    }

    @Test
    public void restartAppliesBlockedTunnelRouteBeforeStartingNewRuntime() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();
        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        controller.applyTunnelSettingsForAccount(1);
        controller.restart(2);

        assertEquals(2, nativeRuntime.startCalls);
        assertEquals(1, nativeRuntime.stopCalls);
        assertEquals(5, tunnelStateSink.calls.size());
        assertFalse(tunnelStateSink.calls.get(0).blocked);
        assertTrue(tunnelStateSink.calls.get(1).blocked);
        assertTrue(tunnelStateSink.calls.get(2).blocked);
        assertFalse(tunnelStateSink.calls.get(3).blocked);
        assertFalse(tunnelStateSink.calls.get(4).blocked);
    }

    @Test
    public void disableStopsRuntimeAndClearsTunnelRoute() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeTunnelStateSink tunnelStateSink = new FakeTunnelStateSink();
        WireGuardController controller = newController(config, nativeRuntime, tunnelStateSink);

        controller.applyTunnelSettingsForAccount(1);
        controller.disable(2);

        assertEquals(1, nativeRuntime.startCalls);
        assertEquals(1, nativeRuntime.stopCalls);
        assertFalse(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertEquals(3, tunnelStateSink.calls.size());
        assertFalse(tunnelStateSink.calls.get(1).enabled);
        assertFalse(tunnelStateSink.calls.get(1).blocked);
        assertFalse(tunnelStateSink.calls.get(2).enabled);
        assertFalse(tunnelStateSink.calls.get(2).blocked);
    }

    private static WireGuardController newController(FakeConfig config, FakeNativeRuntime nativeRuntime, FakeTunnelStateSink tunnelStateSink) {
        return new WireGuardController(
                config,
                nativeRuntime,
                tunnelStateSink,
                new WireGuardController.Logger() {
                    @Override
                    public void debug(String message) {
                    }

                    @Override
                    public void error(String message) {
                    }

                    @Override
                    public void error(Throwable throwable) {
                    }
                });
    }

    private static final class FakeConfig implements WireGuardController.Config {
        boolean enabled = true;
        String validationError;
        TunnelProtocol protocol = TunnelProtocol.WIREGUARD;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public TunnelProtocol protocol() {
            return protocol;
        }

        @Override
        public String protocolLabel() {
            return protocol.displayName();
        }

        @Override
        public String validate() {
            return validationError;
        }

        @Override
        public String buildUserspaceConfig() {
            return "private_key=00\n";
        }

        @Override
        public String[] localAddresses() {
            return new String[]{"10.0.0.2"};
        }

        @Override
        public String[] dnsServers() {
            return new String[]{"1.1.1.1"};
        }

        @Override
        public int mtu() {
            return 1420;
        }

    }

    private static final class FakeNativeRuntime implements WireGuardController.NativeRuntime {
        int startCalls;
        int networkChangedCalls;
        int stopCalls;
        int status = 0;
        int networkChangedStatus = 0;
        RuntimeException failure;

        @Override
        public int start(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu) {
            startCalls++;
            if (failure != null) {
                throw failure;
            }
            return status;
        }

        @Override
        public int onNetworkChanged() {
            networkChangedCalls++;
            return networkChangedStatus;
        }

        @Override
        public void stop() {
            stopCalls++;
        }
    }

    private static final class FakeTunnelStateSink implements WireGuardController.TunnelStateSink {
        final List<TunnelStateCall> calls = new ArrayList<>();

        @Override
        public void apply(int account, boolean enabled, boolean blocked) {
            calls.add(new TunnelStateCall(account, enabled, blocked));
        }
    }

    private static final class TunnelStateCall {
        final int account;
        final boolean enabled;
        final boolean blocked;

        TunnelStateCall(int account, boolean enabled, boolean blocked) {
            this.account = account;
            this.enabled = enabled;
            this.blocked = blocked;
        }
    }
}
