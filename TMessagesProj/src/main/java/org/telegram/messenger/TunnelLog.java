package org.telegram.messenger;

import android.util.Log;

/** Tunnel diagnostics have their own build switch; never use the app-wide FileLog gate. */
public final class TunnelLog {
    private static final String TAG = "Telegram/Tunnel";

    private TunnelLog() { }

    public static void d(String message) {
        if (BuildConfig.TG_TUNNEL_LOGS_ENABLED) {
            Log.d(TAG, message);
        }
    }

    public static void e(String message) {
        if (BuildConfig.TG_TUNNEL_LOGS_ENABLED) {
            Log.e(TAG, message);
        }
    }

    public static void e(Throwable throwable) {
        // Exception messages may include profile contents. Keep only the type.
        e("tunnel_trace event=controller_exception error_type=" + throwable.getClass().getSimpleName());
    }
}
