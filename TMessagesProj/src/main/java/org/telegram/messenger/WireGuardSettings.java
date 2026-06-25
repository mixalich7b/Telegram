package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.UUID;

public final class WireGuardSettings {

    private static final String PREF_ENABLED = "tunnel_enabled";
    private static final String PREF_CURRENT_PROFILE_ID = "tunnel_current_profile_id";
    private static final String PREF_VOIP_ENABLED = "tunnel_voip_enabled";
    private static final String PREF_LEGACY_ENABLED = "wireguard_enabled";
    private static final String PREF_LEGACY_CURRENT_PROFILE_ID = "wireguard_current_profile_id";

    private static boolean loaded;
    private static boolean enabled;
    private static boolean voipEnabled = true;
    private static String currentProfileId = "";
    private static final ArrayList<WireGuardProfile> profiles = new ArrayList<>();

    private WireGuardSettings() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        profiles.clear();
        profiles.addAll(WireGuardSecureStore.loadProfiles());
        enabled = preferences.contains(PREF_ENABLED)
                ? preferences.getBoolean(PREF_ENABLED, false)
                : preferences.getBoolean(PREF_LEGACY_ENABLED, false);
        currentProfileId = preferences.contains(PREF_CURRENT_PROFILE_ID)
                ? preferences.getString(PREF_CURRENT_PROFILE_ID, "")
                : preferences.getString(PREF_LEGACY_CURRENT_PROFILE_ID, "");
        voipEnabled = preferences.getBoolean(PREF_VOIP_ENABLED, true);
        loaded = true;
    }

    public static synchronized void save() {
        load();
        saveProfilesAndSettingsLocked();
    }

    static boolean isSecureStorageSupported() {
        return WireGuardSecureStore.isSupported();
    }

    private static void saveSettingsLocked() {
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit();
        editor.putBoolean(PREF_ENABLED, enabled);
        editor.putString(PREF_CURRENT_PROFILE_ID, currentProfileId == null ? "" : currentProfileId);
        editor.putBoolean(PREF_VOIP_ENABLED, voipEnabled);
        editor.putBoolean(PREF_LEGACY_ENABLED, enabled);
        editor.putString(PREF_LEGACY_CURRENT_PROFILE_ID, currentProfileId == null ? "" : currentProfileId);
        editor.apply();
    }

    private static void saveProfilesAndSettingsLocked() {
        WireGuardSecureStore.saveProfiles(profiles);
        saveSettingsLocked();
    }

    public static synchronized boolean isEnabled() {
        load();
        return enabled;
    }

    public static synchronized void setEnabled(boolean value) {
        load();
        enabled = value;
        saveSettingsLocked();
    }

    public static synchronized boolean isVoipEnabled() {
        load();
        return voipEnabled;
    }

    public static synchronized void setVoipEnabled(boolean value) {
        load();
        voipEnabled = value;
        saveSettingsLocked();
    }

    public static synchronized String getCurrentProfileId() {
        load();
        return currentProfileId;
    }

    public static synchronized void setCurrentProfileId(String profileId) {
        load();
        currentProfileId = profileId == null ? "" : profileId;
        saveSettingsLocked();
    }

    public static synchronized ArrayList<WireGuardProfile> getProfiles() {
        load();
        ArrayList<WireGuardProfile> result = new ArrayList<>(profiles.size());
        for (WireGuardProfile profile : profiles) {
            result.add(profile.copy());
        }
        return result;
    }

    public static synchronized WireGuardProfile getCurrentProfile() {
        load();
        return findProfileLocked(currentProfileId);
    }

    public static synchronized WireGuardProfile getProfile(String profileId) {
        load();
        return findProfileLocked(profileId);
    }

    public static synchronized WireGuardProfile saveProfile(WireGuardProfile profile) {
        load();
        ArrayList<WireGuardProfile> previousProfiles = copyProfilesLocked();
        String previousCurrentProfileId = currentProfileId;
        boolean previousEnabled = enabled;

        WireGuardProfile stored = profile.copy();
        stored.normalize();
        long now = System.currentTimeMillis();
        if (stored.id == null || stored.id.isEmpty()) {
            stored.id = newProfileId();
        }
        if (stored.createdAt == 0) {
            stored.createdAt = now;
        }
        stored.updatedAt = now;

        int index = indexOfProfileLocked(stored.id);
        if (index >= 0) {
            profiles.set(index, stored);
        } else {
            profiles.add(0, stored);
        }
        if (currentProfileId == null || currentProfileId.isEmpty()) {
            currentProfileId = stored.id;
        }
        try {
            saveProfilesAndSettingsLocked();
        } catch (RuntimeException e) {
            restoreLocked(previousProfiles, previousCurrentProfileId, previousEnabled);
            throw e;
        }
        return stored.copy();
    }

    public static synchronized void deleteProfile(String profileId) {
        load();
        ArrayList<WireGuardProfile> previousProfiles = copyProfilesLocked();
        String previousCurrentProfileId = currentProfileId;
        boolean previousEnabled = enabled;

        int index = indexOfProfileLocked(profileId);
        if (index >= 0) {
            profiles.remove(index);
        }
        if (equals(currentProfileId, profileId)) {
            currentProfileId = "";
            enabled = false;
        }
        try {
            saveProfilesAndSettingsLocked();
        } catch (RuntimeException e) {
            restoreLocked(previousProfiles, previousCurrentProfileId, previousEnabled);
            throw e;
        }
    }

    public static WireGuardProfile importProfile(String configText, String fallbackName) {
        WireGuardProfile profile = TunnelConfigParser.parse(configText, fallbackName);
        return saveProfile(profile);
    }

    static String newProfileId() {
        return UUID.randomUUID().toString();
    }

    private static ArrayList<WireGuardProfile> copyProfilesLocked() {
        ArrayList<WireGuardProfile> result = new ArrayList<>(profiles.size());
        for (WireGuardProfile profile : profiles) {
            result.add(profile.copy());
        }
        return result;
    }

    private static void restoreLocked(ArrayList<WireGuardProfile> previousProfiles, String previousCurrentProfileId, boolean previousEnabled) {
        profiles.clear();
        profiles.addAll(previousProfiles);
        currentProfileId = previousCurrentProfileId;
        enabled = previousEnabled;
    }

    private static WireGuardProfile findProfileLocked(String profileId) {
        int index = indexOfProfileLocked(profileId);
        return index >= 0 ? profiles.get(index).copy() : null;
    }

    private static int indexOfProfileLocked(String profileId) {
        if (profileId == null || profileId.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < profiles.size(); i++) {
            if (equals(profiles.get(i).id, profileId)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equals(String lhs, String rhs) {
        return lhs == null ? rhs == null : lhs.equals(rhs);
    }
}
