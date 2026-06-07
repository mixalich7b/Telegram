package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;

import java.security.SecureRandom;
import java.util.Locale;

public final class WireGuardManager {

    private static final SecureRandom random = new SecureRandom();
    private static final WireGuardController controller = new WireGuardController(
            new WireGuardController.Config() {
                @Override
                public boolean isEnabled() {
                    return WireGuardConfig.ENABLED;
                }

                @Override
                public String validate() {
                    return WireGuardConfig.validate();
                }

                @Override
                public String buildUserspaceConfig() {
                    return WireGuardConfig.buildUserspaceConfig();
                }

                @Override
                public String[] localAddresses() {
                    return WireGuardConfig.LOCAL_ADDRESSES;
                }

                @Override
                public String[] dnsServers() {
                    return WireGuardConfig.DNS_SERVERS;
                }

                @Override
                public int mtu() {
                    return WireGuardConfig.MTU;
                }

                @Override
                public String socksHost() {
                    return WireGuardConfig.SOCKS_BIND_HOST;
                }

                @Override
                public int socksPort() {
                    return WireGuardConfig.SOCKS_BIND_PORT;
                }

                @Override
                public int blockedProxyPort() {
                    return WireGuardConfig.BLOCKED_PROXY_PORT;
                }
            },
            new WireGuardController.NativeRuntime() {
                @Override
                public int start(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu, String socksHost, int socksPort, String socksUsername, String socksPassword) {
                    System.loadLibrary(WireGuardConfig.NATIVE_LIBRARY_NAME);
                    return nativeStart(userspaceConfig, localAddresses, dnsServers, mtu, socksHost, socksPort, socksUsername, socksPassword);
                }

                @Override
                public int onNetworkChanged() {
                    return nativeOnNetworkChanged();
                }

                @Override
                public void stop() {
                    nativeStop();
                }
            },
            new WireGuardController.ProxySettingsSink() {
                @Override
                public void apply(int account, String host, int port, String username, String password, String secret) {
                    ConnectionsManager.native_setProxySettings(account, host, port, username, password, secret);
                }
            },
            new WireGuardController.TokenGenerator() {
                @Override
                public String nextToken(int bytes) {
                    return randomToken(bytes);
                }
            },
            new WireGuardController.Logger() {
                @Override
                public void debug(String message) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d(message);
                    }
                }

                @Override
                public void error(String message) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e(message);
                    }
                }

                @Override
                public void error(Throwable throwable) {
                    FileLog.e(throwable);
                }
            });

    private WireGuardManager() {
    }

    public static boolean isEnabled() {
        return controller.isEnabled();
    }

    public static void startIfEnabled() {
        controller.startIfEnabled();
    }

    public static boolean applyProxySettingsForAccount(int account) {
        return controller.applyProxySettingsForAccount(account);
    }

    public static boolean applyProxySettingsForAllAccounts() {
        return controller.applyProxySettingsForAllAccounts(UserConfig.MAX_ACCOUNT_COUNT);
    }

    public static WireGuardProxySettings getProxySettings() {
        return controller.getProxySettings();
    }

    public static void onNetworkChanged() {
        controller.onNetworkChanged(UserConfig.MAX_ACCOUNT_COUNT);
    }

    public static String getFailureReason() {
        return controller.getFailureReason();
    }

    private static String randomToken(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte value : data) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static native int nativeStart(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu, String socksHost, int socksPort, String socksUsername, String socksPassword);
    private static native int nativeOnNetworkChanged();
    private static native void nativeStop();
}
