package org.telegram.messenger;

final class WireGuardController {

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

    private final Object lock = new Object();
    private final Config config;
    private final NativeRuntime nativeRuntime;
    private final TunnelStateSink tunnelStateSink;
    private final Logger logger;

    private boolean startAttempted;
    private boolean running;
    private String failureReason;

    WireGuardController(Config config, NativeRuntime nativeRuntime, TunnelStateSink tunnelStateSink, Logger logger) {
        this.config = config;
        this.nativeRuntime = nativeRuntime;
        this.tunnelStateSink = tunnelStateSink;
        this.logger = logger;
    }

    boolean isEnabled() {
        return config.isEnabled();
    }

    void startIfEnabled() {
        if (!config.isEnabled()) {
            return;
        }
        synchronized (lock) {
            startLocked();
        }
    }

    boolean applyTunnelSettingsForAccount(int account) {
        if (!config.isEnabled()) {
            return false;
        }
        startIfEnabled();
        synchronized (lock) {
            applyTunnelSettingsLocked(account);
            return true;
        }
    }

    boolean applyTunnelSettingsForAllAccounts(int accountCount) {
        if (!config.isEnabled()) {
            return false;
        }
        synchronized (lock) {
            startLocked();
            applyTunnelSettingsForAccountsLocked(accountCount);
            return true;
        }
    }

    TunnelProxySettings getProxySettings() {
        if (!config.isEnabled()) {
            return null;
        }
        synchronized (lock) {
            startLocked();
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

            startLocked();
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
            applyBlockedTunnelForAccountsLocked(accountCount);
            stopRuntimeLocked();
            startLocked();
            applyTunnelSettingsForAccountsLocked(accountCount);
        }
    }

    void disable(int accountCount) {
        synchronized (lock) {
            stopRuntimeLocked();
            failureReason = null;
            applyDirectProxyForAccountsLocked(accountCount);
        }
    }

    boolean isRunningForTests() {
        synchronized (lock) {
            return running;
        }
    }

    private void markFailed(String reason, Throwable throwable) {
        running = false;
        failureReason = reason;
        if (throwable != null) {
            logger.error(throwable);
        }
        logger.error(config.protocolLabel() + " runtime disabled: " + reason);
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

    private void startLocked() {
        if (startAttempted) {
            return;
        }
        startAttempted = true;

        String validationError = config.validate();
        if (validationError != null) {
            markFailed(validationError, null);
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
                markFailed("nativeStart returned error " + status, null);
                return;
            }
            running = true;
            failureReason = null;
            logger.debug(config.protocolLabel() + " runtime started");
        } catch (Throwable e) {
            markFailed("unable to start " + config.protocolLabel() + " runtime", e);
        }
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
