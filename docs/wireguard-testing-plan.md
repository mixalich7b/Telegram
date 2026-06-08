# WireGuard Testing

## Purpose

Verify Telegram's in-process WireGuard integration through fast no-emulator
checks, packaging checks, and focused manual/device smoke tests.

Coverage should focus on Telegram glue code, build integration, fail-closed
behavior, profile parsing/persistence, UI routing policy, and VoIP routing
decisions. Do not duplicate upstream `wireguard-go`, gVisor/netstack, Android VPN
API, or existing Telegram SOCKS coverage.

## Commands

Fast no-emulator checks:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:verifyWireGuardStaticGuards
```

Packaging checks:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=false
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=true
```

`:TMessagesProj_App:assembleAfatDebug` is expected to run JVM unit tests, Go
tests, static guards, and APK packaging verification.

## Automated Coverage

JVM unit tests live under `TMessagesProj/src/test/java` and cover:

- WireGuard userspace config generation and key validation;
- controller startup, restart, network refresh, and fail-closed behavior;
- profile serialization, encrypted envelope, and schema round-trip behavior;
- endpoint validation for domain, IPv4, bracketed IPv6, invalid host, and
  invalid port forms;
- config parser rules, including comments, lists, domain/IPv4/bracketed IPv6
  endpoints, missing fields, invalid keys, and multiple-peer rejection;
- VoIP routing helpers.

Go tests live in `TMessagesProj/jni/tg_wg/go` and cover:

- address and endpoint parsing;
- domain endpoint preprocessing to `IP:port` and DNS failure reporting;
- SOCKS5 and HTTP CONNECT proxy behavior;
- proxy byte-copy paths through fake dialers;
- network-change handling through fake bind refreshes.

The Gradle Go test task must keep `GOCACHE` and `GOMODCACHE` outside
`TMessagesProj/jni`.

`verifyWireGuardStaticGuards` protects invariants that should not be removed
accidentally:

- no Android `VpnService`, `Builder.establish()`, or `/dev/tun`;
- `WireGuardConfig.ENABLED` remains disabled by default;
- `BuildConfig.TG_WIREGUARD_ENABLED` gates UI build support;
- profile storage uses Android Keystore-backed AES-GCM and does not write the
  plaintext profile list to `mainconfig`;
- WireGuard proxy authority is preserved while enabled;
- route-changing UI paths use `NetworkRouteSettings`;
- unsupported builds block WireGuard add/import/scan/enable actions;
- parser rejects multiple peers;
- VoIP paths keep HTTP CONNECT, TCP relay, and direct ICE filtering behavior;
- Go cache/module artifacts stay out of `TMessagesProj/jni`.

## Packaging Expectations

- `TG_WIREGUARD=false`: APK must not contain `libtg-wg.so` or
  `libtg-wg-go.so`.
- `TG_WIREGUARD=true`: APK must contain both libraries for supported ABIs.
- APK must not contain Go cache/module artifacts such as `gomod`, `gocache`,
  `tg_wg/go/build`, or `vdso_*.so`.

## Manual Device Smoke

Manual validation requires a real WireGuard server and packet capture where
possible.

Required flows:

- configure WireGuard manually before login;
- import config files and scan config QR codes before login;
- restart the app and verify state persists;
- log in and verify the same settings are visible after authorization;
- delete inactive and active WireGuard profiles after confirmation;
- switch direct/proxy/WireGuard modes and verify mutual exclusion;
- switch between multiple WireGuard profiles;
- test startup failure and verify traffic fails closed;
- send and receive messages/media while enabled;
- switch Wi-Fi/LTE while enabled;
- start new private and group/live VoIP sessions while enabled;
- verify Telegram datacenter traffic does not go direct while WireGuard is
  enabled.

## Maintenance

- Keep Java unit-testable logic free from Android runtime-only APIs.
- Keep Go host-testable code free from Android cgo imports; Android exports and
  logging stay behind Android build tags.
- Do not place Go module/cache output under `TMessagesProj/jni`.
- Do not add UDP ASSOCIATE assertions until SOCKS5 command `0x03` is
  implemented.
- Do not add real-server tests to default Gradle tasks.
- When an invariant intentionally changes, update tests, static guards, docs, and
  `AGENTS.md` together.
