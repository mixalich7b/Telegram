# Repository Notes for WireGuard Integration

This repository contains an Android Telegram client with an experimental in-process
WireGuard integration.

## Feature Goal

Route Telegram traffic through a WireGuard server without using Android
`VpnService`, without creating a TUN interface on the device, and without adding UI
for the first stage.

The current implementation uses hardcoded code configuration and process-local
proxy endpoints:

```text
tgnet TCP traffic
  -> authenticated SOCKS5 on 127.0.0.1
  -> wireguard-go tun/netstack
  -> UDP to configured WireGuard peer
  -> Telegram datacenters

WebRTC VoIP TCP relay traffic
  -> authenticated HTTP CONNECT on 127.0.0.1
  -> wireguard-go tun/netstack
  -> UDP to configured WireGuard peer
  -> Telegram relay/datacenter
```

## Hard Constraints

- Do not introduce Android `VpnService`, `Builder.establish()`, `/dev/tun`, or a
  device-level VPN session for this feature.
- Keep `TG_WIREGUARD` build integration opt-in. The default build must work with
  WireGuard disabled.
- Keep `WireGuardConfig.ENABLED` disabled by default in shared code.
- Do not commit real WireGuard keys, endpoints, Telegram `APP_ID`, or Telegram
  `APP_HASH`.
- When `WireGuardConfig.ENABLED` is true, traffic must fail closed. Do not add a
  silent direct fallback.
- Do not change proxy UI or persistent proxy preferences for this stage. Runtime
  tgnet proxy settings are overridden in memory only.
- UDP ASSOCIATE is not implemented. Current VoIP safe mode is TCP relay through
  HTTP CONNECT; do not assume UDP relay works through WireGuard yet.

## Code Map

- Java configuration and orchestration:
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardConfig.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardUserspaceConfig.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardManager.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardController.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardProxySettings.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardVoipRouting.java`
- Telegram runtime proxy enforcement:
  - `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`
- Startup hook:
  - `TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`
- Go/JNI WireGuard runtime:
  - `TMessagesProj/jni/tg_wg/go/`
  - `TMessagesProj/jni/tg_wg/tg_wg_jni.cpp`
  - `TMessagesProj/jni/CMakeLists.txt`
- Private VoIP routing:
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/Instance.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/NativeInstance.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java`
  - `TMessagesProj/jni/voip/org_telegram_messenger_voip_Instance.cpp`
  - `TMessagesProj/jni/voip/tgcalls/Instance.h`
  - `TMessagesProj/jni/voip/tgcalls/NetworkManager.cpp`
  - `TMessagesProj/jni/voip/tgcalls/v2/NativeNetworkingImpl.cpp`
  - `TMessagesProj/jni/voip/tgcalls/v2/InstanceV2ReferenceImpl.cpp`
- Group/live VoIP routing:
  - `TMessagesProj/src/main/java/org/telegram/ui/Stories/LivePlayer.java`
  - `TMessagesProj/jni/voip/tgcalls/group/GroupInstanceCustomImpl.cpp`
  - `TMessagesProj/jni/voip/tgcalls/group/GroupInstanceImpl.h`
  - `TMessagesProj/jni/voip/tgcalls/group/GroupNetworkManager.cpp`
  - `TMessagesProj/jni/voip/tgcalls/group/GroupNetworkManager.h`
- Build and static guards:
  - `TMessagesProj/build.gradle`
  - `TMessagesProj_App/build.gradle`
- Detailed project docs:
  - `docs/wireguard-integration-plan.md`
  - `docs/wireguard-testing-plan.md`

## Runtime Invariants

- `WireGuardManager` is the Java entry point. Keep runtime proxy selection
  centralized there and in `WireGuardController`.
- `ConnectionsManager.setProxySettings(...)` must not be able to clear or replace
  the internal WireGuard proxy while WireGuard is enabled.
- If WireGuard startup or restart fails, apply the blocked local proxy to all
  accounts instead of going direct.
- Network changes should call the native refresh path. If native bind refresh
  fails, restart the runtime and reapply proxy settings; if restart fails, fail
  closed.
- The internal proxy binds to `127.0.0.1` with generated credentials.
- Go module/cache output must stay outside `TMessagesProj/jni`; Android Gradle
  scans JNI folders recursively and may otherwise package dependency `.so` files.

## VoIP Invariants

- WireGuard-enabled private calls must ignore user proxy preferences, disable P2P,
  and force TCP relay safe mode.
- WireGuard-created VoIP proxies use HTTP CONNECT. Keep default/user
  `Instance.Proxy` behavior as SOCKS5 unless deliberately changing user proxy
  semantics.
- Native WebRTC paths must call `BasicPortAllocator::set_proxy(...)` when a
  WireGuard proxy is present.
- Native WebRTC paths must disable UDP/STUN and filter direct ICE candidates in
  WireGuard HTTP CONNECT mode:
  - clear `CF_REFLEXIVE`;
  - clear `CF_HOST`;
  - keep TCP candidates enabled when a proxy is present.
- Private VoIP has multiple native paths. When changing routing, audit all of:
  - legacy/private `NetworkManager.cpp`;
  - V2 custom `NativeNetworkingImpl.cpp`;
  - V2 reference `InstanceV2ReferenceImpl.cpp`;
  - group `GroupNetworkManager.cpp`.
- Private V2 reference networking must retain TCP TURN servers with
  `?transport=tcp` when a proxy is present.

## Tests and Verification

Fast no-emulator checks:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:verifyWireGuardStaticGuards
```

Full WireGuard-enabled build:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=true
```

WireGuard-disabled packaging check:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=false
```

Important details:

- `:TMessagesProj_App:assembleAfatDebug` is expected to run JVM unit tests, Go
  tests, static guards, and APK packaging verification.
- Static guards are part of the safety net. If an invariant intentionally changes,
  update tests, guards, and docs together.
- Long Gradle builds can take many minutes. Do not kill a running Gradle build
  unless explicitly asked.
- Prefer no-emulator coverage for glue logic. Real WireGuard server validation,
  packet captures, and active call checks belong to manual/device smoke testing.

## Documentation Policy

When changing this feature, update the docs in the same change if behavior,
architecture, build commands, test coverage, or known risks change:

- `docs/wireguard-integration-plan.md` for architecture, implementation status,
  remediation plans, and open risks.
- `docs/wireguard-testing-plan.md` for unit/static/packaging/manual verification
  expectations.
