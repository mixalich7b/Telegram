package org.telegram.messenger;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

/** Tunnel diagnostics have their own build switch and a shared native file worker. */
public final class TunnelLog {
    private static final String TAG = "Telegram/Tunnel";
    private static volatile boolean initialized;
    private static boolean libraryLoaded;
    private static boolean initErrorReported;

    private TunnelLog() { }

    public static boolean isEnabled() {
        return BuildConfig.TG_TUNNEL_LOGS_ENABLED;
    }

    public static synchronized boolean init() {
        if (!BuildConfig.TG_TUNNEL_LOGS_ENABLED || ApplicationLoader.applicationContext == null) {
            return false;
        }
        if (initialized) {
            return true;
        }
        try {
            if (!libraryLoaded) {
                System.loadLibrary("tg-tunnel-log");
                libraryLoaded = true;
            }
            File directory = AndroidUtilities.getLogsDir();
            if (directory == null || !directory.isDirectory()) {
                return false;
            }
            initialized = nativeInit(directory.getAbsolutePath());
            return initialized;
        } catch (Throwable error) {
            if (!initErrorReported) {
                initErrorReported = true;
                Log.e(TAG, "tunnel file log initialization failed error_type=" + error.getClass().getSimpleName());
            }
            return false;
        }
    }

    public static void d(String message) {
        if (BuildConfig.TG_TUNNEL_LOGS_ENABLED) {
            Log.d(TAG, message);
            if (initialized || init()) {
                nativeWriteFile(Log.DEBUG, message);
            }
        }
    }

    public static void e(String message) {
        if (BuildConfig.TG_TUNNEL_LOGS_ENABLED) {
            Log.e(TAG, message);
            if (initialized || init()) {
                nativeWriteFile(Log.ERROR, message);
            }
        }
    }

    public static void e(Throwable throwable) {
        // Exception messages may include profile contents. Keep only the type.
        e("tunnel_trace event=controller_exception error_type=" + throwable.getClass().getSimpleName());
    }

    public static boolean isLogFile(File file) {
        return "tunnel.log".equals(file.getName()) || "tunnel.1.log".equals(file.getName());
    }

    // Called on the export queue, never the UI thread. The worker drains prior
    // records and copies both files before accepting further file writes.
    public static ExportSnapshot createExportSnapshot() throws IOException {
        if (!BuildConfig.TG_TUNNEL_LOGS_ENABLED) {
            return new ExportSnapshot(null);
        }
        if (!init()) {
            throw new IOException("Tunnel file log is unavailable");
        }
        File directory = new File(ApplicationLoader.applicationContext.getCacheDir(), "tunnel-export-" + UUID.randomUUID());
        ExportSnapshot snapshot = new ExportSnapshot(directory);
        try {
            if (!directory.mkdir() || !nativeSnapshot(directory.getAbsolutePath())) {
                throw new IOException("Unable to snapshot tunnel logs");
            }
            return snapshot;
        } catch (IOException | RuntimeException error) {
            snapshot.close();
            throw error;
        }
    }

    public static void clear() {
        Utilities.globalQueue.postRunnable(() -> {
            if (BuildConfig.TG_TUNNEL_LOGS_ENABLED) {
                if (init()) {
                    nativeClear();
                }
            } else {
                File directory = AndroidUtilities.getLogsDir();
                if (directory != null) {
                    new File(directory, "tunnel.log").delete();
                    new File(directory, "tunnel.1.log").delete();
                }
            }
        });
    }

    public static final class ExportSnapshot implements AutoCloseable {
        private final File directory;

        private ExportSnapshot(File directory) {
            this.directory = directory;
        }

        public boolean replacesLiveFiles() {
            return directory != null;
        }

        public ArrayList<File> files() {
            ArrayList<File> result = new ArrayList<>();
            if (directory != null) {
                File[] files = directory.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (isLogFile(file)) {
                            result.add(file);
                        }
                    }
                }
            }
            return result;
        }

        @Override
        public void close() {
            for (File file : files()) {
                file.delete();
            }
            if (directory != null) {
                directory.delete();
            }
        }
    }

    private static native boolean nativeInit(String directory);
    private static native void nativeWriteFile(int priority, String message);
    private static native boolean nativeSnapshot(String directory);
    private static native void nativeClear();
}
