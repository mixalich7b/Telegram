package org.telegram.messenger;

final class TunnelController {
    private static final long TCP_CONNECT_FAILURE_SETTLE_DELAY_MS = 250L;
    private static final long[] RECONNECT_BACKOFF_DELAYS_MS = new long[]{
            1000L,
            2000L,
            2000L,
            3000L,
            3000L,
            5000L,
            5000L
    };

    enum TunnelRouteState {
        DIRECT,
        BLOCKED,
        ACTIVE
    }

    private enum RuntimeState {
        STOPPED,
        STARTING,
        RUNNING,
        RECONNECT_WAIT,
        FAILED
    }

    interface TunnelConfigProvider {
        boolean isEnabled();

        TunnelProtocol protocol();

        String protocolLabel();

        String validate();

        String buildUserspaceConfig();

        String[] localAddresses();

        String[] dnsServers();

        int mtu();
    }

    interface TunnelRuntime {
        int start(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu);

        int onNetworkChanged();

        void stop();
    }

    interface TunnelRouteStateApplier {
        void applyTunnelRouteState(int account, TunnelRouteState state, long lifecycleGeneration);
    }

    interface Logger {
        void debug(String message);

        void error(String message);

        void error(Throwable throwable);
    }

    interface StatusListener {
        void onStatusChanged();
    }

    interface LifecycleTaskScheduler {
        void execute(Runnable runnable);

        void schedule(Runnable runnable, long delayMs);

        void cancel(Runnable runnable);
    }

    private static final class ScheduledReconnect {
        final Runnable runnable;
        final long delayMs;

        ScheduledReconnect(Runnable runnable, long delayMs) {
            this.runnable = runnable;
            this.delayMs = delayMs;
        }
    }

    private final Object stateLock = new Object();
    private final TunnelConfigProvider configProvider;
    private final TunnelRuntime tunnelRuntime;
    private final TunnelRouteStateApplier routeStateApplier;
    private final Logger logger;
    private final LifecycleTaskScheduler lifecycleTaskScheduler;
    private final StatusListener statusListener;

    private RuntimeState runtimeState = RuntimeState.STOPPED;
    private String failureReason;
    private boolean reconnecting;
    private Runnable pendingReconnect;
    private int nextReconnectDelayIndex;
    private long lifecycleGeneration;
    private long reconnectScheduleGeneration;
    private int maxKnownAccountCount;
    private long routeActivatedAtNanos;
    private long tcpAttemptLifecycleGeneration;
    private int pendingTcpConnectAttempts;
    private boolean tcpConnectFailureSeen;
    private boolean tcpConnectSuccessSeen;
    private Runnable pendingTcpConnectFailureEvaluation;
    private long tcpConnectFailureEvaluationScheduleGeneration;

    TunnelController(
            TunnelConfigProvider configProvider,
            TunnelRuntime tunnelRuntime,
            TunnelRouteStateApplier routeStateApplier,
            Logger logger
    ) {
        this(
                configProvider,
                tunnelRuntime,
                routeStateApplier,
                logger,
                defaultLifecycleTaskScheduler(),
                null
        );
    }

    TunnelController(
            TunnelConfigProvider configProvider,
            TunnelRuntime tunnelRuntime,
            TunnelRouteStateApplier routeStateApplier,
            Logger logger,
            StatusListener statusListener
    ) {
        this(
                configProvider,
                tunnelRuntime,
                routeStateApplier,
                logger,
                defaultLifecycleTaskScheduler(),
                statusListener
        );
    }

    TunnelController(
            TunnelConfigProvider configProvider,
            TunnelRuntime tunnelRuntime,
            TunnelRouteStateApplier routeStateApplier,
            Logger logger,
            LifecycleTaskScheduler lifecycleTaskScheduler
    ) {
        this(
                configProvider,
                tunnelRuntime,
                routeStateApplier,
                logger,
                lifecycleTaskScheduler,
                null
        );
    }

