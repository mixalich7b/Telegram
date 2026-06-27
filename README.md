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

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore and filled variables inside BuildVars.java. The Firebase config is read from `$HOME/.android-keys/google-services-tg-wg.json`, and `TG_APP_ID` / `TG_APP_HASH` are taken from your global Gradle properties. Before publishing your own APKs please make sure to provide your own values.

You will require Android Studio 3.4, Android NDK rev. 20 and Android SDK 8.1

1. Download the Telegram source code from https://github.com/DrKLO/Telegram ( git clone https://github.com/DrKLO/Telegram.git )
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs `net.mixalich7b.telegram` and `net.mixalich7b.telegram.beta`, turn on firebase messaging and save the downloaded `google-services.json` as `$HOME/.android-keys/google-services-tg-wg.json`.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Set `TG_APP_ID` and `TG_APP_HASH` in your global `gradle.properties`; `BuildVars.java` now reads them during the build.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
