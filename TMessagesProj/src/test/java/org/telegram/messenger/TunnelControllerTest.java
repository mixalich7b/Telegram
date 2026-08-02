package org.telegram.messenger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TunnelControllerTest {

    @Test
    public void disabledConfigDoesNotStartOrApplyTunnelRoute() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        config.enabled = false;
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();

        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        assertFalse(controller.applyTunnelSettingsForAccount(0));
        assertEquals(0, tunnelRuntime.startCalls);
        assertEquals(0, routeStateApplier.calls.size());
        assertNull(controller.getFailureReason());
    }

    @Test
    public void successfulStartAppliesActiveTunnelRoute() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();

        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        assertTrue(controller.applyTunnelSettingsForAccount(2));

        assertEquals(1, tunnelRuntime.startCalls);
        assertTrue(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertEquals(4, routeStateApplier.calls.size());
        assertTrue(routeStateApplier.calls.get(0).blocked);
        TunnelStateCall call = lastCallForAccount(routeStateApplier, 2);
        assertEquals(2, call.account);
        assertTrue(call.enabled);
        assertFalse(call.blocked);
    }

    @Test
    public void nativeFailureAppliesBlockedTunnelRoute() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        tunnelRuntime.failure = new RuntimeException("boom");
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();

        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals(1, tunnelRuntime.startCalls);
        assertFalse(controller.isRunningForTests());
        assertEquals("unable to start WireGuard runtime", controller.getFailureReason());
        TunnelStateCall call = lastCallForAccount(routeStateApplier, 1);
        assertTrue(call.enabled);
        assertTrue(call.blocked);
    }

    @Test
    public void nativeErrorStatusAppliesBlockedTunnelRoute() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        tunnelRuntime.status = -7;
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();

        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals("nativeStart returned error -7", controller.getFailureReason());
        assertTrue(routeStateApplier.calls.get(0).enabled);
        assertTrue(routeStateApplier.calls.get(0).blocked);
    }

    @Test
    public void startupFailureSchedulesReconnectAndUnblocksAfterRetrySucceeds() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        tunnelRuntime.status = -1;
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier, lifecycleTaskScheduler);

        assertTrue(controller.applyTunnelSettingsForAllAccounts(2));

        assertEquals(1, tunnelRuntime.startCalls);
        assertFalse(controller.isRunningForTests());
        assertEquals(1, lifecycleTaskScheduler.delays.size());
        assertEquals(1000L, (long) lifecycleTaskScheduler.delays.get(0));
        assertTrue(routeStateApplier.calls.get(0).blocked);
        assertTrue(routeStateApplier.calls.get(1).blocked);

        tunnelRuntime.status = 0;
        lifecycleTaskScheduler.runNext();

        assertEquals(2, tunnelRuntime.startCalls);
        assertTrue(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
        assertFalse(lastCallForAccount(routeStateApplier, 1).blocked);
    }

    @Test
    public void retryBackoffRepeatsAfterExhaustion() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        tunnelRuntime.status = -1;
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier, lifecycleTaskScheduler);

        controller.applyTunnelSettingsForAllAccounts(1);
        long[] expectedDelays = new long[]{
                1000L,
                2000L,
                2000L,
                3000L,
                3000L,
                5000L,
                5000L
        };

        for (int i = 0; i < expectedDelays.length; i++) {
            assertEquals(expectedDelays[i], (long) lifecycleTaskScheduler.delays.get(i));
            if (i + 1 < expectedDelays.length) {
                lifecycleTaskScheduler.runNext();
            }
        }
    }

    @Test
    public void lifecycleStartIsDeferredAndRouteRemainsBlockedUntilCompletion() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        lifecycleTaskScheduler.executeImmediately = false;
        TunnelController controller = newController(
                config,
                tunnelRuntime,
                routeStateApplier,
                lifecycleTaskScheduler
        );

        assertTrue(controller.applyTunnelSettingsForAllAccounts(2));

        assertEquals(0, tunnelRuntime.startCalls);
        assertTrue(lastCallForAccount(routeStateApplier, 0).blocked);
        assertTrue(lastCallForAccount(routeStateApplier, 1).blocked);

        lifecycleTaskScheduler.runNextLifecycleTask();

        assertEquals(1, tunnelRuntime.startCalls);
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
        assertFalse(lastCallForAccount(routeStateApplier, 1).blocked);
    }

    @Test
    public void controllerStateLockRemainsResponsiveWhileNativeStartBlocks() throws Exception {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        BlockingTunnelRuntime tunnelRuntime = new BlockingTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        lifecycleTaskScheduler.executeAsynchronously = true;
        TunnelController controller = new TunnelController(
                config,
                tunnelRuntime,
                routeStateApplier,
                emptyLogger(),
                lifecycleTaskScheduler
        );

        controller.applyTunnelSettingsForAllAccounts(1);
        assertTrue(tunnelRuntime.startEntered.await(2, TimeUnit.SECONDS));

        CountDownLatch stateReadCompleted = new CountDownLatch(1);
        Thread stateReader = new Thread(() -> {
            controller.getFailureReason();
            stateReadCompleted.countDown();
        });
        stateReader.start();

        try {
            assertTrue(stateReadCompleted.await(500, TimeUnit.MILLISECONDS));
            assertTrue(lastCallForAccount(routeStateApplier, 0).blocked);
        } finally {
            tunnelRuntime.allowStartToFinish.countDown();
            stateReader.join(2000);
            lifecycleTaskScheduler.joinLifecycleThreads();
        }
    }

    @Test
    public void runtimeStartSuccessDoesNotResetReconnectBackoffButTcpSuccessDoes() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(
                config,
                tunnelRuntime,
                routeStateApplier,
                lifecycleTaskScheduler
        );

        controller.applyTunnelSettingsForAllAccounts(1);
        long lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);
        lifecycleTaskScheduler.runNext();
        assertFalse(controller.isReconnecting());
        lifecycleTaskScheduler.runNext();
        assertTrue(controller.isReconnecting());

        lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);
        lifecycleTaskScheduler.runNext();
        assertFalse(controller.isReconnecting());
        lifecycleTaskScheduler.runNext();
        assertTrue(controller.isReconnecting());

        lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnected(0, lifecycleGeneration);
        assertFalse(controller.isReconnecting());
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);
        lifecycleTaskScheduler.runNext();

        assertEquals(6, lifecycleTaskScheduler.delays.size());
        assertEquals(250L, (long) lifecycleTaskScheduler.delays.get(0));
        assertEquals(1000L, (long) lifecycleTaskScheduler.delays.get(1));
        assertEquals(250L, (long) lifecycleTaskScheduler.delays.get(2));
        assertEquals(2000L, (long) lifecycleTaskScheduler.delays.get(3));
        assertEquals(250L, (long) lifecycleTaskScheduler.delays.get(4));
        assertEquals(1000L, (long) lifecycleTaskScheduler.delays.get(5));
    }

    @Test
    public void duplicateTcpFailuresScheduleSingleReconnect() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(
                config,
                tunnelRuntime,
                routeStateApplier,
                lifecycleTaskScheduler
        );

        controller.applyTunnelSettingsForAllAccounts(1);
        long lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);

        assertEquals(1, lifecycleTaskScheduler.delays.size());
        assertEquals(250L, (long) lifecycleTaskScheduler.delays.get(0));
        assertEquals(0, tunnelRuntime.stopCalls);
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);

        lifecycleTaskScheduler.runNext();

        assertEquals(1, tunnelRuntime.stopCalls);
        assertEquals(2, lifecycleTaskScheduler.delays.size());
        assertEquals(1000L, (long) lifecycleTaskScheduler.delays.get(1));
        assertTrue(lastCallForAccount(routeStateApplier, 0).blocked);
    }

    @Test
    public void ipv4OnlyProfileKeepsParallelIpv4AttemptAfterIpv6NoRouteFailure() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        config.protocol = TunnelProtocol.AMNEZIA_WG;
        config.localAddresses = new String[]{"10.8.0.2/32"};
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(
                config,
                tunnelRuntime,
                routeStateApplier,
                lifecycleTaskScheduler
        );

        controller.applyTunnelSettingsForAllAccounts(1);
        long lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;

        // tgnet starts an unsupported IPv6 destination and a usable IPv4 destination in parallel.
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);

        assertTrue(controller.isRunningForTests());
        assertEquals(0, tunnelRuntime.stopCalls);
        assertEquals(0, lifecycleTaskScheduler.delays.size());
        assertTrue(lastCallForAccount(routeStateApplier, 0).enabled);
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);

        controller.onTunnelTcpConnected(0, lifecycleGeneration);

        assertTrue(controller.isRunningForTests());
        assertEquals(0, tunnelRuntime.stopCalls);
        assertEquals(0, lifecycleTaskScheduler.delays.size());
        assertNull(controller.getFailureReason());
        assertTrue(lastCallForAccount(routeStateApplier, 0).enabled);
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
    }

    @Test
    public void sequentialIpv4AlternateAttemptCancelsSettlingIpv6Failure() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        config.protocol = TunnelProtocol.AMNEZIA_WG;
        config.localAddresses = new String[]{"10.8.0.2/32"};
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier, lifecycleTaskScheduler);

        controller.applyTunnelSettingsForAllAccounts(1);
        long lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);
        assertEquals(1, lifecycleTaskScheduler.delays.size());

        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnected(0, lifecycleGeneration);
        lifecycleTaskScheduler.runNext();

        assertTrue(controller.isRunningForTests());
        assertEquals(0, tunnelRuntime.stopCalls);
        assertTrue(lifecycleTaskScheduler.cancelCalls >= 1);
        assertNull(controller.getFailureReason());
        assertTrue(lastCallForAccount(routeStateApplier, 0).enabled);
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
    }

    @Test
    public void staleTcpFailureFromPreviousLifecycleGenerationIsIgnored() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(
                config,
                tunnelRuntime,
                routeStateApplier,
                lifecycleTaskScheduler
        );

        controller.applyTunnelSettingsForAllAccounts(1);
        long staleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.restart(1);

        controller.onTunnelTcpConnectFailed(0, staleGeneration, 1);

        assertTrue(controller.isRunningForTests());
        assertEquals(0, lifecycleTaskScheduler.delays.size());
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
    }

    @Test
    public void queuedStartBecomesStaleAfterDisable() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        lifecycleTaskScheduler.executeImmediately = false;
        TunnelController controller = newController(
                config,
                tunnelRuntime,
                routeStateApplier,
                lifecycleTaskScheduler
        );

        controller.applyTunnelSettingsForAllAccounts(1);
        config.enabled = false;
        controller.disable(1);

        lifecycleTaskScheduler.runAllLifecycleTasks();

        assertEquals(0, tunnelRuntime.startCalls);
        assertFalse(lastCallForAccount(routeStateApplier, 0).enabled);
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
    }

    @Test
    public void validationFailureDoesNotStartNativeRuntime() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        config.validationError = "missing WireGuard config values: [PRIVATE_KEY]";
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();

        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals(0, tunnelRuntime.startCalls);
        assertEquals(config.validationError, controller.getFailureReason());
        assertTrue(routeStateApplier.calls.get(0).enabled);
        assertTrue(routeStateApplier.calls.get(0).blocked);
    }

    @Test
    public void validationFailureDoesNotScheduleReconnect() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        config.validationError = "missing WireGuard config values: [PRIVATE_KEY]";
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();

        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier, lifecycleTaskScheduler);

        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals(0, tunnelRuntime.startCalls);
        assertEquals(0, lifecycleTaskScheduler.delays.size());
    }

    @Test
    public void startIsAttemptedOnlyOnce() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();

        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        assertTrue(controller.applyTunnelSettingsForAccount(1));
        assertTrue(controller.applyTunnelSettingsForAccount(1));

        assertEquals(1, tunnelRuntime.startCalls);
        assertFalse(lastCallForAccount(routeStateApplier, 1).blocked);
    }

    @Test
    public void applyTunnelSettingsForAllAccountsUsesActiveTunnelRoute() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        assertTrue(controller.applyTunnelSettingsForAllAccounts(3));

        assertEquals(1, tunnelRuntime.startCalls);
        assertEquals(6, routeStateApplier.calls.size());
        for (int account = 0; account < 3; account++) {
            TunnelStateCall call = lastCallForAccount(routeStateApplier, account);
            assertEquals(account, call.account);
            assertTrue(call.enabled);
            assertFalse(call.blocked);
        }
    }

    @Test
    public void getProxySettingsReturnsBlockedEndpointWhenStartupFails() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        tunnelRuntime.status = -1;
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        TunnelProxySettings proxySettings = controller.getProxySettings();

        assertTrue(proxySettings.blocked);
        assertEquals("", proxySettings.host);
        assertEquals(0, proxySettings.port);
        assertEquals("", proxySettings.username);
        assertEquals("", proxySettings.password);
    }

    @Test
    public void getProxySettingsPreservesAmneziaWGProtocol() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        config.protocol = TunnelProtocol.AMNEZIA_WG;
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        TunnelProxySettings proxySettings = controller.getProxySettings();

        assertEquals(TunnelProtocol.AMNEZIA_WG, proxySettings.protocol);
        assertTrue(proxySettings.blocked);
        assertFalse(controller.getProxySettings().blocked);
        assertEquals(0, proxySettings.port);
    }

    @Test
    public void networkChangedIsForwardedOnlyWhenRunning() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        controller.onNetworkChanged(2);
        assertEquals(0, tunnelRuntime.networkChangedCalls);

        controller.applyTunnelSettingsForAccount(1);
        controller.onNetworkChanged(2);
        assertEquals(1, tunnelRuntime.networkChangedCalls);
    }

    @Test
    public void networkChangedFailureRestartsRuntimeAndReappliesTunnelRoute() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        controller.applyTunnelSettingsForAccount(1);
        tunnelRuntime.networkChangedStatus = -9;

        controller.onNetworkChanged(2);

        assertEquals(1, tunnelRuntime.networkChangedCalls);
        assertEquals(1, tunnelRuntime.stopCalls);
        assertEquals(2, tunnelRuntime.startCalls);
        assertTrue(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
        assertFalse(lastCallForAccount(routeStateApplier, 1).blocked);
    }

    @Test
    public void networkChangedRestartFailureAppliesBlockedTunnelRoute() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        controller.applyTunnelSettingsForAccount(1);
        tunnelRuntime.networkChangedStatus = -1;
        tunnelRuntime.status = -5;

        controller.onNetworkChanged(2);

        assertEquals(1, tunnelRuntime.networkChangedCalls);
        assertEquals(2, tunnelRuntime.stopCalls);
        assertEquals(2, tunnelRuntime.startCalls);
        assertFalse(controller.isRunningForTests());
        assertEquals("nativeStart returned error -5", controller.getFailureReason());
        assertTrue(lastCallForAccount(routeStateApplier, 0).blocked);
        assertTrue(lastCallForAccount(routeStateApplier, 1).blocked);
    }

    @Test
    public void tunnelConnectionFailureStopsRuntimeBlocksRouteAndSchedulesReconnect() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier, lifecycleTaskScheduler);

        controller.applyTunnelSettingsForAccount(1, 2);
        long lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(
                0,
                lifecycleGeneration,
                2
        );

        assertEquals(1, tunnelRuntime.startCalls);
        assertEquals(0, tunnelRuntime.stopCalls);
        assertTrue(controller.isRunningForTests());
        assertEquals(1, lifecycleTaskScheduler.delays.size());
        assertEquals(250L, (long) lifecycleTaskScheduler.delays.get(0));
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);

        lifecycleTaskScheduler.runNext();

        assertEquals(1, tunnelRuntime.stopCalls);
        assertFalse(controller.isRunningForTests());
        assertEquals("WireGuard TCP connections failed", controller.getFailureReason());
        assertFalse(controller.isReconnecting());
        assertEquals(2, lifecycleTaskScheduler.delays.size());
        assertEquals(1000L, (long) lifecycleTaskScheduler.delays.get(1));
        assertTrue(lastCallForAccount(routeStateApplier, 0).blocked);
        assertTrue(lastCallForAccount(routeStateApplier, 1).blocked);

        lifecycleTaskScheduler.runNext();

        assertEquals(2, tunnelRuntime.startCalls);
        assertTrue(controller.isRunningForTests());
        assertTrue(controller.isReconnecting());
        assertNull(controller.getFailureReason());
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
        assertFalse(lastCallForAccount(routeStateApplier, 1).blocked);
    }

    @Test
    public void disableCancelsScheduledReconnect() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        tunnelRuntime.status = -1;
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier, lifecycleTaskScheduler);

        controller.applyTunnelSettingsForAllAccounts(1);
        controller.disable(1);
        lifecycleTaskScheduler.runNext();

        assertEquals(1, tunnelRuntime.startCalls);
        assertEquals(1, lifecycleTaskScheduler.cancelCalls);
        assertFalse(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertFalse(routeStateApplier.calls.get(routeStateApplier.calls.size() - 1).enabled);
    }

    @Test
    public void restartAppliesBlockedTunnelRouteBeforeStartingNewRuntime() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        controller.applyTunnelSettingsForAccount(1);
        controller.restart(2);

        assertEquals(2, tunnelRuntime.startCalls);
        assertEquals(1, tunnelRuntime.stopCalls);
        assertEquals(7, routeStateApplier.calls.size());
        assertTrue(routeStateApplier.calls.get(3).blocked);
        assertTrue(routeStateApplier.calls.get(4).blocked);
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
        assertFalse(lastCallForAccount(routeStateApplier, 1).blocked);
    }

    @Test
    public void disableStopsRuntimeAndClearsTunnelRoute() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        controller.applyTunnelSettingsForAccount(1);
        controller.disable(2);

        assertEquals(1, tunnelRuntime.startCalls);
        assertEquals(1, tunnelRuntime.stopCalls);
        assertFalse(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertEquals(5, routeStateApplier.calls.size());
        assertFalse(lastCallForAccount(routeStateApplier, 0).enabled);
        assertFalse(lastCallForAccount(routeStateApplier, 0).blocked);
        assertFalse(lastCallForAccount(routeStateApplier, 1).enabled);
        assertFalse(lastCallForAccount(routeStateApplier, 1).blocked);
    }

    private static TunnelController newController(FakeTunnelConfigProvider config, FakeTunnelRuntime tunnelRuntime, FakeTunnelRouteStateApplier routeStateApplier) {
        return newController(config, tunnelRuntime, routeStateApplier, new FakeLifecycleTaskScheduler());
    }

    private static TunnelStateCall lastCallForAccount(FakeTunnelRouteStateApplier routeStateApplier, int account) {
        for (int index = routeStateApplier.calls.size() - 1; index >= 0; index--) {
            TunnelStateCall call = routeStateApplier.calls.get(index);
            if (call.account == account) {
                return call;
            }
        }
        throw new AssertionError("No route state applied for account " + account);
    }

    private static TunnelController newController(FakeTunnelConfigProvider config, FakeTunnelRuntime tunnelRuntime, FakeTunnelRouteStateApplier routeStateApplier, FakeLifecycleTaskScheduler lifecycleTaskScheduler) {
        return new TunnelController(
                config,
                tunnelRuntime,
                routeStateApplier,
                emptyLogger(),
                lifecycleTaskScheduler);
    }

    private static TunnelController.Logger emptyLogger() {
        return new TunnelController.Logger() {
            @Override
            public void debug(String message) {
            }

            @Override
            public void error(String message) {
            }

            @Override
            public void error(Throwable throwable) {
            }
        };
    }

    private static final class FakeTunnelConfigProvider implements TunnelController.TunnelConfigProvider {
        boolean enabled = true;
        String validationError;
        TunnelProtocol protocol = TunnelProtocol.WIREGUARD;
        String[] localAddresses = new String[]{"10.0.0.2"};

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
            return localAddresses;
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

    private static final class FakeTunnelRuntime implements TunnelController.TunnelRuntime {
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

    private static final class BlockingTunnelRuntime implements TunnelController.TunnelRuntime {
        final CountDownLatch startEntered = new CountDownLatch(1);
        final CountDownLatch allowStartToFinish = new CountDownLatch(1);

        @Override
        public int start(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu) {
            startEntered.countDown();
            try {
                if (!allowStartToFinish.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to finish fake tunnel start");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return 0;
        }

        @Override
        public int onNetworkChanged() {
            return 0;
        }

        @Override
        public void stop() {
        }
    }

    private static final class FakeTunnelRouteStateApplier implements TunnelController.TunnelRouteStateApplier {
        final List<TunnelStateCall> calls = new ArrayList<>();

        @Override
        public void applyTunnelRouteState(
                int account,
                TunnelController.TunnelRouteState state,
                long lifecycleGeneration
        ) {
            calls.add(new TunnelStateCall(
                    account,
                    state != TunnelController.TunnelRouteState.DIRECT,
                    state == TunnelController.TunnelRouteState.BLOCKED,
                    lifecycleGeneration
            ));
        }
    }

    private static final class FakeLifecycleTaskScheduler implements TunnelController.LifecycleTaskScheduler {
        final List<Runnable> lifecycleTasks = new ArrayList<>();
        final List<Thread> lifecycleThreads = new ArrayList<>();
        final List<Runnable> runnables = new ArrayList<>();
        final List<Runnable> canceled = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        boolean executeImmediately = true;
        boolean executeAsynchronously;
        int cancelCalls;

        @Override
        public void execute(Runnable runnable) {
            if (executeAsynchronously) {
                Thread thread = new Thread(runnable);
                lifecycleThreads.add(thread);
                thread.start();
            } else if (executeImmediately) {
                runnable.run();
            } else {
                lifecycleTasks.add(runnable);
            }
        }

        @Override
        public void schedule(Runnable runnable, long delayMs) {
            runnables.add(runnable);
            delays.add(delayMs);
        }

        @Override
        public void cancel(Runnable runnable) {
            cancelCalls++;
            canceled.add(runnable);
        }

        void runNext() {
            Runnable runnable = runnables.remove(0);
            if (!canceled.contains(runnable)) {
                runnable.run();
            }
        }

        void runNextLifecycleTask() {
            lifecycleTasks.remove(0).run();
        }

        void runAllLifecycleTasks() {
            while (!lifecycleTasks.isEmpty()) {
                runNextLifecycleTask();
            }
        }

        void joinLifecycleThreads() throws InterruptedException {
            for (Thread thread : lifecycleThreads) {
                thread.join(2000);
            }
        }
    }

    private static final class TunnelStateCall {
        final int account;
        final boolean enabled;
        final boolean blocked;
        final long lifecycleGeneration;

        TunnelStateCall(int account, boolean enabled, boolean blocked, long lifecycleGeneration) {
            this.account = account;
            this.enabled = enabled;
            this.blocked = blocked;
            this.lifecycleGeneration = lifecycleGeneration;
        }
    }
}
