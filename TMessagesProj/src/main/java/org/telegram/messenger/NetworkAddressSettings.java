package org.telegram.messenger;

import android.content.SharedPreferences;
import org.telegram.tgnet.ConnectionsManager;

/** Process-wide connection policy. WEB proxy owns its networking and is exempt. */
public final class NetworkAddressSettings {
    private static final String IPV6_ENABLED = "connection_ipv6_enabled";
    private static IpVersionPolicy policy;

    private NetworkAddressSettings() { }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", 0);
    }

    // Must run after NativeLoader and before starting any account or tunnel.
    public static synchronized void initialize() {
        if (policy == null) {
            policy = new IpVersionPolicy(preferences().getBoolean(IPV6_ENABLED, true));
            ConnectionsManager.native_setIpv6Enabled(policy.isEnabled());
            logPolicy("startup");
        }
    }

    public static synchronized boolean isIpv6Enabled() {
        initialize();
        return policy.isEnabled();
    }

    public static synchronized boolean isIpv6Requested() {
        initialize();
        return policy.isRequested();
    }

    public static synchronized void setIpv6Enabled(boolean enabled) {
        initialize();
        preferences().edit().putBoolean(IPV6_ENABLED, enabled).apply();
        boolean changed = policy.request(enabled);
        logPolicy("requested");
        if (changed) applyEffectivePolicy();
    }

    public static synchronized void beginVoipSession() {
        initialize();
        policy.beginSession();
    }

    public static void endVoipSession() {
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (NetworkAddressSettings.class) {
                boolean changed = policy.endSession();
                if (changed) {
                    logPolicy("last_session_ended");
                    applyEffectivePolicy();
                }
            }
        });
    }

    private static void applyEffectivePolicy() {
        ConnectionsManager.native_setIpv6Enabled(policy.isEnabled());
        logPolicy("applied");
        // TunnelController serializes restart and blocks the route until ready.
        TunnelManager.onIpVersionPolicyChanged();
        ConnectionsManager.onIpVersionPolicyChanged();
    }

    private static void logPolicy(String reason) {
        if (BuildConfig.TG_TUNNEL_LOGS_ENABLED) {
            TunnelLog.d("tunnel_trace event=ipv6_policy reason=" + reason
                    + " requested=" + policy.isRequested() + " effective=" + policy.isEnabled()
                    + " deferred=" + (policy.isRequested() != policy.isEnabled()));
        }
    }
}
