package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;

public final class NetworkRouteSettings {

    private NetworkRouteSettings() {
    }

    public static void enableProxy(SharedConfig.ProxyInfo proxyInfo) {
        if (proxyInfo == null) {
            return;
        }
        proxyInfo = SharedConfig.addProxy(proxyInfo);
        SharedConfig.currentProxy = proxyInfo;

        WireGuardManager.disable();

        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", true);
        editor.putString("proxy_ip", proxyInfo.address);
        editor.putString("proxy_pass", proxyInfo.password);
        editor.putString("proxy_user", proxyInfo.username);
        editor.putInt("proxy_port", proxyInfo.port);
        editor.putString("proxy_secret", proxyInfo.secret);
        if (!proxyInfo.secret.isEmpty()) {
            editor.putBoolean("proxy_enabled_calls", false);
        }
        editor.commit();

        ConnectionsManager.setProxySettings(true, proxyInfo.address, proxyInfo.port, proxyInfo.username, proxyInfo.password, proxyInfo.secret);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    public static void disableProxy() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.putBoolean("proxy_enabled_calls", false);
        editor.commit();

        ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    public static boolean enableWireGuard(String profileId) {
        if (!WireGuardManager.isBuildSupported()) {
            return false;
        }

        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.putBoolean("proxy_enabled_calls", false);
        editor.commit();

        if (SharedConfig.proxyRotationEnabled) {
            SharedConfig.proxyRotationEnabled = false;
            SharedConfig.saveConfig();
        }

        WireGuardManager.enable(profileId);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        return true;
    }

    public static void disableWireGuard() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.putBoolean("proxy_enabled_calls", false);
        editor.commit();

        if (SharedConfig.proxyRotationEnabled) {
            SharedConfig.proxyRotationEnabled = false;
            SharedConfig.saveConfig();
        }

        WireGuardManager.disable();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }
}
