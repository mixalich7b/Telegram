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
    public void disabledConfigDoesNotStartOrApplyProxy() {
        FakeConfig config = new FakeConfig();
        config.enabled = false;
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeProxySink proxySink = new FakeProxySink();

        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        assertFalse(controller.applyProxySettingsForAccount(0));
        assertEquals(0, nativeRuntime.startCalls);
        assertEquals(0, proxySink.calls.size());
        assertNull(controller.getFailureReason());
    }

    @Test
    public void successfulStartAppliesInternalSocksProxy() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        nativeRuntime.port = 39001;
        FakeProxySink proxySink = new FakeProxySink();

        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        assertTrue(controller.applyProxySettingsForAccount(2));

        assertEquals(1, nativeRuntime.startCalls);
        assertTrue(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertEquals(1, proxySink.calls.size());
        ProxyCall call = proxySink.calls.get(0);
        assertEquals(2, call.account);
        assertEquals("127.0.0.1", call.host);
        assertEquals(39001, call.port);
        assertEquals("tg-wg-token8", call.username);
        assertEquals("token24", call.password);
        assertEquals("", call.secret);
    }

    @Test
    public void nativeFailureAppliesBlockedProxy() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        nativeRuntime.failure = new RuntimeException("boom");
        FakeProxySink proxySink = new FakeProxySink();

        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        assertTrue(controller.applyProxySettingsForAccount(1));

        assertEquals(1, nativeRuntime.startCalls);
        assertFalse(controller.isRunningForTests());
        assertEquals("unable to start WireGuard runtime", controller.getFailureReason());
        assertEquals(1, proxySink.calls.size());
        ProxyCall call = proxySink.calls.get(0);
        assertEquals("127.0.0.1", call.host);
        assertEquals(1, call.port);
        assertEquals("", call.username);
        assertEquals("", call.password);
    }

    @Test
    public void invalidNativePortAppliesBlockedProxy() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        nativeRuntime.port = 0;
        FakeProxySink proxySink = new FakeProxySink();

        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        assertTrue(controller.applyProxySettingsForAccount(1));

        assertEquals("nativeStart returned invalid port 0", controller.getFailureReason());
        assertEquals(1, proxySink.calls.get(0).port);
    }

    @Test
    public void validationFailureDoesNotStartNativeRuntime() {
        FakeConfig config = new FakeConfig();
        config.validationError = "missing WireGuard config values: [PRIVATE_KEY]";
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeProxySink proxySink = new FakeProxySink();

        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        assertTrue(controller.applyProxySettingsForAccount(1));

        assertEquals(0, nativeRuntime.startCalls);
        assertEquals(config.validationError, controller.getFailureReason());
        assertEquals(1, proxySink.calls.get(0).port);
    }

    @Test
    public void startIsAttemptedOnlyOnce() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeProxySink proxySink = new FakeProxySink();

        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        assertTrue(controller.applyProxySettingsForAccount(1));
        assertTrue(controller.applyProxySettingsForAccount(1));

        assertEquals(1, nativeRuntime.startCalls);
        assertEquals(2, proxySink.calls.size());
        assertEquals(23456, proxySink.calls.get(0).port);
        assertEquals(23456, proxySink.calls.get(1).port);
    }

    @Test
    public void applyProxySettingsForAllAccountsUsesInternalSocks() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        nativeRuntime.port = 39001;
        FakeProxySink proxySink = new FakeProxySink();
        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        assertTrue(controller.applyProxySettingsForAllAccounts(3));

        assertEquals(1, nativeRuntime.startCalls);
        assertEquals(3, proxySink.calls.size());
        for (int account = 0; account < 3; account++) {
            ProxyCall call = proxySink.calls.get(account);
            assertEquals(account, call.account);
            assertEquals(39001, call.port);
            assertEquals("tg-wg-token8", call.username);
            assertEquals("token24", call.password);
        }
    }

    @Test
    public void getProxySettingsReturnsBlockedEndpointWhenStartupFails() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        nativeRuntime.port = 0;
        FakeProxySink proxySink = new FakeProxySink();
        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        WireGuardProxySettings proxySettings = controller.getProxySettings();

        assertTrue(proxySettings.blocked);
        assertEquals("127.0.0.1", proxySettings.host);
        assertEquals(1, proxySettings.port);
        assertEquals("", proxySettings.username);
        assertEquals("", proxySettings.password);
    }

    @Test
    public void networkChangedIsForwardedOnlyWhenRunning() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeProxySink proxySink = new FakeProxySink();
        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        controller.onNetworkChanged(2);
        assertEquals(0, nativeRuntime.networkChangedCalls);

        controller.applyProxySettingsForAccount(1);
        controller.onNetworkChanged(2);
        assertEquals(1, nativeRuntime.networkChangedCalls);
    }

    @Test
    public void networkChangedFailureRestartsRuntimeAndReappliesProxy() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeProxySink proxySink = new FakeProxySink();
        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        controller.applyProxySettingsForAccount(1);
        nativeRuntime.networkChangedStatus = -9;
        nativeRuntime.port = 39002;

        controller.onNetworkChanged(2);

        assertEquals(1, nativeRuntime.networkChangedCalls);
        assertEquals(1, nativeRuntime.stopCalls);
        assertEquals(2, nativeRuntime.startCalls);
        assertTrue(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertEquals(3, proxySink.calls.size());
        assertEquals(39002, proxySink.calls.get(1).port);
        assertEquals(39002, proxySink.calls.get(2).port);
        assertEquals(0, proxySink.calls.get(1).account);
        assertEquals(1, proxySink.calls.get(2).account);
    }

    @Test
    public void networkChangedRestartFailureAppliesBlockedProxy() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeProxySink proxySink = new FakeProxySink();
        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        controller.applyProxySettingsForAccount(1);
        nativeRuntime.networkChangedStatus = -1;
        nativeRuntime.port = 0;

        controller.onNetworkChanged(2);

        assertEquals(1, nativeRuntime.networkChangedCalls);
        assertEquals(1, nativeRuntime.stopCalls);
        assertEquals(2, nativeRuntime.startCalls);
        assertFalse(controller.isRunningForTests());
        assertEquals("nativeStart returned invalid port 0", controller.getFailureReason());
        assertEquals(1, proxySink.calls.get(1).port);
        assertEquals(1, proxySink.calls.get(2).port);
        assertEquals("", proxySink.calls.get(1).username);
        assertEquals("", proxySink.calls.get(2).password);
    }

    @Test
    public void restartAppliesBlockedProxyBeforeStartingNewRuntime() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeProxySink proxySink = new FakeProxySink();
        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        controller.applyProxySettingsForAccount(1);
        nativeRuntime.port = 39002;
        controller.restart(2);

        assertEquals(2, nativeRuntime.startCalls);
        assertEquals(1, nativeRuntime.stopCalls);
        assertEquals(5, proxySink.calls.size());
        assertEquals(23456, proxySink.calls.get(0).port);
        assertEquals(1, proxySink.calls.get(1).port);
        assertEquals(1, proxySink.calls.get(2).port);
        assertEquals(39002, proxySink.calls.get(3).port);
        assertEquals(39002, proxySink.calls.get(4).port);
    }

    @Test
    public void disableStopsRuntimeAndClearsNativeProxy() {
        FakeConfig config = new FakeConfig();
        FakeNativeRuntime nativeRuntime = new FakeNativeRuntime();
        FakeProxySink proxySink = new FakeProxySink();
        WireGuardController controller = newController(config, nativeRuntime, proxySink);

        controller.applyProxySettingsForAccount(1);
        controller.disable(2);

        assertEquals(1, nativeRuntime.startCalls);
        assertEquals(1, nativeRuntime.stopCalls);
        assertFalse(controller.isRunningForTests());
        assertNull(controller.getFailureReason());
        assertEquals(3, proxySink.calls.size());
        assertEquals("", proxySink.calls.get(1).host);
        assertEquals(1080, proxySink.calls.get(1).port);
        assertEquals("", proxySink.calls.get(2).host);
        assertEquals(1080, proxySink.calls.get(2).port);
    }

    private static WireGuardController newController(FakeConfig config, FakeNativeRuntime nativeRuntime, FakeProxySink proxySink) {
        return new WireGuardController(
                config,
                nativeRuntime,
                proxySink,
                new WireGuardController.TokenGenerator() {
                    @Override
                    public String nextToken(int bytes) {
                        return "token" + bytes;
                    }
                },
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

        @Override
        public boolean isEnabled() {
            return enabled;
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

        @Override
        public String socksHost() {
            return "127.0.0.1";
        }

        @Override
        public int socksPort() {
            return 0;
        }

        @Override
        public int blockedProxyPort() {
            return 1;
        }
    }

    private static final class FakeNativeRuntime implements WireGuardController.NativeRuntime {
        int startCalls;
        int networkChangedCalls;
        int stopCalls;
        int port = 23456;
        int networkChangedStatus = 0;
        RuntimeException failure;

        @Override
        public int start(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu, String socksHost, int socksPort, String socksUsername, String socksPassword) {
            startCalls++;
            if (failure != null) {
                throw failure;
            }
            return port;
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

    private static final class FakeProxySink implements WireGuardController.ProxySettingsSink {
        final List<ProxyCall> calls = new ArrayList<>();

        @Override
        public void apply(int account, String host, int port, String username, String password, String secret) {
            calls.add(new ProxyCall(account, host, port, username, password, secret));
        }
    }

    private static final class ProxyCall {
        final int account;
        final String host;
        final int port;
        final String username;
        final String password;
        final String secret;

        ProxyCall(int account, String host, int port, String username, String password, String secret) {
            this.account = account;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.secret = secret;
        }
    }
}
