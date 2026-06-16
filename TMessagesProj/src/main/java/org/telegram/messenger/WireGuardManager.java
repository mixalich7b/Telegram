package org.telegram.messenger;

public final class WireGuardManager {

    private WireGuardManager() {
    }

    public static boolean isEnabled() {
        return TunnelManager.isEnabled();
    }

    public static boolean isSupported() {
        return WireGuardSettings.isSecureStorageSupported() && TunnelManager.isSupported();
    }

    public static boolean isUserEnabled() {
        return TunnelManager.isUserEnabled();
    }

    public static boolean hasActiveProfile() {
        return TunnelManager.hasActiveProfile();
    }

    public static WireGuardProfile getActiveProfile() {
        return TunnelManager.getActiveProfile();
    }

    public static java.util.ArrayList<WireGuardProfile> getProfiles() {
        return TunnelManager.getProfiles();
    }

    public static void startIfEnabled() {
        TunnelManager.startIfEnabled();
    }

    public static void enable(String profileId) {
        TunnelManager.enable(profileId);
    }

    public static void disable() {
        TunnelManager.disable();
    }

    public static void selectProfile(String profileId) {
        TunnelManager.selectProfile(profileId);
    }

    public static WireGuardProfile saveProfile(WireGuardProfile profile) {
        return TunnelManager.saveProfile(profile);
    }

    public static void deleteProfile(String profileId) {
        TunnelManager.deleteProfile(profileId);
    }

    public static WireGuardProfile importProfile(String configText, String fallbackName) {
        return TunnelManager.importProfile(configText, fallbackName);
    }

    public static boolean applyProxySettingsForAccount(int account) {
        return TunnelManager.applyProxySettingsForAccount(account);
    }

    public static boolean applyProxySettingsForAllAccounts() {
        return TunnelManager.applyProxySettingsForAllAccounts();
    }

    public static WireGuardProxySettings getProxySettings() {
        return WireGuardProxySettings.from(TunnelManager.getProxySettings());
    }

    public static void onNetworkChanged() {
        TunnelManager.onNetworkChanged();
    }

    public static String getFailureReason() {
        return TunnelManager.getFailureReason();
    }
}
