package org.telegram.messenger;

final class WireGuardController {
    private static final long[] RETRY_DELAYS_MS = new long[]{
            1000L,
            2000L,
            2000L,
            3000L,
            5000L,
            10000L,
            10000L,
            10000L
    };

    interface Config {
        boolean isEnabled();

        TunnelProtocol protocol();

        String protocolLabel();

        String validate();

        String buildUserspaceConfig();

        String[] localAddresses();

        String[] dnsServers();

        int mtu();
    }

    interface NativeRuntime {
        int start(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu);

        int onNetworkChanged();

        void stop();
    }

    interface TunnelStateSink {
        void apply(int account, boolean enabled, boolean blocked);
    }

    interface Logger {
        void debug(String message);

        void error(String message);

        void error(Throwable throwable);
    }

    interface RetryScheduler {
        void schedule(Runnable runnable, long delayMs);

        void cancel(Runnable runnable);
    }

    private final Object lock = new Object();
    private final Config config;
    private final NativeRuntime nativeRuntime;
    private final TunnelStateSink tunnelStateSink;
    private final Logger logger;
    private final RetryScheduler retryScheduler;

    private boolean startAttempted;
    private boolean running;
    private String failureReason;
    private Runnable retryRunnable;
    private int retryDelayIndex;
    private int retryGeneration;
    private int retryAccountCount;
    private int lastAccountCount;

    WireGuardController(Config config, NativeRuntime nativeRuntime, TunnelStateSink tunnelStateSink, Logger logger) {
        this(
                config,
                nativeRuntime,
                tunnelStateSink,
                logger,
                new RetryScheduler() {
                    @Override
                    public void schedule(Runnable runnable, long delayMs) {
                        AndroidUtilities.runOnUIThread(runnable, delayMs);
                    }

                    @Override
                    public void cancel(Runnable runnable) {
                        AndroidUtilities.cancelRunOnUIThread(runnable);
                    }
                });
    }

    WireGuardController(Config config, NativeRuntime nativeRuntime, TunnelStateSink tunnelStateSink, Logger logger, RetryScheduler retryScheduler) {
        this.config = config;
        this.nativeRuntime = nativeRuntime;
        this.tunnelStateSink = tunnelStateSink;
        this.logger = logger;
        this.retryScheduler = retryScheduler;
    }

    boolean isEnabled() {
        return config.isEnabled();
    }

    void startIfEnabled(int accountCount) {
        if (!config.isEnabled()) {
            return;
        }
        synchronized (lock) {
            rememberAccountCountLocked(accountCount);
            startLocked(accountCount);
            applyTunnelSettingsForAccountsLocked(accountCount);
        }
    }

    boolean applyTunnelSettingsForAccount(int account) {
        return applyTunnelSettingsForAccount(account, account + 1);
    }

    boolean applyTunnelSettingsForAccount(int account, int accountCount) {
        if (!config.isEnabled()) {
            return false;
        }
        synchronized (lock) {
            rememberAccountCountLocked(accountCount);
            startLocked(accountCount);
            applyTunnelSettingsLocked(account);
            return true;
        }
    }

    boolean applyTunnelSettingsForAllAccounts(int accountCount) {
        if (!config.isEnabled()) {
            return false;
        }
        synchronized (lock) {
            rememberAccountCountLocked(accountCount);
            startLocked(accountCount);
            applyTunnelSettingsForAccountsLocked(accountCount);
            return true;
        }
    }

    TunnelProxySettings getProxySettings() {
        return getProxySettings(Math.max(1, lastAccountCount));
    }

    TunnelProxySettings getProxySettings(int accountCount) {
        if (!config.isEnabled()) {
            return null;
        }
        synchronized (lock) {
            rememberAccountCountLocked(accountCount);
            startLocked(accountCount);
            return buildProxySettingsLocked();
        }
    }

    void onNetworkChanged(int accountCount) {
        if (!config.isEnabled()) {
            return;
        }
        synchronized (lock) {
            if (!running) {
                return;
            }
            try {
                int status = nativeRuntime.onNetworkChanged();
                if (status >= 0) {
                    return;
                }
                logger.error(config.protocolLabel() + " network refresh failed with code " + status);
            } catch (Throwable e) {
                logger.error(e);
                logger.error(config.protocolLabel() + " network refresh failed");
            }

            try {
                nativeRuntime.stop();
            } catch (Throwable e) {
                logger.error(e);
            }
            running = false;
            startAttempted = false;
            failureReason = config.protocolLabel() + " network refresh failed";

            startLocked(accountCount);
            applyTunnelSettingsForAccountsLocked(accountCount);
        }
    }

    String getFailureReason() {
        synchronized (lock) {
            return failureReason;
        }
    }

    void restart(int accountCount) {
        if (!config.isEnabled()) {
            disable(accountCount);
            return;
        }
        synchronized (lock) {
            cancelRetryLocked(true);
            rememberAccountCountLocked(accountCount);
            applyBlockedTunnelForAccountsLocked(accountCount);
            stopRuntimeLocked();
            startLocked(accountCount);
            applyTunnelSettingsForAccountsLocked(accountCount);
        }
    }

