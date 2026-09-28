package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.utils.proxy.ProxySettings;
import org.telegram.utils.proxy.WebProxyTransport;
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

        TunnelManager.disable();

        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", true);
        proxyInfo.settings.toSharedPreferences(editor);
        if (proxyInfo.settings.getType() != ProxySettings.Type.SOCKS5) {
            editor.putBoolean("proxy_enabled_calls", false);
        }
        editor.commit();

        ConnectionsManager.setProxySettings(true, proxyInfo.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    public static void disableProxy() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.putBoolean("proxy_enabled_calls", false);
        editor.commit();

        ConnectionsManager.setProxySettings(false, null);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    public static boolean enableWireGuard(String profileId) {
        return enableTunnel(profileId);
    }

    public static boolean enableTunnel(String profileId) {
        if (!TunnelManager.isSupported()) {
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

        TunnelManager.enable(profileId);
        // Stop the ordinary proxy carrier without clearing the native tunnel route.
        WebProxyTransport.stop();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        return true;
    }

    public static void disableWireGuard() {
        disableTunnel();
    }

    public static void disableTunnel() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.putBoolean("proxy_enabled_calls", false);
        editor.commit();

        if (SharedConfig.proxyRotationEnabled) {
            SharedConfig.proxyRotationEnabled = false;
            SharedConfig.saveConfig();
        }

        TunnelManager.disable();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    public static void setTunnelVoipEnabled(boolean enabled) {
        TunnelManager.setVoipRoutingEnabled(enabled);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }
}