    TunnelController(
            TunnelConfigProvider configProvider,
            TunnelRuntime tunnelRuntime,
            TunnelRouteStateApplier routeStateApplier,
            Logger logger,
            LifecycleTaskScheduler lifecycleTaskScheduler,
            StatusListener statusListener
    ) {
        this.configProvider = configProvider;
        this.tunnelRuntime = tunnelRuntime;
        this.routeStateApplier = routeStateApplier;
        this.logger = logger;
        this.lifecycleTaskScheduler = lifecycleTaskScheduler;
        this.statusListener = statusListener == null ? () -> { } : statusListener;
    }

    private static LifecycleTaskScheduler defaultLifecycleTaskScheduler() {
        return new LifecycleTaskScheduler() {
            private final DispatchQueue queue = new DispatchQueue("tunnelLifecycleQueue");

            @Override
            public void execute(Runnable runnable) {
                queue.postRunnable(runnable);
            }

            @Override
            public void schedule(Runnable runnable, long delayMs) {
                queue.postRunnable(runnable, delayMs);
            }

            @Override
            public void cancel(Runnable runnable) {
                queue.cancelRunnable(runnable);
            }
        };
    }

    boolean isEnabled() {
        return configProvider.isEnabled();
    }

    void startIfEnabled(int accountCount) {
        if (!configProvider.isEnabled()) {
            return;
        }
        Runnable lifecycleTask;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            lifecycleTask = prepareInitialStartLocked();
            applyTunnelRouteForAccountsLocked(accountCount);
        }
        executeLifecycleTask(lifecycleTask);
    }

    boolean applyTunnelSettingsForAccount(int account) {
        return applyTunnelSettingsForAccount(account, account + 1);
    }

    boolean applyTunnelSettingsForAccount(int account, int accountCount) {
        if (!configProvider.isEnabled()) {
            return false;
        }
        Runnable lifecycleTask;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            lifecycleTask = prepareInitialStartLocked();
            applyTunnelRouteForAccountLocked(account);
        }
        executeLifecycleTask(lifecycleTask);
        return true;
    }

    boolean applyTunnelSettingsForAllAccounts(int accountCount) {
        if (!configProvider.isEnabled()) {
            return false;
        }
        Runnable lifecycleTask;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            lifecycleTask = prepareInitialStartLocked();
            applyTunnelRouteForAccountsLocked(accountCount);
        }
        executeLifecycleTask(lifecycleTask);
        return true;
    }

    TunnelProxySettings getProxySettings() {
        int accountCount;
        synchronized (stateLock) {
            accountCount = Math.max(1, maxKnownAccountCount);
        }
        return getProxySettings(accountCount);
    }

    TunnelProxySettings getProxySettings(int accountCount) {
        if (!configProvider.isEnabled()) {
            return null;
        }
        Runnable lifecycleTask;
        TunnelProxySettings proxySettings;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            lifecycleTask = prepareInitialStartLocked();
            proxySettings = buildProxySettingsLocked();
        }
        executeLifecycleTask(lifecycleTask);
        return proxySettings;
    }

    void onNetworkChanged(int accountCount) {
        if (!configProvider.isEnabled()) {
            return;
        }
        Runnable lifecycleTask = null;
        Runnable tcpFailureEvaluationToCancel = null;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            if (runtimeState == RuntimeState.RUNNING) {
                tcpFailureEvaluationToCancel = resetTcpConnectAttemptsLocked();
                runtimeState = RuntimeState.STARTING;
                long generation = ++lifecycleGeneration;
                int knownAccountCount = maxKnownAccountCount;
                applyTunnelRouteForAccountsLocked(knownAccountCount);
                lifecycleTask = tracedLifecycleTask("network_refresh", generation,
                        () -> refreshNetworkBindings(generation, knownAccountCount));
            }
        }
        cancelScheduledTask(tcpFailureEvaluationToCancel);
        executeLifecycleTask(lifecycleTask);
    }

    String getFailureReason() {
        synchronized (stateLock) {
            return failureReason;
        }
    }

    boolean isReconnecting() {
        synchronized (stateLock) {
            return reconnecting;
        }
    }

    void restart(int accountCount) {
        if (!configProvider.isEnabled()) {
            disable(accountCount);
            return;
        }
        Runnable reconnectToCancel;
        Runnable tcpFailureEvaluationToCancel;
        Runnable lifecycleTask;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            reconnectToCancel = clearPendingReconnectLocked(true);
            tcpFailureEvaluationToCancel = resetTcpConnectAttemptsLocked();
            failureReason = null;
            reconnecting = false;
            runtimeState = RuntimeState.STARTING;
            long generation = ++lifecycleGeneration;
            int knownAccountCount = maxKnownAccountCount;
            applyTunnelRouteForAccountsLocked(knownAccountCount);
            lifecycleTask = tracedLifecycleTask("restart", generation,
                    () -> startRuntime(generation, knownAccountCount, true));
        }
        cancelScheduledTask(reconnectToCancel);
        cancelScheduledTask(tcpFailureEvaluationToCancel);
        executeLifecycleTask(lifecycleTask);
    }

    void disable(int accountCount) {
        Runnable reconnectToCancel;
        Runnable tcpFailureEvaluationToCancel;
        synchronized (stateLock) {
            reconnectToCancel = clearPendingReconnectLocked(true);
            tcpFailureEvaluationToCancel = resetTcpConnectAttemptsLocked();
            ++lifecycleGeneration;
            runtimeState = RuntimeState.STOPPED;
            failureReason = null;
            reconnecting = false;
            maxKnownAccountCount = 0;
            applyDirectRouteForAccountsLocked(accountCount);
        }
        cancelScheduledTask(reconnectToCancel);
        cancelScheduledTask(tcpFailureEvaluationToCancel);
        lifecycleTaskScheduler.execute(tracedLifecycleTask("disable", currentGeneration(), this::stopRuntimeSafely));
    }

    void onTunnelTcpConnectStarted(int sourceAccount, long callbackGeneration) {
        Runnable tcpFailureEvaluationToCancel = null;
        int pendingAttempts;
        synchronized (stateLock) {
            if (!isCurrentTunnelTcpCallbackLocked(callbackGeneration)) {
                return;
            }
            if (tcpAttemptLifecycleGeneration != callbackGeneration) {
                tcpFailureEvaluationToCancel = resetTcpConnectAttemptsLocked();
                tcpAttemptLifecycleGeneration = callbackGeneration;
            } else if (pendingTcpConnectAttempts == 0) {
                boolean continuingFailedBatch = pendingTcpConnectFailureEvaluation != null;
                tcpFailureEvaluationToCancel = clearPendingTcpConnectFailureEvaluationLocked();
                if (!continuingFailedBatch) {
                    tcpConnectFailureSeen = false;
                }
                tcpConnectSuccessSeen = false;
            }
            pendingTcpConnectAttempts++;
            pendingAttempts = pendingTcpConnectAttempts;
        }
        cancelScheduledTask(tcpFailureEvaluationToCancel);
        logger.debug("tunnel_trace event=tcp_attempt_started protocol=" + configProvider.protocolLabel()
                + " account=" + sourceAccount
                + " generation=" + callbackGeneration
                + " pending=" + pendingAttempts
                + " settle_cancelled=" + (tcpFailureEvaluationToCancel != null));
    }

    void onTunnelTcpConnectFailed(int sourceAccount, long callbackGeneration, int accountCount) {
        if (!configProvider.isEnabled()) {
            return;
        }
        Runnable failureEvaluation = null;
        int pendingAttempts;
        boolean successSeen;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            if (!isCurrentTunnelTcpCallbackLocked(callbackGeneration)) {
                return;
            }
            ensureTcpAttemptGenerationLocked(callbackGeneration);
            if (pendingTcpConnectAttempts > 0) {
                pendingTcpConnectAttempts--;
            }
            tcpConnectFailureSeen = true;
            if (pendingTcpConnectAttempts == 0 && !tcpConnectSuccessSeen) {
                failureEvaluation = prepareTcpConnectFailureEvaluationLocked(
                        sourceAccount,
                        callbackGeneration,
                        maxKnownAccountCount
                );
            }
            pendingAttempts = pendingTcpConnectAttempts;
            successSeen = tcpConnectSuccessSeen;
        }
        logger.debug("tunnel_trace event=tcp_attempt_failed protocol=" + configProvider.protocolLabel()
                + " account=" + sourceAccount
                + " generation=" + callbackGeneration
                + " pending=" + pendingAttempts
                + " success_seen=" + successSeen
                + " settle_scheduled=" + (failureEvaluation != null));
        if (failureEvaluation != null) {
            lifecycleTaskScheduler.schedule(failureEvaluation, TCP_CONNECT_FAILURE_SETTLE_DELAY_MS);
        }
    }

    void onTunnelTcpConnected(int sourceAccount, long callbackGeneration) {
        Runnable tcpFailureEvaluationToCancel;
        int pendingAttempts;
        long routeElapsedMs;
        synchronized (stateLock) {
            if (!isCurrentTunnelTcpCallbackLocked(callbackGeneration)) {
                return;
            }
            ensureTcpAttemptGenerationLocked(callbackGeneration);
            if (pendingTcpConnectAttempts > 0) {
                pendingTcpConnectAttempts--;
            }
            tcpConnectSuccessSeen = true;
            tcpConnectFailureSeen = false;
            tcpFailureEvaluationToCancel = clearPendingTcpConnectFailureEvaluationLocked();
            nextReconnectDelayIndex = 0;
            failureReason = null;
            reconnecting = false;
            pendingAttempts = pendingTcpConnectAttempts;
            routeElapsedMs = elapsedMillis(routeActivatedAtNanos);
        }
        cancelScheduledTask(tcpFailureEvaluationToCancel);
        logger.debug("tunnel_trace event=tcp_connected_timing protocol=" + configProvider.protocolLabel()
                + " account=" + sourceAccount + " generation=" + callbackGeneration
                + " route_elapsed_ms=" + routeElapsedMs);
        notifyStatusChanged();
        logger.debug("tunnel_trace event=tcp_attempt_connected protocol=" + configProvider.protocolLabel()
                + " account=" + sourceAccount
                + " generation=" + callbackGeneration
                + " pending=" + pendingAttempts
                + " success_seen=true"
                + " settle_cancelled=" + (tcpFailureEvaluationToCancel != null));
    }

    void onTunnelTcpConnectCancelled(int sourceAccount, long callbackGeneration) {
        Runnable failureEvaluation = null;
        int pendingAttempts;
        boolean successSeen;
        synchronized (stateLock) {
            if (!isCurrentTunnelTcpCallbackLocked(callbackGeneration)
                    || tcpAttemptLifecycleGeneration != callbackGeneration
                    || pendingTcpConnectAttempts <= 0) {
                return;
            }
            pendingTcpConnectAttempts--;
            if (pendingTcpConnectAttempts == 0 && tcpConnectFailureSeen && !tcpConnectSuccessSeen) {
                failureEvaluation = prepareTcpConnectFailureEvaluationLocked(
                        sourceAccount,
                        callbackGeneration,
                        maxKnownAccountCount
                );
            }
            pendingAttempts = pendingTcpConnectAttempts;
            successSeen = tcpConnectSuccessSeen;
        }
        logger.debug("tunnel_trace event=tcp_attempt_cancelled protocol=" + configProvider.protocolLabel()
                + " account=" + sourceAccount
                + " generation=" + callbackGeneration
                + " pending=" + pendingAttempts
                + " success_seen=" + successSeen
                + " settle_scheduled=" + (failureEvaluation != null));
        if (failureEvaluation != null) {
            lifecycleTaskScheduler.schedule(failureEvaluation, TCP_CONNECT_FAILURE_SETTLE_DELAY_MS);
        }
    }

    boolean isRunningForTests() {
        synchronized (stateLock) {
            return runtimeState == RuntimeState.RUNNING;
        }
    }

    private Runnable prepareInitialStartLocked() {
        if (runtimeState != RuntimeState.STOPPED) {
            return null;
        }
        runtimeState = RuntimeState.STARTING;
        long generation = ++lifecycleGeneration;
        int accountCount = Math.max(1, maxKnownAccountCount);
        return tracedLifecycleTask("initial_start", generation,
                () -> startRuntime(generation, accountCount, false));
    }

    private void startRuntime(long generation, int accountCount, boolean stopFirst) {
        if (!isCurrentLifecycleOperation(generation, RuntimeState.STARTING)) {
            return;
        }
        logger.debug("tunnel_trace event=runtime_starting protocol=" + configProvider.protocolLabel()
                + " generation=" + generation + " stop_first=" + stopFirst);
        if (stopFirst) {
            stopRuntimeSafely();
            if (!isCurrentLifecycleOperation(generation, RuntimeState.STARTING)) {
                return;
            }
        }

        String validationError;
        try {
            validationError = configProvider.validate();
        } catch (Throwable throwable) {
            completeStartFailure(
                    generation,
                    accountCount,
                    "unable to validate " + configProvider.protocolLabel() + " configuration",
                    throwable,
                    false
            );
            return;
        }
        if (validationError != null) {
            completeStartFailure(generation, accountCount, validationError, null, false);
            return;
        }

        long nativeStartedAt = System.nanoTime();
        logger.debug("tunnel_trace event=native_start_begin protocol=" + configProvider.protocolLabel()
                + " generation=" + generation);
        try {
            int status = tunnelRuntime.start(
                    configProvider.buildUserspaceConfig(),
                    configProvider.localAddresses(),
                    configProvider.dnsServers(),
                    configProvider.mtu()
            );
            logger.debug("tunnel_trace event=native_start_result protocol=" + configProvider.protocolLabel()
                    + " generation=" + generation + " status=" + status
                    + " elapsed_ms=" + elapsedMillis(nativeStartedAt));
            if (status < 0) {
                stopRuntimeSafely();
                completeStartFailure(
                        generation,
                        accountCount,
                        "nativeStart returned error " + status,
                        null,
                        true
                );
                return;
            }
        } catch (Throwable throwable) {
            logger.error("tunnel_trace event=native_start_exception protocol=" + configProvider.protocolLabel()
                    + " generation=" + generation + " error_type=" + throwable.getClass().getSimpleName()
                    + " elapsed_ms=" + elapsedMillis(nativeStartedAt));
            stopRuntimeSafely();
            completeStartFailure(
                    generation,
                    accountCount,
                    "unable to start " + configProvider.protocolLabel() + " runtime",
                    throwable,
                    true
            );
            return;
        }

        boolean stale;
        int knownAccountCount;
        long activeGeneration;
        synchronized (stateLock) {
            stale = generation != lifecycleGeneration
                    || runtimeState != RuntimeState.STARTING
                    || !configProvider.isEnabled();
            if (!stale) {
                runtimeState = RuntimeState.RUNNING;
                routeActivatedAtNanos = System.nanoTime();
                failureReason = null;
            }
            knownAccountCount = Math.max(accountCount, maxKnownAccountCount);
            activeGeneration = lifecycleGeneration;
        }
        if (stale) {
            logger.debug("tunnel_trace event=runtime_start_discarded generation=" + generation);
            stopRuntimeSafely();
            return;
        }
        notifyStatusChanged();
        applyTunnelRouteForAccounts(knownAccountCount, TunnelRouteState.ACTIVE, activeGeneration);
        logger.debug("tunnel_trace event=runtime_active protocol=" + configProvider.protocolLabel()
                + " generation=" + activeGeneration);
    }

    private void completeStartFailure(
            long generation,
            int accountCount,
            String reason,
            Throwable throwable,
            boolean retryable
    ) {
        ScheduledReconnect scheduledReconnect = null;
        int knownAccountCount;
        long failedGeneration;
        synchronized (stateLock) {
            if (generation != lifecycleGeneration || runtimeState != RuntimeState.STARTING) {
                return;
            }
            failureReason = reason;
            runtimeState = retryable ? RuntimeState.RECONNECT_WAIT : RuntimeState.FAILED;
            reconnecting = false;
            knownAccountCount = Math.max(accountCount, maxKnownAccountCount);
            failedGeneration = lifecycleGeneration;
            if (retryable) {
                scheduledReconnect = prepareReconnectLocked();
            }
        }
        if (throwable != null) {
            logger.error(throwable);
        }
        logger.error("tunnel_trace event=runtime_failed protocol=" + configProvider.protocolLabel()
                + " generation=" + generation + " retryable=" + retryable + " route=blocked reason=" + reason);
        notifyStatusChanged();
        applyTunnelRouteForAccounts(knownAccountCount, TunnelRouteState.BLOCKED, failedGeneration);
        scheduleReconnect(scheduledReconnect);
    }

    private void refreshNetworkBindings(long generation, int accountCount) {
        if (!isCurrentLifecycleOperation(generation, RuntimeState.STARTING)) {
            return;
        }
        long startedAt = System.nanoTime();
        logger.debug("tunnel_trace event=network_refresh_begin protocol=" + configProvider.protocolLabel()
                + " generation=" + generation);
        try {
            int status = tunnelRuntime.onNetworkChanged();
            logger.debug("tunnel_trace event=network_refresh_result protocol=" + configProvider.protocolLabel()
                    + " generation=" + generation + " status=" + status
                    + " elapsed_ms=" + elapsedMillis(startedAt));
            if (status >= 0) {
                completeNetworkRefresh(generation, accountCount);
                return;
            }
            logger.error("tunnel_trace event=network_refresh_failed generation=" + generation
                    + " status=" + status + " action=restart_runtime");
        } catch (Throwable throwable) {
            logger.error(throwable);
            logger.error("tunnel_trace event=network_refresh_failed generation=" + generation
                    + " error_type=" + throwable.getClass().getSimpleName()
                    + " elapsed_ms=" + elapsedMillis(startedAt) + " action=restart_runtime");
        }
        stopRuntimeSafely();
        startRuntime(generation, accountCount, false);
    }

    private void completeNetworkRefresh(long generation, int accountCount) {
        int knownAccountCount;
        long activeGeneration;
        synchronized (stateLock) {
            if (generation != lifecycleGeneration
                    || runtimeState != RuntimeState.STARTING
                    || !configProvider.isEnabled()) {
                return;
            }
            runtimeState = RuntimeState.RUNNING;
            routeActivatedAtNanos = System.nanoTime();
            failureReason = null;
            reconnecting = false;
            knownAccountCount = Math.max(accountCount, maxKnownAccountCount);
            activeGeneration = lifecycleGeneration;
        }
        notifyStatusChanged();
        applyTunnelRouteForAccounts(knownAccountCount, TunnelRouteState.ACTIVE, activeGeneration);
        logger.debug("tunnel_trace event=runtime_refreshed protocol=" + configProvider.protocolLabel()
                + " generation=" + activeGeneration);
    }

    private void stopAndScheduleReconnect(long generation, int accountCount) {
        stopRuntimeSafely();
        ScheduledReconnect scheduledReconnect;
        synchronized (stateLock) {
            if (generation != lifecycleGeneration
                    || runtimeState != RuntimeState.RECONNECT_WAIT
                    || !configProvider.isEnabled()) {
                return;
            }
            rememberAccountCountLocked(accountCount);
            scheduledReconnect = prepareReconnectLocked();
        }
        scheduleReconnect(scheduledReconnect);
    }

    private ScheduledReconnect prepareReconnectLocked() {
        if (pendingReconnect != null || !configProvider.isEnabled()) {
            return null;
        }
        long delayMs = RECONNECT_BACKOFF_DELAYS_MS[nextReconnectDelayIndex];
        nextReconnectDelayIndex = (nextReconnectDelayIndex + 1) % RECONNECT_BACKOFF_DELAYS_MS.length;
        long scheduleGeneration = ++reconnectScheduleGeneration;
        long scheduledAt = System.nanoTime();
        Runnable reconnect = () -> {
            logger.debug("tunnel_trace event=reconnect_timer_fired schedule=" + scheduleGeneration
                    + " delay_ms=" + delayMs + " actual_wait_ms=" + elapsedMillis(scheduledAt));
            runReconnect(scheduleGeneration);
        };
        pendingReconnect = reconnect;
        logger.error("tunnel_trace event=reconnect_scheduled protocol=" + configProvider.protocolLabel()
                + " generation=" + lifecycleGeneration + " schedule=" + scheduleGeneration
                + " delay_ms=" + delayMs + " reason=" + failureReason);
        return new ScheduledReconnect(reconnect, delayMs);
    }

    private Runnable prepareTcpConnectFailureEvaluationLocked(
            int sourceAccount,
            long callbackGeneration,
            int accountCount
    ) {
        if (pendingTcpConnectFailureEvaluation != null) {
            return null;
        }
        long scheduleGeneration = ++tcpConnectFailureEvaluationScheduleGeneration;
        Runnable evaluation = () -> evaluateTcpConnectFailures(
                scheduleGeneration,
                sourceAccount,
                callbackGeneration,
                accountCount
        );
        pendingTcpConnectFailureEvaluation = evaluation;
        return evaluation;
    }

    private void evaluateTcpConnectFailures(
            long scheduleGeneration,
            int sourceAccount,
            long callbackGeneration,
            int accountCount
    ) {
        Runnable lifecycleTask = null;
        synchronized (stateLock) {
            if (scheduleGeneration != tcpConnectFailureEvaluationScheduleGeneration
                    || pendingTcpConnectFailureEvaluation == null) {
                return;
            }
            pendingTcpConnectFailureEvaluation = null;
            if (!isCurrentTunnelTcpCallbackLocked(callbackGeneration)
                    || pendingTcpConnectAttempts != 0
                    || tcpConnectSuccessSeen
                    || !tcpConnectFailureSeen) {
                return;
            }
            runtimeState = RuntimeState.RECONNECT_WAIT;
            failureReason = configProvider.protocolLabel() + " TCP connections failed";
            reconnecting = false;
            logger.error("tunnel_trace event=tcp_attempt_group_failed protocol=" + configProvider.protocolLabel()
                    + " account=" + sourceAccount
                    + " generation=" + callbackGeneration
                    + " pending=0 success_seen=false action=restart_runtime");
            long generation = ++lifecycleGeneration;
            int knownAccountCount = Math.max(accountCount, maxKnownAccountCount);
            resetTcpConnectAttemptsLocked();
            applyTunnelRouteForAccountsLocked(knownAccountCount);
            lifecycleTask = tracedLifecycleTask("tcp_failure_recovery", generation,
                    () -> stopAndScheduleReconnect(generation, knownAccountCount));
        }
        notifyStatusChanged();
        executeLifecycleTask(lifecycleTask);
    }

    private void runReconnect(long scheduleGeneration) {
        long generation;
        int accountCount;
        synchronized (stateLock) {
            if (scheduleGeneration != reconnectScheduleGeneration
                    || pendingReconnect == null
                    || runtimeState != RuntimeState.RECONNECT_WAIT) {
                return;
            }
            pendingReconnect = null;
            if (!configProvider.isEnabled()) {
                return;
            }
            runtimeState = RuntimeState.STARTING;
            reconnecting = true;
            generation = ++lifecycleGeneration;
            accountCount = Math.max(1, maxKnownAccountCount);
        }
        logger.debug("tunnel_trace event=reconnect_started protocol=" + configProvider.protocolLabel()
                + " generation=" + generation + " schedule=" + scheduleGeneration);
        notifyStatusChanged();
        applyTunnelRouteForAccounts(accountCount, TunnelRouteState.BLOCKED, generation);
        startRuntime(generation, accountCount, true);
    }

    private Runnable clearPendingReconnectLocked(boolean resetBackoff) {
        Runnable reconnectToCancel = pendingReconnect;
        pendingReconnect = null;
        if (reconnectToCancel != null) {
            logger.debug("tunnel_trace event=reconnect_cancelled generation=" + lifecycleGeneration
                    + " schedule=" + reconnectScheduleGeneration);
        }
        ++reconnectScheduleGeneration;
        if (resetBackoff) {
            nextReconnectDelayIndex = 0;
        }
        return reconnectToCancel;
    }

    private boolean isCurrentTunnelTcpCallbackLocked(long callbackGeneration) {
        return runtimeState == RuntimeState.RUNNING
                && callbackGeneration == lifecycleGeneration
                && configProvider.isEnabled();
    }

    private void ensureTcpAttemptGenerationLocked(long callbackGeneration) {
        if (tcpAttemptLifecycleGeneration == callbackGeneration) {
            return;
        }
        resetTcpConnectAttemptsLocked();
        tcpAttemptLifecycleGeneration = callbackGeneration;
    }

    private Runnable resetTcpConnectAttemptsLocked() {
        Runnable failureEvaluationToCancel = clearPendingTcpConnectFailureEvaluationLocked();
        tcpAttemptLifecycleGeneration = 0;
        pendingTcpConnectAttempts = 0;
        tcpConnectFailureSeen = false;
        tcpConnectSuccessSeen = false;
        return failureEvaluationToCancel;
    }

    private Runnable clearPendingTcpConnectFailureEvaluationLocked() {
        Runnable failureEvaluationToCancel = pendingTcpConnectFailureEvaluation;
        pendingTcpConnectFailureEvaluation = null;
        ++tcpConnectFailureEvaluationScheduleGeneration;
        return failureEvaluationToCancel;
    }

    private boolean isCurrentLifecycleOperation(long generation, RuntimeState expectedState) {
        synchronized (stateLock) {
            return generation == lifecycleGeneration
                    && runtimeState == expectedState
                    && configProvider.isEnabled();
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private long currentGeneration() {
        synchronized (stateLock) {
            return lifecycleGeneration;
        }
    }

    // Measure queue wait separately from native work, which may block on DNS or shutdown.
    private Runnable tracedLifecycleTask(String operation, long generation, Runnable task) {
        long queuedAt = System.nanoTime();
        logger.debug("tunnel_trace event=lifecycle_queued operation=" + operation + " generation=" + generation);
        return () -> {
            long startedAt = System.nanoTime();
            logger.debug("tunnel_trace event=lifecycle_begin operation=" + operation + " generation=" + generation
                    + " queue_wait_ms=" + elapsedMillis(queuedAt));
            try {
                task.run();
            } finally {
                logger.debug("tunnel_trace event=lifecycle_end operation=" + operation + " generation=" + generation
                        + " elapsed_ms=" + elapsedMillis(startedAt));
            }
        };
    }

    private void stopRuntimeSafely() {
        long startedAt = System.nanoTime();
        logger.debug("tunnel_trace event=runtime_stop_begin");
        try {
            tunnelRuntime.stop();
            logger.debug("tunnel_trace event=runtime_stopped elapsed_ms=" + elapsedMillis(startedAt));
        } catch (Throwable throwable) {
            logger.error("tunnel_trace event=runtime_stop_failed elapsed_ms=" + elapsedMillis(startedAt)
                    + " error_type=" + throwable.getClass().getSimpleName());
            logger.error(throwable);
        }
    }

    private void executeLifecycleTask(Runnable lifecycleTask) {
        if (lifecycleTask != null) {
            lifecycleTaskScheduler.execute(lifecycleTask);
        }
    }

    private void scheduleReconnect(ScheduledReconnect scheduledReconnect) {
        if (scheduledReconnect != null) {
            lifecycleTaskScheduler.schedule(scheduledReconnect.runnable, scheduledReconnect.delayMs);
        }
    }

    private void cancelScheduledTask(Runnable runnable) {
        if (runnable != null) {
            lifecycleTaskScheduler.cancel(runnable);
        }
    }

    private void notifyStatusChanged() {
        statusListener.onStatusChanged();
    }

    private void rememberAccountCountLocked(int accountCount) {
        maxKnownAccountCount = Math.max(maxKnownAccountCount, Math.max(1, accountCount));
    }

    private void applyTunnelRouteForAccountsLocked(int accountCount) {
        TunnelRouteState state = runtimeState == RuntimeState.RUNNING
                ? TunnelRouteState.ACTIVE
                : TunnelRouteState.BLOCKED;
        applyTunnelRouteForAccounts(accountCount, state, lifecycleGeneration);
    }

    private void applyTunnelRouteForAccountLocked(int account) {
        TunnelRouteState state = runtimeState == RuntimeState.RUNNING
                ? TunnelRouteState.ACTIVE
                : TunnelRouteState.BLOCKED;
        routeStateApplier.applyTunnelRouteState(account, state, lifecycleGeneration);
    }

    private void applyDirectRouteForAccountsLocked(int accountCount) {
        applyTunnelRouteForAccounts(accountCount, TunnelRouteState.DIRECT, lifecycleGeneration);
    }

    private void applyTunnelRouteForAccounts(int accountCount, TunnelRouteState state, long generation) {
        for (int account = 0; account < accountCount; account++) {
            routeStateApplier.applyTunnelRouteState(account, state, generation);
        }
    }

    private TunnelProxySettings buildProxySettingsLocked() {
        boolean blocked = runtimeState != RuntimeState.RUNNING;
        return new TunnelProxySettings(configProvider.protocol(), "", 0, "", "", blocked);
    }
}
