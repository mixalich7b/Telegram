package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;

public final class TunnelManager {

    private static final String WIREGUARD_NATIVE_LIBRARY_NAME = "tg-wg";
    private static final String AMNEZIA_NATIVE_LIBRARY_NAME = "tg-awg";
    private static final RuntimeSelector runtimeSelector = new RuntimeSelector();
    private static final WireGuardController controller = new WireGuardController(
            new WireGuardController.Config() {
                @Override
                public boolean isEnabled() {
                    return WireGuardSettings.isEnabled();
                }

                @Override
                public TunnelProtocol protocol() {
                    WireGuardProfile profile = WireGuardSettings.getCurrentProfile();
                    return profile == null ? TunnelProtocol.WIREGUARD : profile.protocol;
                }

                @Override
                public String protocolLabel() {
                    return protocol().displayName();
                }

                @Override
                public String validate() {
                    WireGuardProfile profile = WireGuardSettings.getCurrentProfile();
                    return profile == null ? "missing active tunnel profile" : profile.validate();
                }

                @Override
                public String buildUserspaceConfig() {
                    WireGuardProfile profile = WireGuardSettings.getCurrentProfile();
                    return profile == null ? "" : profile.buildUserspaceConfig();
                }

                @Override
                public String[] localAddresses() {
                    WireGuardProfile profile = WireGuardSettings.getCurrentProfile();
                    return profile == null ? new String[0] : profile.localAddresses;
                }

                @Override
                public String[] dnsServers() {
                    WireGuardProfile profile = WireGuardSettings.getCurrentProfile();
                    return profile == null ? new String[0] : profile.dnsServers;
                }

                @Override
                public int mtu() {
                    WireGuardProfile profile = WireGuardSettings.getCurrentProfile();
                    return profile == null ? WireGuardProfile.DEFAULT_MTU : profile.mtu;
                }

            },
            runtimeSelector,
            new WireGuardController.TunnelStateSink() {
                @Override
                public void apply(int account, boolean enabled, boolean blocked) {
                    ConnectionsManager.native_setTunnelSettings(account, enabled, blocked);
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

    private TunnelManager() {
    }

    public static boolean isEnabled() {
        return controller.isEnabled();
    }

    public static boolean isSupported() {
        return WireGuardSettings.isSecureStorageSupported();
    }

    public static boolean isUserEnabled() {
        return WireGuardSettings.isEnabled();
    }

    public static boolean isVoipRoutingEnabled() {
        return WireGuardSettings.isVoipEnabled();
    }

    public static void setVoipRoutingEnabled(boolean enabled) {
        WireGuardSettings.setVoipEnabled(enabled);
    }

    public static TunnelProtocol getActiveProtocol() {
        WireGuardProfile profile = WireGuardSettings.getCurrentProfile();
        return profile == null ? TunnelProtocol.WIREGUARD : profile.protocol;
    }

    public static boolean hasActiveProfile() {
        return WireGuardSettings.getCurrentProfile() != null;
    }

    public static WireGuardProfile getActiveProfile() {
        return WireGuardSettings.getCurrentProfile();
    }

    public static ArrayList<WireGuardProfile> getProfiles() {
        return WireGuardSettings.getProfiles();
    }

    public static void startIfEnabled() {
        controller.startIfEnabled();
    }

    public static void enable(String profileId) {
        WireGuardSettings.setCurrentProfileId(profileId);
        WireGuardSettings.setEnabled(true);
        controller.restart(UserConfig.MAX_ACCOUNT_COUNT);
    }

    public static void disable() {
        WireGuardSettings.setEnabled(false);
        controller.disable(UserConfig.MAX_ACCOUNT_COUNT);
    }

    public static void selectProfile(String profileId) {
        WireGuardSettings.setCurrentProfileId(profileId);
        if (WireGuardSettings.isEnabled()) {
            controller.restart(UserConfig.MAX_ACCOUNT_COUNT);
        }
    }

    public static WireGuardProfile saveProfile(WireGuardProfile profile) {
        WireGuardProfile saved = WireGuardSettings.saveProfile(profile);
        if (WireGuardSettings.isEnabled() && saved.id.equals(WireGuardSettings.getCurrentProfileId())) {
            controller.restart(UserConfig.MAX_ACCOUNT_COUNT);
        }
        return saved;
    }

    public static void deleteProfile(String profileId) {
        boolean deletingActive = profileId != null && profileId.equals(WireGuardSettings.getCurrentProfileId());
        WireGuardSettings.deleteProfile(profileId);
        if (deletingActive) {
            controller.disable(UserConfig.MAX_ACCOUNT_COUNT);
        }
    }

    public static WireGuardProfile importProfile(String configText, String fallbackName) {
        return WireGuardSettings.importProfile(configText, fallbackName);
    }

    public static boolean applyProxySettingsForAccount(int account) {
        return controller.applyTunnelSettingsForAccount(account);
    }

    public static boolean applyTunnelSettingsForAccount(int account) {
        return controller.applyTunnelSettingsForAccount(account);
    }

    public static boolean applyProxySettingsForAllAccounts() {
        return controller.applyTunnelSettingsForAllAccounts(UserConfig.MAX_ACCOUNT_COUNT);
    }

    public static boolean applyTunnelSettingsForAllAccounts() {
        return controller.applyTunnelSettingsForAllAccounts(UserConfig.MAX_ACCOUNT_COUNT);
    }

    public static TunnelProxySettings getProxySettings() {
        return controller.getProxySettings();
    }

    public static TunnelProxySettings getVoipProxySettings() {
        return TunnelVoipRouting.selectProxySettings(isVoipRoutingEnabled(), getProxySettings());
    }

    public static void onNetworkChanged() {
        controller.onNetworkChanged(UserConfig.MAX_ACCOUNT_COUNT);
    }

    public static String getFailureReason() {
        return controller.getFailureReason();
    }

    private static final class RuntimeSelector implements WireGuardController.NativeRuntime {
        private TunnelProtocol activeProtocol;

        @Override
        public int start(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu) {
            WireGuardProfile profile = WireGuardSettings.getCurrentProfile();
            TunnelProtocol protocol = profile == null ? TunnelProtocol.WIREGUARD : profile.protocol;
            activeProtocol = protocol;
            if (protocol == TunnelProtocol.AMNEZIA_WG) {
                System.loadLibrary(AMNEZIA_NATIVE_LIBRARY_NAME);
                return nativeAmneziaWGStart(userspaceConfig, localAddresses, dnsServers, mtu);
            }
            System.loadLibrary(WIREGUARD_NATIVE_LIBRARY_NAME);
            return nativeWireGuardStart(userspaceConfig, localAddresses, dnsServers, mtu);
        }

        @Override
        public int onNetworkChanged() {
            if (activeProtocol == TunnelProtocol.AMNEZIA_WG) {
                return nativeAmneziaWGOnNetworkChanged();
            }
            return nativeWireGuardOnNetworkChanged();
        }

        @Override
        public void stop() {
            if (activeProtocol == TunnelProtocol.AMNEZIA_WG) {
                nativeAmneziaWGStop();
            } else if (activeProtocol == TunnelProtocol.WIREGUARD) {
                nativeWireGuardStop();
            }
            activeProtocol = null;
        }
    }

    private static native int nativeWireGuardStart(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu);
    private static native int nativeWireGuardOnNetworkChanged();
    private static native void nativeWireGuardStop();
    private static native int nativeAmneziaWGStart(String userspaceConfig, String[] localAddresses, String[] dnsServers, int mtu);
    private static native int nativeAmneziaWGOnNetworkChanged();
    private static native void nativeAmneziaWGStop();
}
