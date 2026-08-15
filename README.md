## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

### About this fork

This is an unofficial fork of the official Telegram client for Android.
It can route Telegram traffic through WireGuard or AmneziaWG directly inside the app,
without Android `VpnService`, a device TUN interface, or a system-wide VPN connection. 
Tunnel profiles can be added manually or imported from configuration files and QR codes, 
and routing through the tunnel can be enabled separately for calls.

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore and filled variables inside BuildVars.java. The Firebase config is read from `$HOME/.android-keys/google-services-tg-wg.json`. `TG_APP_ID`, `TG_APP_HASH`, `TG_RELEASE_KEYSTORE_PATH`, `TG_RELEASE_STORE_PASSWORD`, `TG_RELEASE_KEY_ALIAS`, and `TG_RELEASE_KEY_PASSWORD` are taken from the user's global Gradle properties and must be provided before running Gradle. Do not add these values to the repository.

You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 35.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/mixalich7b/Telegram.git Telegram
   ```
   In case you forgot the `--recursive` flag, change to the `Telegram` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Debug builds use the checked-in debug keystore and signing properties. Do not copy or commit a release keystore.
3. The checked-in debug signing properties are already used by debug builds. For release signing, provide `TG_RELEASE_KEYSTORE_PATH`, `TG_RELEASE_STORE_PASSWORD`, `TG_RELEASE_KEY_ALIAS`, and `TG_RELEASE_KEY_PASSWORD` in the global `gradle.properties`.
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs `net.mixalich7b.telegram` and `net.mixalich7b.telegram.beta`, turn on firebase messaging and save the downloaded `google-services.json` as `$HOME/.android-keys/google-services-tg-wg.json`.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Set `TG_APP_ID` and `TG_APP_HASH` in your global `gradle.properties`; the build reads them during Gradle configuration.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
