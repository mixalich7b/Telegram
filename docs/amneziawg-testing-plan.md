# AmneziaWG Testing Plan

## Purpose

Extend the current WireGuard safety net so AmneziaWG support is covered by fast
host-side tests, static guards, packaging checks, and manual/device validation.

The test suite should verify Telegram glue code, fail-closed behavior, parsing,
encrypted profile storage, protocol selection, native build integration, and
VoIP routing. It should not duplicate upstream `amneziawg-go` cryptographic or
transport tests.

## Expected Commands

Fast no-emulator checks after implementation:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:testAmneziaWGGo :TMessagesProj:verifyTunnelStaticGuards
```

If static guards remain split instead of being renamed:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:testAmneziaWGGo :TMessagesProj:verifyWireGuardStaticGuards :TMessagesProj:verifyAmneziaWGStaticGuards
```

Packaging check:

```bash
env GRADLE_USER_HOME=$PWD/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug
```

`assembleAfatDebug` should continue to run JVM unit tests, Go tests, static
guards, and APK packaging verification.

## JVM Unit Tests

Add or extend tests under `TMessagesProj/src/test/java/org/telegram/messenger`.

Parser tests:

- standard WireGuard config still parses as `WIREGUARD`;
- AmneziaWG config with `Jc/Jmin/Jmax`, `S1`..`S4`, `H1`..`H4`, and `I1`..`I5`
  parses as `AMNEZIA_WG`;
- keys are case-insensitive and whitespace/comment handling matches WireGuard;
- multiple `[Peer]` sections are rejected;
- AmneziaWG keys outside accepted sections are rejected or ignored according to
  the chosen parser policy;
- invalid `Jc/Jmin/Jmax`, padding, header range, header overlap, and CPS syntax
  are rejected;
- standard WireGuard config imported through automatic detection does not become
  AmneziaWG unless the user explicitly selected that protocol.

Userspace config tests:

- WireGuard output stays byte-for-byte compatible with existing expectations;
- AmneziaWG output emits base WireGuard UAPI plus AmneziaWG device keys before
  `public_key=`;
- zero or empty AmneziaWG values are omitted from UAPI output;
- preshared key and allowed IP behavior stays unchanged;
- invalid key lengths still fail before runtime start.

Profile and storage tests:

- protocol enum round-trips through encrypted profile serialization;
- AmneziaWG fields round-trip through encrypted profile serialization;
- old WireGuard serialized profiles migrate to `protocol=WIREGUARD`;
- migration does not delete legacy data until new encrypted save succeeds;
- wrong-key encrypted envelope still fails closed;
- no plaintext profile list key is introduced in `mainconfig`.

Controller and route policy tests:

- enabling AmneziaWG disables ordinary proxy, proxy-for-calls, proxy rotation,
  and WireGuard;
- enabling WireGuard disables AmneziaWG;
- startup failure applies the blocked local proxy, not direct routing;
- restart/profile switch applies blocked proxy before starting the new runtime;
- network-refresh failure restarts the active runtime and stays fail-closed if
  restart fails;
- deleting the active AmneziaWG profile disables the active tunnel;
- unsupported secure storage blocks add/import/scan/enable but allows disabling
  stale enabled state.

VoIP tests:

- active AmneziaWG proxy is treated like WireGuard for P2P disabling and TCP
  relay selection;
- blocked tunnel proxy still forces tunnel VoIP policy;
- default/user proxy semantics remain SOCKS5 unless the proxy is the internal
  tunnel proxy.

## Go Tests

Add `TMessagesProj/jni/tg_awg/go` host-side tests and a Gradle
`testAmneziaWGGo` task. Keep `GOCACHE` and `GOMODCACHE` under the root
`.gradle` directory, never under `TMessagesProj/jni`.

Copy the existing bridge-level WireGuard Go tests where applicable:

- local address and DNS address parsing;
- domain endpoint preprocessing to `IP:port`;
- DNS failure reporting;
- SOCKS5 CONNECT handshake and authentication;
- HTTP CONNECT handshake and authentication;
- proxy copy behavior through fake dialers;
- bind refresh and network-change failure behavior.

Add AmneziaWG-specific Go tests:

- `device.IpcSet` accepts generated AmneziaWG UAPI with valid `jc`, `jmin`,
  `jmax`, `s1`..`s4`, `h1`..`h4`, and `i1`..`i5`;
- `device.IpcSet` rejects overlapping header ranges;
- generated configs omit explicit zero AmneziaWG values;
- invalid endpoint resolution keeps `startRuntime` failed.

## Static Guards

Broaden the existing static guard coverage to both protocol paths.

Required guards:

- no `VpnService`, `Builder.establish`, or `/dev/tun` in tunnel integration
  files;
- no silent direct fallback while any tunnel protocol is enabled;
- profile contents use Keystore-backed AES-GCM encrypted storage;
- no plaintext private keys, preshared keys, or AmneziaWG masking fields in
  `mainconfig`;
- all route-mode changes go through `NetworkRouteSettings`;
- `ConnectionsManager.setProxySettings(...)` preserves the active internal
  tunnel proxy;
- unsupported-device UI checks exist for add/import/scan/enable/select;
- parser rejects multiple peers;
- VoIP paths keep HTTP CONNECT, TCP relay, disabled UDP/STUN, and direct ICE
  filtering behavior;
- Go build/cache directories are not under `TMessagesProj/jni`;
- AmneziaWG dependency imports are limited to the intended Go bridge module.

## Packaging Checks

APK verification should require these libraries for every supported ABI:

- `libtg-wg.so`;
- `libtg-wg-go.so`;
- `libtg-awg.so`;
- `libtg-awg-go.so`.

APK verification should reject entries containing:

- `gomod/`;
- `gocache/`;
- `tg_wg/go/build`;
- `tg_awg/go/build`;
- `vdso_`;
- any downloaded Go module shared objects accidentally packaged from build
  caches.

## Manual Device Smoke

Manual validation requires:

- one standard WireGuard server;
- one AmneziaWG 2.0 server;
- packet capture or server-side connection logs where possible.

Required flows:

- migrate an app install with existing WireGuard profiles;
- configure WireGuard manually before login;
- configure AmneziaWG manually before login;
- import WireGuard and AmneziaWG config files;
- scan WireGuard and AmneziaWG QR codes from camera and gallery;
- restart the app and verify state persists;
- log in and verify the same settings are available after authorization;
- switch direct/proxy/WireGuard/AmneziaWG modes and verify mutual exclusion;
- switch between multiple profiles across both protocols;
- delete inactive profiles;
- delete the active WireGuard profile;
- delete the active AmneziaWG profile;
- simulate startup failure and verify traffic fails closed;
- switch Wi-Fi/LTE while WireGuard is active;
- switch Wi-Fi/LTE while AmneziaWG is active;
- send messages/media while each protocol is active;
- start new private and group/live VoIP sessions while each protocol is active;
- verify Telegram datacenter traffic does not go direct while either protocol is
  enabled.

## Maintenance Rules

- Keep Java logic unit-testable without Android runtime dependencies.
- Keep Android-only Go exports and logging behind Android build tags.
- Pin `amneziawg-go` to a reviewed commit or tag.
- Re-run license review when the pinned AmneziaWG dependency changes.
- Update docs, static guards, and `AGENTS.md` whenever a tunnel invariant
  intentionally changes.
