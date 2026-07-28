package org.telegram.messenger;

final class TunnelController {
    private static final long[] RECONNECT_BACKOFF_DELAYS_MS = new long[]{
            1000L,
            2000L,
            2000L,
            3000L,
            5000L,
            10000L,
            10000L,
            10000L
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

    private RuntimeState runtimeState = RuntimeState.STOPPED;
    private String failureReason;
    private Runnable pendingReconnect;
    private int nextReconnectDelayIndex;
    private long lifecycleGeneration;
    private long reconnectScheduleGeneration;
    private int maxKnownAccountCount;

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
                new LifecycleTaskScheduler() {
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
                });
    }

    TunnelController(
            TunnelConfigProvider configProvider,
            TunnelRuntime tunnelRuntime,
            TunnelRouteStateApplier routeStateApplier,
            Logger logger,
            LifecycleTaskScheduler lifecycleTaskScheduler
    ) {
        this.configProvider = configProvider;
        this.tunnelRuntime = tunnelRuntime;
        this.routeStateApplier = routeStateApplier;
        this.logger = logger;
        this.lifecycleTaskScheduler = lifecycleTaskScheduler;
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
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            if (runtimeState == RuntimeState.RUNNING) {
                runtimeState = RuntimeState.STARTING;
                long generation = ++lifecycleGeneration;
                int knownAccountCount = maxKnownAccountCount;
                applyTunnelRouteForAccountsLocked(knownAccountCount);
                lifecycleTask = () -> refreshNetworkBindings(generation, knownAccountCount);
            }
        }
        executeLifecycleTask(lifecycleTask);
    }

    String getFailureReason() {
        synchronized (stateLock) {
            return failureReason;
        }
    }

    void restart(int accountCount) {
        if (!configProvider.isEnabled()) {
            disable(accountCount);
            return;
        }
        Runnable reconnectToCancel;
        Runnable lifecycleTask;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            reconnectToCancel = clearPendingReconnectLocked(true);
            failureReason = null;
            runtimeState = RuntimeState.STARTING;
            long generation = ++lifecycleGeneration;
            int knownAccountCount = maxKnownAccountCount;
            applyTunnelRouteForAccountsLocked(knownAccountCount);
            lifecycleTask = () -> startRuntime(generation, knownAccountCount, true);
        }
        cancelScheduledTask(reconnectToCancel);
        executeLifecycleTask(lifecycleTask);
    }

    void disable(int accountCount) {
        Runnable reconnectToCancel;
        synchronized (stateLock) {
            reconnectToCancel = clearPendingReconnectLocked(true);
            ++lifecycleGeneration;
            runtimeState = RuntimeState.STOPPED;
            failureReason = null;
            maxKnownAccountCount = 0;
            applyDirectRouteForAccountsLocked(accountCount);
        }
        cancelScheduledTask(reconnectToCancel);
        lifecycleTaskScheduler.execute(this::stopRuntimeSafely);
    }

    void onTunnelTcpConnectFailed(int sourceAccount, long callbackGeneration, int accountCount) {
        if (!configProvider.isEnabled()) {
            return;
        }
        Runnable lifecycleTask = null;
        synchronized (stateLock) {
            rememberAccountCountLocked(accountCount);
            if (runtimeState == RuntimeState.RUNNING && callbackGeneration == lifecycleGeneration) {
                runtimeState = RuntimeState.RECONNECT_WAIT;
                failureReason = configProvider.protocolLabel() + " TCP connection failed";
                logger.error(configProvider.protocolLabel() + " runtime failed for account "
                        + sourceAccount + ": " + failureReason);
                long generation = ++lifecycleGeneration;
                int knownAccountCount = maxKnownAccountCount;
                applyTunnelRouteForAccountsLocked(knownAccountCount);
                lifecycleTask = () -> stopAndScheduleReconnect(generation, knownAccountCount);
            }
        }
        executeLifecycleTask(lifecycleTask);
    }

    void onTunnelTcpConnected(int sourceAccount, long callbackGeneration) {
        synchronized (stateLock) {
            if (runtimeState != RuntimeState.RUNNING
                    || callbackGeneration != lifecycleGeneration
                    || !configProvider.isEnabled()) {
                return;
            }
            nextReconnectDelayIndex = 0;
            failureReason = null;
        }
        logger.debug(configProvider.protocolLabel() + " TCP connection established for account " + sourceAccount);
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
        return () -> startRuntime(generation, accountCount, false);
    }

    private void startRuntime(long generation, int accountCount, boolean stopFirst) {
        if (!isCurrentLifecycleOperation(generation, RuntimeState.STARTING)) {
            return;
        }
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

        try {
            int status = tunnelRuntime.start(
                    configProvider.buildUserspaceConfig(),
                    configProvider.localAddresses(),
                    configProvider.dnsServers(),
                    configProvider.mtu()
            );
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
                failureReason = null;
            }
            knownAccountCount = Math.max(accountCount, maxKnownAccountCount);
            activeGeneration = lifecycleGeneration;
        }
        if (stale) {
            stopRuntimeSafely();
            return;
        }
        applyTunnelRouteForAccounts(knownAccountCount, TunnelRouteState.ACTIVE, activeGeneration);
        logger.debug(configProvider.protocolLabel() + " runtime started");
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
            knownAccountCount = Math.max(accountCount, maxKnownAccountCount);
            failedGeneration = lifecycleGeneration;
            if (retryable) {
                scheduledReconnect = prepareReconnectLocked();
            }
        }
        if (throwable != null) {
            logger.error(throwable);
        }
        logger.error(configProvider.protocolLabel() + " runtime failed: " + reason);
        applyTunnelRouteForAccounts(knownAccountCount, TunnelRouteState.BLOCKED, failedGeneration);
        scheduleReconnect(scheduledReconnect);
    }

    private void refreshNetworkBindings(long generation, int accountCount) {
        if (!isCurrentLifecycleOperation(generation, RuntimeState.STARTING)) {
            return;
        }
        try {
            int status = tunnelRuntime.onNetworkChanged();
            if (status >= 0) {
                completeNetworkRefresh(generation, accountCount);
                return;
            }
            logger.error(configProvider.protocolLabel() + " network refresh failed with code " + status);
        } catch (Throwable throwable) {
            logger.error(throwable);
            logger.error(configProvider.protocolLabel() + " network refresh failed");
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
            failureReason = null;
            knownAccountCount = Math.max(accountCount, maxKnownAccountCount);
            activeGeneration = lifecycleGeneration;
        }
        applyTunnelRouteForAccounts(knownAccountCount, TunnelRouteState.ACTIVE, activeGeneration);
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
        Runnable reconnect = () -> runReconnect(scheduleGeneration);
        pendingReconnect = reconnect;
        logger.error(configProvider.protocolLabel() + " reconnect scheduled in " + delayMs + " ms");
        return new ScheduledReconnect(reconnect, delayMs);
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
            generation = ++lifecycleGeneration;
            accountCount = Math.max(1, maxKnownAccountCount);
        }
        applyTunnelRouteForAccounts(accountCount, TunnelRouteState.BLOCKED, generation);
        startRuntime(generation, accountCount, true);
    }

    private Runnable clearPendingReconnectLocked(boolean resetBackoff) {
        Runnable reconnectToCancel = pendingReconnect;
        pendingReconnect = null;
        ++reconnectScheduleGeneration;
        if (resetBackoff) {
            nextReconnectDelayIndex = 0;
        }
        return reconnectToCancel;
    }

    private boolean isCurrentLifecycleOperation(long generation, RuntimeState expectedState) {
        synchronized (stateLock) {
            return generation == lifecycleGeneration
                    && runtimeState == expectedState
                    && configProvider.isEnabled();
        }
    }

    private void stopRuntimeSafely() {
        try {
            tunnelRuntime.stop();
        } catch (Throwable throwable) {
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
