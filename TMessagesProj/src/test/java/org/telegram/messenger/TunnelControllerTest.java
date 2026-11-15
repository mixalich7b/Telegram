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
    public void failedTunnelConsumesRepeatedProxyUpdatesForEveryAccount() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        tunnelRuntime.failure = new RuntimeException("unavailable");
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        // ConnectionsManager must treat a failed enabled tunnel as a consumed
        // route update, so its ordinary proxy/direct branches are never used.
        for (int update = 0; update < 2; update++) {
            for (int account = 0; account < 4; account++) {
                assertTrue(controller.applyTunnelSettingsForAccount(account, 4));
                TunnelStateCall call = lastCallForAccount(routeStateApplier, account);
                assertTrue(call.enabled);
                assertTrue(call.blocked);
            }
        }
        assertEquals(1, tunnelRuntime.startCalls);
        for (TunnelStateCall call : routeStateApplier.calls) {
            assertTrue(call.enabled);
            assertTrue(call.blocked);
        }
    }

    @Test
    public void missingProfileConsumesRouteUpdatesUntilExplicitlyDisabled() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        config.validationError = "missing active tunnel profile";
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        TunnelController controller = newController(config, tunnelRuntime, routeStateApplier);

        for (int account = 0; account < 4; account++) {
            assertTrue(controller.applyTunnelSettingsForAccount(account, 4));
            assertTrue(lastCallForAccount(routeStateApplier, account).blocked);
        }
        assertEquals(0, tunnelRuntime.startCalls);

        config.enabled = false;
        controller.disable(4);
        for (int account = 0; account < 4; account++) {
            assertFalse(controller.applyTunnelSettingsForAccount(account, 4));
            assertFalse(lastCallForAccount(routeStateApplier, account).enabled);
        }
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
    public void eightDownloadAttemptsKeepTunnelWhilePendingOrSuccessful() {
        FakeTunnelRuntime runtime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routes = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler scheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(new FakeTunnelConfigProvider(), runtime, routes, scheduler);
        controller.applyTunnelSettingsForAllAccounts(1);
        long generation = lastCallForAccount(routes, 0).lifecycleGeneration;
        for (int i = 0; i < 8; i++) {
            controller.onTunnelTcpConnectStarted(0, generation);
        }
        // A failed IPv6 destination must not cancel the other download sockets.
        for (int i = 0; i < 6; i++) {
            controller.onTunnelTcpConnectFailed(0, generation, 1);
            assertEquals(0, runtime.stopCalls);
            assertTrue(scheduler.delays.isEmpty());
        }
        controller.onTunnelTcpConnected(0, generation);
        controller.onTunnelTcpConnectFailed(0, generation, 1);
        assertEquals(0, runtime.stopCalls);
        assertTrue(scheduler.delays.isEmpty());
        assertTrue(controller.isRunningForTests());
        assertFalse(lastCallForAccount(routes, 0).blocked);
    }

    @Test
    public void eightFailedDownloadAttemptsRestartOnlyAfterLastFailureSettles() {
        FakeTunnelRuntime runtime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routes = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler scheduler = new FakeLifecycleTaskScheduler();
        TunnelController controller = newController(new FakeTunnelConfigProvider(), runtime, routes, scheduler);
        controller.applyTunnelSettingsForAllAccounts(1);
        long generation = lastCallForAccount(routes, 0).lifecycleGeneration;
        for (int i = 0; i < 8; i++) {
            controller.onTunnelTcpConnectStarted(0, generation);
        }
        for (int i = 0; i < 7; i++) {
            controller.onTunnelTcpConnectFailed(0, generation, 1);
            assertTrue(scheduler.delays.isEmpty());
        }
        controller.onTunnelTcpConnectFailed(0, generation, 1);
        assertEquals(0, runtime.stopCalls);
        assertEquals(1, scheduler.delays.size());
        assertEquals(250L, (long) scheduler.delays.get(0));
        scheduler.runNext();
        assertEquals(1, runtime.stopCalls);
        assertTrue(lastCallForAccount(routes, 0).enabled);
        assertTrue(lastCallForAccount(routes, 0).blocked);
        assertEquals(1000L, (long) scheduler.delays.get(1));
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
    public void tcpAttemptDiagnosticsExposeGenerationPendingAndSettleDecision() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        RecordingLogger logger = new RecordingLogger();
        TunnelController controller = new TunnelController(
                config,
                tunnelRuntime,
                routeStateApplier,
                logger,
                lifecycleTaskScheduler
        );

        controller.applyTunnelSettingsForAllAccounts(1);
        long lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnected(0, lifecycleGeneration);

        assertDebugLogContains(logger, "tunnel_trace event=runtime_active protocol=WireGuard generation="
                + lifecycleGeneration);
        assertDebugLogContains(logger, "tunnel_trace event=tcp_attempt_started protocol=WireGuard account=0 generation="
                + lifecycleGeneration + " pending=1 settle_cancelled=false");
        assertDebugLogContains(logger, "tunnel_trace event=tcp_attempt_failed protocol=WireGuard account=0 generation="
                + lifecycleGeneration + " pending=0 success_seen=false settle_scheduled=true");
        assertDebugLogContains(logger, "tunnel_trace event=tcp_attempt_started protocol=WireGuard account=0 generation="
                + lifecycleGeneration + " pending=1 settle_cancelled=true");
        assertDebugLogContains(logger, "tunnel_trace event=tcp_attempt_connected protocol=WireGuard account=0 generation="
                + lifecycleGeneration + " pending=0 success_seen=true settle_cancelled=false");
    }

    @Test
    public void tcpAttemptDiagnosticsReportSettledGroupFailure() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler lifecycleTaskScheduler = new FakeLifecycleTaskScheduler();
        RecordingLogger logger = new RecordingLogger();
        TunnelController controller = new TunnelController(
                config,
                tunnelRuntime,
                routeStateApplier,
                logger,
                lifecycleTaskScheduler
        );

        controller.applyTunnelSettingsForAllAccounts(1);
        long lifecycleGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onTunnelTcpConnectStarted(0, lifecycleGeneration);
        controller.onTunnelTcpConnectFailed(0, lifecycleGeneration, 1);
        lifecycleTaskScheduler.runNext();

        assertErrorLogContains(logger, "tunnel_trace event=tcp_attempt_group_failed protocol=WireGuard account=0 generation="
                + lifecycleGeneration + " pending=0 success_seen=false action=restart_runtime");
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
    public void successfulNetworkRefreshLogsItsLifecycleGeneration() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime tunnelRuntime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routeStateApplier = new FakeTunnelRouteStateApplier();
        RecordingLogger logger = new RecordingLogger();
        TunnelController controller = new TunnelController(
                config,
                tunnelRuntime,
                routeStateApplier,
                logger,
                new FakeLifecycleTaskScheduler()
        );

        controller.applyTunnelSettingsForAccount(0);
        long initialGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;
        controller.onNetworkChanged(1);
        long refreshedGeneration = lastCallForAccount(routeStateApplier, 0).lifecycleGeneration;

        assertTrue(refreshedGeneration > initialGeneration);
        assertDebugLogContains(logger, "tunnel_trace event=runtime_refreshed protocol=WireGuard generation="
                + refreshedGeneration);
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

    @Test
    public void timedOutOnlyGroupSchedulesRecovery() {
        RecoveryFixture f = new RecoveryFixture();
        assertTrue(f.controller.onTunnelTcpConnectStarted(0, f.generation));
        f.controller.onTunnelTcpConnectFailed(0, f.generation, 4);
        f.scheduler.runNext();
        assertEquals(1, f.runtime.stopCalls);
        assertTrue(lastCallForAccount(f.routes, 0).blocked);
    }

    @Test
    public void continuousFailuresEventuallyDeferNewAdmissions() {
        RecoveryFixture f = new RecoveryFixture();
        for (int i = 0; i < 12; ++i) {
            assertTrue(f.controller.onTunnelTcpConnectStarted(i % 4, f.generation));
            f.controller.onTunnelTcpConnectFailed(i % 4, f.generation, 4);
        }
        // Starts inside the 250ms settlement keep the same first-failure episode.
        assertTrue(f.controller.onTunnelTcpConnectStarted(0, f.generation));
        assertEquals(1, f.scheduler.recoveryWindows.size());
        f.scheduler.recoveryWindows.get(0).run();
        for (int account = 0; account < 4; ++account) {
            assertFalse(f.controller.onTunnelTcpConnectStarted(account, f.generation));
        }
        assertEquals(0, f.runtime.stopCalls);
        assertFalse(lastCallForAccount(f.routes, 0).blocked);
        f.controller.onTunnelTcpConnectFailed(0, f.generation, 4);
        f.scheduler.runAllShortTasksUntilStop(f.runtime);
        assertEquals(1, f.runtime.stopCalls);
    }

    @Test
    public void drainingWaitsForLastAdmittedAttempt() {
        RecoveryFixture f = new RecoveryFixture();
        for (int i = 0; i < 4; ++i) assertTrue(f.controller.onTunnelTcpConnectStarted(i, f.generation));
        f.controller.onTunnelTcpConnectFailed(0, f.generation, 4);
        f.scheduler.recoveryWindows.get(0).run();
        f.controller.onTunnelTcpConnectCancelled(1, f.generation);
        f.controller.onTunnelTcpConnectFailed(2, f.generation, 4);
        assertEquals(0, f.runtime.stopCalls);
        assertTrue(f.scheduler.runnables.isEmpty());
        f.controller.onTunnelTcpConnectFailed(3, f.generation, 4);
        assertEquals(0, f.runtime.stopCalls);
        f.scheduler.runNext();
        assertEquals(1, f.runtime.stopCalls);
    }

    @Test
    public void successDuringDrainReopensAdmission() {
        RecoveryFixture f = new RecoveryFixture();
        assertTrue(f.controller.onTunnelTcpConnectStarted(0, f.generation));
        assertTrue(f.controller.onTunnelTcpConnectStarted(1, f.generation));
        assertTrue(f.controller.onTunnelTcpConnectStarted(3, f.generation));
        f.controller.onTunnelTcpConnectFailed(0, f.generation, 4);
        Runnable deadline = f.scheduler.recoveryWindows.get(0);
        deadline.run();
        assertFalse(f.controller.onTunnelTcpConnectStarted(2, f.generation));
        f.controller.onTunnelTcpConnected(1, f.generation);
        assertTrue(f.controller.onTunnelTcpConnectStarted(2, f.generation));
        deadline.run(); // Repeated/cancelled deadline must not close admission again.
        assertTrue(f.controller.onTunnelTcpConnectStarted(3, f.generation));
        f.controller.onTunnelTcpConnectFailed(2, f.generation, 4);
        f.controller.onTunnelTcpConnectCancelled(3, f.generation);
        f.controller.onTunnelTcpConnectCancelled(3, f.generation);
        assertTrue(f.scheduler.runnables.isEmpty());
        assertEquals(0, f.runtime.stopCalls);
    }

    @Test
    public void ordinaryCancellationsNeverOpenRecoveryWindow() {
        RecoveryFixture f = new RecoveryFixture();
        assertTrue(f.controller.onTunnelTcpConnectStarted(0, f.generation));
        f.controller.onTunnelTcpConnectCancelled(0, f.generation);
        assertTrue(f.scheduler.recoveryWindows.isEmpty());
        assertTrue(f.scheduler.runnables.isEmpty());
        assertEquals(0, f.runtime.stopCalls);
    }

    @Test
    public void staleRecoveryTimersCannotAffectNewGeneration() {
        for (int transition = 0; transition < 3; ++transition) {
            RecoveryFixture f = new RecoveryFixture();
            assertTrue(f.controller.onTunnelTcpConnectStarted(0, f.generation));
            assertTrue(f.controller.onTunnelTcpConnectStarted(1, f.generation));
            f.controller.onTunnelTcpConnectFailed(0, f.generation, 4);
            Runnable deadline = f.scheduler.recoveryWindows.get(0);
            if (transition == 0) {
                f.config.enabled = false;
                f.controller.disable(4);
            } else if (transition == 1) {
                f.controller.onNetworkChanged(4);
            } else {
                f.controller.restart(4);
            }
            int stopCalls = f.runtime.stopCalls;
            deadline.run();
            f.controller.onTunnelTcpConnectFailed(1, f.generation, 4);
            assertFalse(f.controller.onTunnelTcpConnectStarted(2, f.generation));
            assertEquals(stopCalls, f.runtime.stopCalls);
            if (transition != 0) {
                long generation = lastCallForAccount(f.routes, 0).lifecycleGeneration;
                assertTrue(f.controller.onTunnelTcpConnectStarted(0, generation));
            }
        }
    }

    @Test
    public void concurrentAdmissionsAtDeadlineStayBalanced() throws Exception {
        RecoveryFixture f = new RecoveryFixture();
        assertTrue(f.controller.onTunnelTcpConnectStarted(0, f.generation));
        assertTrue(f.controller.onTunnelTcpConnectStarted(1, f.generation));
        f.controller.onTunnelTcpConnectFailed(0, f.generation, 4);
        Runnable deadline = f.scheduler.recoveryWindows.get(0);
        java.util.concurrent.atomic.AtomicInteger admitted = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; ++i) {
            Thread thread = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (f.controller.onTunnelTcpConnectStarted(2, f.generation)) admitted.incrementAndGet();
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        deadline.run();
        for (Thread thread : threads) thread.join(2000);
        for (Thread thread : threads) assertFalse(thread.isAlive());
        assertFalse(f.controller.onTunnelTcpConnectStarted(3, f.generation));
        for (int i = 0; i < admitted.get(); ++i) f.controller.onTunnelTcpConnectCancelled(2, f.generation);
        assertEquals(0, f.runtime.stopCalls);
        f.controller.onTunnelTcpConnectFailed(1, f.generation, 4);
        f.scheduler.runNext();
        assertEquals(1, f.runtime.stopCalls);
    }

    @Test
    public void admissionLoggerFailureDoesNotLeakPendingAttempt() {
        FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        FakeTunnelRuntime runtime = new FakeTunnelRuntime();
        FakeTunnelRouteStateApplier routes = new FakeTunnelRouteStateApplier();
        FakeLifecycleTaskScheduler scheduler = new FakeLifecycleTaskScheduler();
        boolean[] throwOnce = {true};
        TunnelController.Logger logger = new TunnelController.Logger() {
            @Override public void debug(String message) {
                if (throwOnce[0] && message.startsWith("tunnel_trace event=tcp_attempt_started")) {
                    throwOnce[0] = false;
                    throw new IllegalStateException("diagnostic sink unavailable");
                }
            }
            @Override public void error(String message) { }
            @Override public void error(Throwable error) { }
        };
        TunnelController controller = new TunnelController(config, runtime, routes, logger, scheduler);
        controller.applyTunnelSettingsForAllAccounts(1);
        long generation = lastCallForAccount(routes, 0).lifecycleGeneration;
        org.junit.Assert.assertThrows(IllegalStateException.class,
                () -> controller.onTunnelTcpConnectStarted(0, generation));
        assertTrue(controller.onTunnelTcpConnectStarted(0, generation));
        controller.onTunnelTcpConnectFailed(0, generation, 1);
        assertEquals(1, scheduler.runnables.size());
        scheduler.runNext();
        assertEquals(1, runtime.stopCalls);
    }

    private static final class RecoveryFixture {
        final FakeTunnelConfigProvider config = new FakeTunnelConfigProvider();
        final FakeTunnelRuntime runtime = new FakeTunnelRuntime();
        final FakeTunnelRouteStateApplier routes = new FakeTunnelRouteStateApplier();
        final FakeLifecycleTaskScheduler scheduler = new FakeLifecycleTaskScheduler();
        final TunnelController controller = newController(config, runtime, routes, scheduler);
        final long generation;
        RecoveryFixture() {
            controller.applyTunnelSettingsForAllAccounts(4);
            generation = lastCallForAccount(routes, 0).lifecycleGeneration;
        }
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

    private static void assertDebugLogContains(RecordingLogger logger, String expectedMessage) {
        assertTrue("Missing debug log: " + expectedMessage + " in " + logger.debugMessages,
                logger.debugMessages.contains(expectedMessage));
    }

    private static void assertErrorLogContains(RecordingLogger logger, String expectedMessage) {
        assertTrue("Missing error log: " + expectedMessage + " in " + logger.errorMessages,
                logger.errorMessages.contains(expectedMessage));
    }

    private static final class RecordingLogger implements TunnelController.Logger {
        final List<String> debugMessages = new ArrayList<>();
        final List<String> errorMessages = new ArrayList<>();

        @Override
        public void debug(String message) {
            debugMessages.add(message);
        }

        @Override
        public void error(String message) {
            errorMessages.add(message);
        }

        @Override
        public void error(Throwable throwable) {
        }
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
        final List<Runnable> recoveryWindows = new ArrayList<>();
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
            // Keep independently-fired long recovery deadlines separate from
            // settlement/backoff assertions in the existing tests.
            if (delayMs == 20_000L) recoveryWindows.add(runnable);
            else {
                runnables.add(runnable);
                delays.add(delayMs);
            }
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

        void runAllShortTasksUntilStop(FakeTunnelRuntime runtime) {
            while (runtime.stopCalls == 0 && !runnables.isEmpty()) runNext();
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
