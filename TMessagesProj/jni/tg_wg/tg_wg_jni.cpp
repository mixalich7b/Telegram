#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <string>
#include <vector>

namespace {

constexpr const char *kTag = "Telegram/WireGuard";

using StartFunc = int (*)(const char *, const char **, int, const char **, int, int, const char *, int, const char *, const char *);
using VoidFunc = void (*)();
using NetworkChangedFunc = int (*)();

void *goHandle = nullptr;
StartFunc startFunc = nullptr;
VoidFunc stopFunc = nullptr;
NetworkChangedFunc networkChangedFunc = nullptr;

void logError(const char *message) {
    __android_log_write(ANDROID_LOG_ERROR, kTag, message);
}

bool loadGoBridge() {
    if (goHandle != nullptr) {
        return true;
    }
    goHandle = dlopen("libtg-wg-go.so", RTLD_NOW);
    if (goHandle == nullptr) {
        const char *error = dlerror();
        __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen libtg-wg-go.so failed: %s", error != nullptr ? error : "unknown");
        return false;
    }
    startFunc = reinterpret_cast<StartFunc>(dlsym(goHandle, "tgWgStart"));
    stopFunc = reinterpret_cast<VoidFunc>(dlsym(goHandle, "tgWgStop"));
    networkChangedFunc = reinterpret_cast<NetworkChangedFunc>(dlsym(goHandle, "tgWgOnNetworkChanged"));
    if (startFunc == nullptr || stopFunc == nullptr || networkChangedFunc == nullptr) {
        logError("required tg-wg-go symbols are missing");
        dlclose(goHandle);
        goHandle = nullptr;
        startFunc = nullptr;
        stopFunc = nullptr;
        networkChangedFunc = nullptr;
        return false;
    }
    return true;
}

class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv *env, jstring value) : env(env), value(value) {
        if (value != nullptr) {
            chars = env->GetStringUTFChars(value, nullptr);
        }
    }

    ~ScopedUtfChars() {
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(value, chars);
        }
    }

    const char *get() const {
        return chars != nullptr ? chars : "";
    }

private:
    JNIEnv *env;
    jstring value;
    const char *chars = nullptr;
};

std::vector<std::string> toStringVector(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> result;
    if (array == nullptr) {
        return result;
    }
    jsize length = env->GetArrayLength(array);
    result.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; i++) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        // ReleaseStringUTFChars must run before the local ref is deleted.
        {
            ScopedUtfChars chars(env, value);
            result.emplace_back(chars.get());
        }
        env->DeleteLocalRef(value);
    }
    return result;
}

std::vector<const char *> toCStringVector(const std::vector<std::string> &values) {
    std::vector<const char *> result;
    result.reserve(values.size());
    for (const auto &value : values) {
        result.push_back(value.c_str());
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_WireGuardManager_nativeStart(JNIEnv *env, jclass, jstring userspaceConfig, jobjectArray localAddresses, jobjectArray dnsServers, jint mtu, jstring socksHost, jint socksPort, jstring socksUsername, jstring socksPassword) {
    if (!loadGoBridge()) {
        return -100;
    }

    ScopedUtfChars config(env, userspaceConfig);
    ScopedUtfChars host(env, socksHost);
    ScopedUtfChars username(env, socksUsername);
    ScopedUtfChars password(env, socksPassword);

    std::vector<std::string> localAddressValues = toStringVector(env, localAddresses);
    std::vector<std::string> dnsServerValues = toStringVector(env, dnsServers);
    std::vector<const char *> localAddressPointers = toCStringVector(localAddressValues);
    std::vector<const char *> dnsServerPointers = toCStringVector(dnsServerValues);

    return static_cast<jint>(startFunc(
            config.get(),
            localAddressPointers.data(),
            static_cast<int>(localAddressPointers.size()),
            dnsServerPointers.data(),
            static_cast<int>(dnsServerPointers.size()),
            static_cast<int>(mtu),
            host.get(),
            static_cast<int>(socksPort),
            username.get(),
            password.get()));
}

extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_WireGuardManager_nativeOnNetworkChanged(JNIEnv *, jclass) {
    if (loadGoBridge()) {
        return static_cast<jint>(networkChangedFunc());
    }
    return -100;
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_WireGuardManager_nativeStop(JNIEnv *, jclass) {
    if (loadGoBridge()) {
        stopFunc();
    }
}
