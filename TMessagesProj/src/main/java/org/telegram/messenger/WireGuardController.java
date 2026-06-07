package org.telegram.messenger;

final class WireGuardController {

    interface Config {
        boolean isEnabled();

        String validate();

        String buildUserspaceConfig();

        String[] localAddresses();

        String[] dnsServers();

        int mtu();

        String socksHost();

        int socksPort();

        int blockedProxyPort();
    }

    interface NativeRuntime {
        int start(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu, String socksHost, int socksPort, String socksUsername, String socksPassword);

        int onNetworkChanged();

        void stop();
    }

    interface ProxySettingsSink {
        void apply(int account, String host, int port, String username, String password, String secret);
    }

    interface TokenGenerator {
        String nextToken(int bytes);
    }

    interface Logger {
        void debug(String message);

        void error(String message);

        void error(Throwable throwable);
    }

    private final Object lock = new Object();
    private final Config config;
    private final NativeRuntime nativeRuntime;
    private final ProxySettingsSink proxySettingsSink;
    private final TokenGenerator tokenGenerator;
    private final Logger logger;

    private boolean startAttempted;
    private boolean running;
    private int socksPort;
    private String socksUsername;
    private String socksPassword;
    private String failureReason;

    WireGuardController(Config config, NativeRuntime nativeRuntime, ProxySettingsSink proxySettingsSink, TokenGenerator tokenGenerator, Logger logger) {
        this.config = config;
        this.nativeRuntime = nativeRuntime;
        this.proxySettingsSink = proxySettingsSink;
        this.tokenGenerator = tokenGenerator;
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

    boolean applyProxySettingsForAccount(int account) {
        if (!config.isEnabled()) {
            return false;
        }
        startIfEnabled();
        synchronized (lock) {
            applyProxySettingsLocked(account);
            return true;
        }
    }

    boolean applyProxySettingsForAllAccounts(int accountCount) {
        if (!config.isEnabled()) {
            return false;
        }
        synchronized (lock) {
            startLocked();
            applyProxySettingsForAccountsLocked(accountCount);
            return true;
        }
    }

    WireGuardProxySettings getProxySettings() {
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
                logger.error("WireGuard network refresh failed with code " + status);
            } catch (Throwable e) {
                logger.error(e);
                logger.error("WireGuard network refresh failed");
            }

            try {
                nativeRuntime.stop();
            } catch (Throwable e) {
                logger.error(e);
            }
            running = false;
            socksPort = 0;
            startAttempted = false;
            failureReason = "WireGuard network refresh failed";

            startLocked();
            applyProxySettingsForAccountsLocked(accountCount);
        }
    }

    String getFailureReason() {
        synchronized (lock) {
            return failureReason;
        }
    }

    boolean isRunningForTests() {
        synchronized (lock) {
            return running;
        }
    }

    private void markFailed(String reason, Throwable throwable) {
        running = false;
        socksPort = 0;
        failureReason = reason;
        if (throwable != null) {
            logger.error(throwable);
        }
        logger.error("WireGuard runtime disabled: " + reason);
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

        socksUsername = "tg-wg-" + tokenGenerator.nextToken(8);
        socksPassword = tokenGenerator.nextToken(24);

        try {
            int port = nativeRuntime.start(
                    config.buildUserspaceConfig(),
                    config.localAddresses(),
                    config.dnsServers(),
                    config.mtu(),
                    config.socksHost(),
                    config.socksPort(),
                    socksUsername,
                    socksPassword
            );
            if (port <= 0 || port > 65535) {
                markFailed("nativeStart returned invalid port " + port, null);
                return;
            }
            socksPort = port;
            running = true;
            failureReason = null;
            logger.debug("WireGuard runtime started on " + config.socksHost() + ":" + socksPort);
        } catch (Throwable e) {
            markFailed("unable to start WireGuard runtime", e);
        }
    }

    private void applyProxySettingsForAccountsLocked(int accountCount) {
        for (int account = 0; account < accountCount; account++) {
            applyProxySettingsLocked(account);
        }
    }

    private void applyProxySettingsLocked(int account) {
        WireGuardProxySettings proxySettings = buildProxySettingsLocked();
        proxySettingsSink.apply(account, proxySettings.host, proxySettings.port, proxySettings.username, proxySettings.password, "");
    }

    private WireGuardProxySettings buildProxySettingsLocked() {
        if (running) {
            return new WireGuardProxySettings(config.socksHost(), socksPort, socksUsername, socksPassword, false);
        }
        return new WireGuardProxySettings(config.socksHost(), config.blockedProxyPort(), "", "", true);
    }
}
