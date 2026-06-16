package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.telegram.tgnet.SerializedData;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class WireGuardSecureStore {

    private static final int PROFILE_MARKER = -1;
    private static final int PROFILE_SCHEMA_V1 = 1;
    private static final int PROFILE_SCHEMA_V2 = 2;
    private static final int PROFILE_CURRENT_SCHEMA_VERSION = PROFILE_SCHEMA_V2;

    private static final int ENCRYPTED_MARKER = -2;
    private static final int ENCRYPTED_SCHEMA_V1 = 1;
    private static final int ENCRYPTED_CURRENT_SCHEMA_VERSION = ENCRYPTED_SCHEMA_V1;
    private static final int GCM_TAG_BITS = 128;

    private static final String PREF_NAME = "wireguard_secure";
    private static final String PREF_ENCRYPTED_PROFILES = "wireguard_profiles_encrypted_v1";
    private static final String KEY_ALIAS = "telegram_wireguard_profiles_v1";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private WireGuardSecureStore() {
    }

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    static ArrayList<WireGuardProfile> loadProfiles() {
        ArrayList<WireGuardProfile> result = new ArrayList<>();
        if (!isSupported()) {
            FileLog.e("WireGuard secure storage requires Android 6.0 or newer");
            return result;
        }
        String encryptedProfiles = getPreferences().getString(PREF_ENCRYPTED_PROFILES, "");
        if (encryptedProfiles == null || encryptedProfiles.isEmpty()) {
            return result;
        }
        try {
            return deserializeProfiles(decryptProfiles(encryptedProfiles, getSecretKey()));
        } catch (Throwable e) {
            FileLog.e(e);
            return result;
        }
    }

    static void saveProfiles(List<WireGuardProfile> profiles) {
        if (!isSupported()) {
            throw new IllegalStateException("WireGuard secure storage requires Android 6.0 or newer");
        }
        try {
            String encryptedProfiles = encryptProfiles(serializeProfiles(profiles), getSecretKey());
            getPreferences().edit().putString(PREF_ENCRYPTED_PROFILES, encryptedProfiles).apply();
        } catch (Throwable e) {
            FileLog.e(e);
            throw new RuntimeException("Unable to save WireGuard profiles securely", e);
        }
    }

    static byte[] serializeProfiles(List<WireGuardProfile> profiles) {
        SerializedData data = new SerializedData();
        try {
            data.writeInt32(PROFILE_MARKER);
            data.writeByte(PROFILE_CURRENT_SCHEMA_VERSION);
            int count = profiles == null ? 0 : profiles.size();
            data.writeInt32(count);
            if (profiles != null) {
                for (WireGuardProfile profile : profiles) {
                    writeProfile(data, profile);
                }
            }
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    static ArrayList<WireGuardProfile> deserializeProfiles(byte[] serialized) {
        ArrayList<WireGuardProfile> result = new ArrayList<>();
        if (serialized == null || serialized.length == 0) {
            return result;
        }
        SerializedData data = null;
        try {
            data = new SerializedData(serialized);
            int marker = data.readInt32(false);
            if (marker != PROFILE_MARKER) {
                return result;
            }
            int version = data.readByte(false);
            if (version != PROFILE_SCHEMA_V1 && version != PROFILE_SCHEMA_V2) {
                return result;
            }
            int count = data.readInt32(false);
            for (int i = 0; i < count; i++) {
                WireGuardProfile profile = readProfile(data, version);
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

    static String encryptProfiles(byte[] serializedProfiles, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(serializedProfiles == null ? new byte[0] : serializedProfiles);
        return writeEncryptedEnvelope(cipher.getIV(), ciphertext);
    }

    static byte[] decryptProfiles(String encryptedProfiles, SecretKey key) throws Exception {
        EncryptedEnvelope envelope = readEncryptedEnvelope(encryptedProfiles);
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, envelope.iv));
        return cipher.doFinal(envelope.ciphertext);
    }

    private static SecretKey getSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
            KeyGenParameterSpec keySpec = new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build();
            keyGenerator.init(keySpec);
            keyGenerator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    private static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String writeEncryptedEnvelope(byte[] iv, byte[] ciphertext) {
        SerializedData data = new SerializedData();
        try {
            data.writeInt32(ENCRYPTED_MARKER);
            data.writeByte(ENCRYPTED_CURRENT_SCHEMA_VERSION);
            data.writeByteArray(iv);
            data.writeByteArray(ciphertext);
            return Base64.getEncoder().encodeToString(data.toByteArray());
        } finally {
            data.cleanup();
        }
    }

    private static EncryptedEnvelope readEncryptedEnvelope(String encryptedProfiles) {
        if (encryptedProfiles == null || encryptedProfiles.isEmpty()) {
            throw new IllegalArgumentException("missing encrypted WireGuard profiles");
        }
        SerializedData data = null;
        try {
            data = new SerializedData(Base64.getDecoder().decode(encryptedProfiles));
            int marker = data.readInt32(false);
            if (marker != ENCRYPTED_MARKER) {
                throw new IllegalArgumentException("invalid WireGuard profile envelope");
            }
            int version = data.readByte(false);
            if (version != ENCRYPTED_SCHEMA_V1) {
                throw new IllegalArgumentException("unsupported WireGuard profile envelope");
            }
            byte[] iv = data.readByteArray(false);
            byte[] ciphertext = data.readByteArray(false);
            if (iv == null || iv.length == 0 || ciphertext == null || ciphertext.length == 0) {
                throw new IllegalArgumentException("invalid WireGuard profile ciphertext");
            }
            return new EncryptedEnvelope(iv, ciphertext);
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    private static void writeProfile(SerializedData data, WireGuardProfile profile) {
        profile = profile == null ? new WireGuardProfile() : profile.copy();
        profile.normalize();
        data.writeString(profile.id);
        data.writeString(profile.protocol.storageValue());
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
        data.writeInt32(profile.amneziaJc);
        data.writeInt32(profile.amneziaJmin);
        data.writeInt32(profile.amneziaJmax);
        data.writeInt32(profile.amneziaS1);
        data.writeInt32(profile.amneziaS2);
        data.writeInt32(profile.amneziaS3);
        data.writeInt32(profile.amneziaS4);
        data.writeString(profile.amneziaH1);
        data.writeString(profile.amneziaH2);
        data.writeString(profile.amneziaH3);
        data.writeString(profile.amneziaH4);
        data.writeString(profile.amneziaI1);
        data.writeString(profile.amneziaI2);
        data.writeString(profile.amneziaI3);
        data.writeString(profile.amneziaI4);
        data.writeString(profile.amneziaI5);
        data.writeInt64(profile.createdAt);
        data.writeInt64(profile.updatedAt);
    }

    private static WireGuardProfile readProfile(SerializedData data, int version) {
        WireGuardProfile profile = new WireGuardProfile();
        profile.id = data.readString(false);
        if (version >= PROFILE_SCHEMA_V2) {
            profile.protocol = TunnelProtocol.fromStorageValue(data.readString(false));
        } else {
            profile.protocol = TunnelProtocol.WIREGUARD;
        }
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
        if (version >= PROFILE_SCHEMA_V2) {
            profile.amneziaJc = data.readInt32(false);
            profile.amneziaJmin = data.readInt32(false);
            profile.amneziaJmax = data.readInt32(false);
            profile.amneziaS1 = data.readInt32(false);
            profile.amneziaS2 = data.readInt32(false);
            profile.amneziaS3 = data.readInt32(false);
            profile.amneziaS4 = data.readInt32(false);
            profile.amneziaH1 = data.readString(false);
            profile.amneziaH2 = data.readString(false);
            profile.amneziaH3 = data.readString(false);
            profile.amneziaH4 = data.readString(false);
            profile.amneziaI1 = data.readString(false);
            profile.amneziaI2 = data.readString(false);
            profile.amneziaI3 = data.readString(false);
            profile.amneziaI4 = data.readString(false);
            profile.amneziaI5 = data.readString(false);
        }
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

    private static final class EncryptedEnvelope {
        final byte[] iv;
        final byte[] ciphertext;

        EncryptedEnvelope(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }
}
