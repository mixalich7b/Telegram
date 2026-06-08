# WireGuard Test Plan

## Goal

Verify Telegram's in-process WireGuard integration without requiring an emulator
for the primary automated path.

Tests should cover Telegram glue code, build integration, fail-closed behavior,
profile parsing/persistence, and VoIP routing decisions. They should not duplicate
upstream coverage for `wireguard-go`, gVisor/netstack, Android VPN APIs, or the
existing Telegram SOCKS implementation.

## Commands

Fast no-emulator checks:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:verifyWireGuardStaticGuards
```

WireGuard-disabled packaging check:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=false
```

WireGuard-enabled packaging check:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=true
```

`:TMessagesProj_App:assembleAfatDebug` is expected to run JVM unit tests, Go
tests, static guards, and APK packaging verification.

## JVM Unit Tests

JVM unit tests live under `TMessagesProj/src/test/java`.

Core coverage:

- WireGuard key conversion and userspace IPC config generation.
- Invalid keys and missing required config fields fail before native startup.
- Controller fail-closed behavior:
  - disabled config does not start native runtime;
  - startup failure applies blocked local proxy;
  - startup success applies generated internal proxy;
  - repeated start does not create multiple runtimes;
  - native network refresh failure restarts runtime;
  - restart failure applies blocked proxy.
- Profile persistence:
  - empty settings load as disabled/no-profile;
  - profile list serialization round-trips all fields;
  - selected profile id is preserved.
- Config parser:
  - standard `[Interface]` and `[Peer]` fields;
  - comments, whitespace, comma-separated values, bracketed IPv6 endpoints;
  - invalid endpoint ports, missing fields, invalid keys, and multiple peers.
- VoIP routing helper:
  - WireGuard proxy disables P2P;
  - WireGuard proxy forces TCP relay safe mode;
  - blocked WireGuard proxy still keeps fail-closed routing semantics.

## Go Unit Tests

Go tests live in `TMessagesProj/jni/tg_wg/go`.

Core coverage:

- address parsing for IPv4, IPv6, and CIDR;
- invalid/empty address handling;
- SOCKS5 username/password handshake;
- unsupported SOCKS methods and commands;
- IPv4, IPv6, and domain target parsing;
- proxy byte-copy path through a fake dialer;
- HTTP CONNECT proxy authorization and response behavior;
- network-change handling through fake `BindUpdate()` success/failure.

The Gradle Go test task must keep `GOCACHE` and `GOMODCACHE` outside
`TMessagesProj/jni`.

## Static Guards

`verifyWireGuardStaticGuards` is part of the safety net. It should fail the build
if key invariants are removed accidentally.

Current guard coverage:

- `WireGuardConfig.ENABLED` remains disabled by default and committed
  key/endpoint fields are empty.
- WireGuard integration code does not reference `VpnService`,
  `Builder.establish`, or `/dev/tun`.
- `BuildConfig.TG_WIREGUARD_ENABLED` exists for UI build-support gating.
- `ConnectionsManager.setProxySettings(...)` preserves WireGuard proxy authority
  while WireGuard is enabled.
- Route-changing proxy UI/link/rotation paths use `NetworkRouteSettings`.
- WireGuard add/import/enable UI paths check build support and show unavailable
  UI in unsupported builds.
- Login/start screen keeps the proxy/WireGuard settings entry visible before
  authorization.
- Disabling WireGuard leaves ordinary proxy, proxy-for-calls, and proxy rotation
  disabled.
- The parser rejects multiple peers.
- VoIP setup references WireGuard routing decisions and HTTP CONNECT.
- Native private/group WebRTC managers configure proxy routing, disable direct
  ICE paths, and keep TCP candidates/TCP TURN behavior required for HTTP CONNECT.
- Go proxy contains HTTP CONNECT support.
- `TMessagesProj/jni/tg_wg/go/build` is absent.

## Packaging Checks

APK packaging verification is controlled by `TG_WIREGUARD`.

- `TG_WIREGUARD=false`: APK must not contain `libtg-wg.so` or
  `libtg-wg-go.so`.
- `TG_WIREGUARD=true`: APK must contain both libraries for supported ABIs.
- APK must not contain Go cache/module artifacts such as `gomod`, `gocache`,
  `tg_wg/go/build`, or `vdso_*.so`.

## Manual Device Smoke

Manual validation requires a real WireGuard server and device/network capture
where possible.

Required flows:

- configure WireGuard manually before login;
- import a WireGuard config before login;
- restart the app and verify state persists;
- log in and verify the same settings are visible after authorization;
- switch direct/proxy/WireGuard modes and verify mutual exclusion;
- switch between multiple WireGuard profiles;
- test startup failure and verify traffic fails closed;
- send/receive messages and media while enabled;
- switch Wi-Fi/LTE while enabled;
- start new private and group/live VoIP sessions while enabled;
- verify with packet capture that Telegram datacenter traffic does not go direct
  while WireGuard is enabled.

## Maintenance Notes

- Keep Java unit-testable logic free from Android runtime-only APIs.
- Keep Go host-testable code free from Android cgo imports; Android exports and
  logging stay behind Android build tags.
- Do not place Go module/cache output under `TMessagesProj/jni`.
- Do not add UDP ASSOCIATE assertions until the Go proxy implements SOCKS5
  command `0x03`.
- Do not add real-server tests to default Gradle tasks.
- If an invariant intentionally changes, update tests, static guards, docs, and
  `AGENTS.md` together.
