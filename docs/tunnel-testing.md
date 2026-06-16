# Tunnel Testing

## Commands

Fast no-emulator checks:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:testAmneziaWGGo :TMessagesProj:verifyTunnelStaticGuards
```

Packaging check:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug
```

`:TMessagesProj_App:assembleAfatDebug` is expected to run JVM unit tests,
WireGuard Go tests, AmneziaWG Go tests, static guards, and APK packaging
verification.

## Automated Coverage

JVM unit tests under `TMessagesProj/src/test/java` cover:

- userspace config generation and key validation;
- WireGuard and AmneziaWG parser detection, forced-protocol behavior, validation
  errors, and UAPI output;
- profile serialization, encrypted envelope behavior, schema round trips, and
  legacy WireGuard profile migration;
- controller startup, restart, network refresh, and fail-closed behavior;
- tunnel proxy protocol metadata;
- VoIP route policy for direct, active tunnel, and blocked tunnel cases.

Go bridge tests cover:

- address and endpoint parsing;
- domain endpoint preprocessing to `IP:port` and DNS failure reporting;
- SOCKS5 and HTTP CONNECT proxy behavior;
- proxy byte-copy paths through fake dialers;
- network-change handling through fake bind refreshes;
- AmneziaWG `device.IpcSet` acceptance for generated AWG UAPI, including
  `jc/jmin/jmax`, `s1..s4`, `h1..h4`, and `i1..i5`.

The Gradle Go test tasks keep `GOCACHE` and `GOMODCACHE` under the root
`.gradle` directory, never under `TMessagesProj/jni`.

## Static Guards

`verifyTunnelStaticGuards` protects invariants that should not be removed
accidentally:

- no Android `VpnService`, `Builder.establish()`, or `/dev/tun` in tunnel
  integration files;
- no silent direct fallback while any tunnel protocol is enabled;
- profile storage uses Android Keystore-backed AES-GCM and does not write
  plaintext profile contents to `mainconfig`;
- all route-mode changes go through `NetworkRouteSettings`;
- `ConnectionsManager.setProxySettings(...)` preserves the active internal
  tunnel proxy;
- unsupported-device UI checks exist for add/import/scan/enable/select;
- parser rejects multiple peers;
- VoIP paths keep HTTP CONNECT, TCP relay, disabled UDP/STUN, and direct ICE
  filtering behavior;
- WireGuard and AmneziaWG Go cache/module artifacts stay out of
  `TMessagesProj/jni`;
- AmneziaWG imports stay limited to the intended Go bridge dependency.

## Packaging Expectations

APK verification requires these libraries for every supported ABI:

- `libtg-wg.so`;
- `libtg-wg-go.so`;
- `libtg-awg.so`;
- `libtg-awg-go.so`.

APK verification rejects entries containing:

- `gomod/`;
- `gocache/`;
- `tg_wg/go/build`;
- `tg_awg/go/build`;
- `vdso_`;
- downloaded Go module shared objects accidentally packaged from build caches.

## Manual Device Validation

Manual validation requires a real WireGuard server, a real AmneziaWG 2.0 server,
and packet capture or server-side connection logs where possible.

Cover these flows:

- migrate an app install with existing WireGuard profiles;
- configure WireGuard and AmneziaWG manually before login;
- import WireGuard and AmneziaWG config files;
- scan WireGuard and AmneziaWG QR codes from camera and gallery;
- restart the app and verify state persists;
- log in and verify the same settings are available after authorization;
- switch direct/proxy/WireGuard/AmneziaWG modes and verify mutual exclusion;
- switch between multiple profiles across both protocols;
- delete inactive and active profiles;
- simulate startup failure and verify traffic fails closed;
- switch Wi-Fi/LTE while each protocol is active;
- send messages/media while each protocol is active;
- start new private and group/live VoIP sessions while each protocol is active;
- verify Telegram datacenter traffic does not go direct while either protocol is
  enabled.

## Maintenance

- Keep Java logic unit-testable without Android runtime dependencies.
- Keep Android-only Go exports and logging behind Android build tags.
- Pin AmneziaWG to a reviewed tag or commit and update tests when the pin
  changes.
- Re-run license review when tunnel dependencies change.
- Do not place Go module/cache output under `TMessagesProj/jni`.
- Do not add UDP ASSOCIATE assertions until SOCKS5 command `0x03` is
  implemented.
- Do not add real-server tests to default Gradle tasks.
- When an invariant intentionally changes, update tests, static guards, docs,
  and `AGENTS.md` together.