    void disable(int accountCount) {
        synchronized (lock) {
            cancelRetryLocked(true);
            stopRuntimeLocked();
            failureReason = null;
            retryAccountCount = 0;
            lastAccountCount = 0;
            applyDirectProxyForAccountsLocked(accountCount);
        }
    }

    void onTunnelConnectionFailure(int accountCount) {
        if (!config.isEnabled()) {
            return;
        }
        synchronized (lock) {
            rememberAccountCountLocked(accountCount);
            if (!running) {
                return;
            }
            String reason = config.protocolLabel() + " connection failed";
            failureReason = reason;
            logger.error(config.protocolLabel() + " runtime failed: " + reason);
            applyBlockedTunnelForAccountsLocked(accountCount);
            stopRuntimeLocked();
            scheduleRetryLocked(accountCount);
        }
    }

    boolean isRunningForTests() {
        synchronized (lock) {
            return running;
        }
    }

    private void markFailed(String reason, Throwable throwable, boolean retryable, int accountCount) {
        running = false;
        failureReason = reason;
        if (throwable != null) {
            logger.error(throwable);
        }
        logger.error(config.protocolLabel() + " runtime failed: " + reason);
        if (retryable) {
            scheduleRetryLocked(accountCount);
        }
    }

    private void stopRuntimeLocked() {
        if (running || startAttempted) {
            try {
                nativeRuntime.stop();
            } catch (Throwable e) {
                logger.error(e);
            }
        }
        running = false;
        startAttempted = false;
    }

    private void startLocked(int accountCount) {
        if (startAttempted || retryRunnable != null) {
            return;
        }
        startAttempted = true;

        String validationError = config.validate();
        if (validationError != null) {
            markFailed(validationError, null, false, accountCount);
            return;
        }

        try {
            int status = nativeRuntime.start(
                    config.buildUserspaceConfig(),
                    config.localAddresses(),
                    config.dnsServers(),
                    config.mtu()
            );
            if (status < 0) {
                markFailed("nativeStart returned error " + status, null, true, accountCount);
                return;
            }
            running = true;
            failureReason = null;
            cancelRetryLocked(true);
            logger.debug(config.protocolLabel() + " runtime started");
        } catch (Throwable e) {
            markFailed("unable to start " + config.protocolLabel() + " runtime", e, true, accountCount);
        }
    }

    private void scheduleRetryLocked(int accountCount) {
        if (retryRunnable != null || !config.isEnabled()) {
            return;
        }
        rememberAccountCountLocked(accountCount);
        retryAccountCount = Math.max(retryAccountCount, Math.max(1, accountCount));
        long delayMs = RETRY_DELAYS_MS[retryDelayIndex];
        retryDelayIndex = (retryDelayIndex + 1) % RETRY_DELAYS_MS.length;
        int generation = ++retryGeneration;
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                retryNow(generation);
            }
        };
        retryRunnable = runnable;
        logger.error(config.protocolLabel() + " reconnect scheduled in " + delayMs + " ms");
        retryScheduler.schedule(runnable, delayMs);
    }

    private void retryNow(int generation) {
        synchronized (lock) {
            if (generation != retryGeneration || retryRunnable == null) {
                return;
            }
            retryRunnable = null;
            if (!config.isEnabled()) {
                return;
            }
            int accountCount = Math.max(1, retryAccountCount);
            applyBlockedTunnelForAccountsLocked(accountCount);
            stopRuntimeLocked();
            startLocked(accountCount);
            applyTunnelSettingsForAccountsLocked(accountCount);
        }
    }

    private void cancelRetryLocked(boolean resetBackoff) {
        if (retryRunnable != null) {
            retryScheduler.cancel(retryRunnable);
            retryRunnable = null;
        }
        retryGeneration++;
        retryAccountCount = 0;
        if (resetBackoff) {
            retryDelayIndex = 0;
        }
    }

    private void rememberAccountCountLocked(int accountCount) {
        lastAccountCount = Math.max(lastAccountCount, Math.max(1, accountCount));
        retryAccountCount = Math.max(retryAccountCount, lastAccountCount);
    }

    private void applyTunnelSettingsForAccountsLocked(int accountCount) {
        for (int account = 0; account < accountCount; account++) {
            applyTunnelSettingsLocked(account);
        }
    }

    private void applyBlockedTunnelForAccountsLocked(int accountCount) {
        for (int account = 0; account < accountCount; account++) {
            tunnelStateSink.apply(account, true, true);
        }
    }

    private void applyDirectProxyForAccountsLocked(int accountCount) {
        for (int account = 0; account < accountCount; account++) {
            tunnelStateSink.apply(account, false, false);
        }
    }

    private void applyTunnelSettingsLocked(int account) {
        tunnelStateSink.apply(account, true, !running);
    }

    private TunnelProxySettings buildProxySettingsLocked() {
        if (running) {
            return new TunnelProxySettings(config.protocol(), "", 0, "", "", false);
        }
        return new TunnelProxySettings(config.protocol(), "", 0, "", "", true);
    }
}
