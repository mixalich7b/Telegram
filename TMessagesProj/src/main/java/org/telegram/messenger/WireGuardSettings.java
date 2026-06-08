package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.tgnet.SerializedData;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class WireGuardSettings {

    private static final int WIREGUARD_SCHEMA_V1 = 1;
    private static final int WIREGUARD_CURRENT_SCHEMA_VERSION = WIREGUARD_SCHEMA_V1;

    private static final String PREF_ENABLED = "wireguard_enabled";
    private static final String PREF_CURRENT_PROFILE_ID = "wireguard_current_profile_id";
    private static final String PREF_PROFILE_LIST = "wireguard_profile_list";

    private static boolean loaded;
    private static boolean enabled;
    private static String currentProfileId = "";
    private static final ArrayList<WireGuardProfile> profiles = new ArrayList<>();

    private WireGuardSettings() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        enabled = preferences.getBoolean(PREF_ENABLED, false);
        currentProfileId = preferences.getString(PREF_CURRENT_PROFILE_ID, "");
        profiles.clear();
        profiles.addAll(deserializeProfiles(preferences.getString(PREF_PROFILE_LIST, "")));
        loaded = true;
    }

    public static synchronized void save() {
        load();
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit();
        editor.putBoolean(PREF_ENABLED, enabled);
        editor.putString(PREF_CURRENT_PROFILE_ID, currentProfileId == null ? "" : currentProfileId);
        editor.putString(PREF_PROFILE_LIST, serializeProfiles(profiles));
        editor.apply();
    }

    public static synchronized boolean isEnabled() {
        load();
        return enabled;
    }

    public static synchronized void setEnabled(boolean value) {
        load();
        enabled = value;
        save();
    }

    public static synchronized String getCurrentProfileId() {
        load();
        return currentProfileId;
    }

    public static synchronized void setCurrentProfileId(String profileId) {
        load();
        currentProfileId = profileId == null ? "" : profileId;
        save();
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
        save();
        return stored.copy();
    }

    public static synchronized void deleteProfile(String profileId) {
        load();
        int index = indexOfProfileLocked(profileId);
        if (index >= 0) {
            profiles.remove(index);
        }
        if (equals(currentProfileId, profileId)) {
            currentProfileId = "";
            enabled = false;
        }
        save();
    }

    public static WireGuardProfile importProfile(String configText, String fallbackName) {
        WireGuardProfile profile = WireGuardConfigParser.parse(configText, fallbackName);
        return saveProfile(profile);
    }

    static String serializeProfiles(List<WireGuardProfile> profiles) {
        SerializedData serializedData = new SerializedData();
        serializedData.writeInt32(-1);
        serializedData.writeByte(WIREGUARD_CURRENT_SCHEMA_VERSION);
        int count = profiles == null ? 0 : profiles.size();
        serializedData.writeInt32(count);
        if (profiles != null) {
            for (WireGuardProfile profile : profiles) {
                writeProfile(serializedData, profile);
            }
        }
        String result = Base64.getEncoder().encodeToString(serializedData.toByteArray());
        serializedData.cleanup();
        return result;
    }

    static ArrayList<WireGuardProfile> deserializeProfiles(String serialized) {
        ArrayList<WireGuardProfile> result = new ArrayList<>();
        if (serialized == null || serialized.isEmpty()) {
            return result;
        }
        SerializedData data = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(serialized);
            data = new SerializedData(bytes);
            int marker = data.readInt32(false);
            if (marker != -1) {
                return result;
            }
            int version = data.readByte(false);
            if (version != WIREGUARD_SCHEMA_V1) {
                return result;
            }
            int count = data.readInt32(false);
            for (int i = 0; i < count; i++) {
                WireGuardProfile profile = readProfile(data);
                profile.normalize();
                result.add(profile);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            result.clear();
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
        return result;
    }

    static String newProfileId() {
        return UUID.randomUUID().toString();
    }

    private static void writeProfile(SerializedData data, WireGuardProfile profile) {
        profile = profile == null ? new WireGuardProfile() : profile.copy();
        profile.normalize();
        data.writeString(profile.id);
        data.writeString(profile.name);
        data.writeString(profile.privateKey);
        writeStringArray(data, profile.localAddresses);
        writeStringArray(data, profile.dnsServers);
        data.writeInt32(profile.mtu);
        data.writeString(profile.peerPublicKey);
        data.writeString(profile.presharedKey);
        data.writeString(profile.peerEndpoint);
        writeStringArray(data, profile.allowedIps);
        data.writeInt32(profile.persistentKeepaliveSeconds);
        data.writeInt64(profile.createdAt);
        data.writeInt64(profile.updatedAt);
    }

    private static WireGuardProfile readProfile(SerializedData data) {
        WireGuardProfile profile = new WireGuardProfile();
        profile.id = data.readString(false);
        profile.name = data.readString(false);
        profile.privateKey = data.readString(false);
        profile.localAddresses = readStringArray(data);
        profile.dnsServers = readStringArray(data);
        profile.mtu = data.readInt32(false);
        profile.peerPublicKey = data.readString(false);
        profile.presharedKey = data.readString(false);
        profile.peerEndpoint = data.readString(false);
        profile.allowedIps = readStringArray(data);
        profile.persistentKeepaliveSeconds = data.readInt32(false);
        profile.createdAt = data.readInt64(false);
        profile.updatedAt = data.readInt64(false);
        return profile;
    }

    private static void writeStringArray(SerializedData data, String[] values) {
        values = WireGuardProfile.normalizeArray(values);
        data.writeInt32(values.length);
        for (String value : values) {
            data.writeString(value);
        }
    }

    private static String[] readStringArray(SerializedData data) {
        int count = data.readInt32(false);
        if (count <= 0) {
            return new String[0];
        }
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = data.readString(false);
        }
        return values;
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
