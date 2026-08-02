# Tunnel Testing

## Commands

Fast no-emulator checks:

```bash
./gradlew --no-daemon :TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:testAmneziaWGGo :TMessagesProj:verifyTunnelStaticGuards
```

Packaging check:

```bash
./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug
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
- controller startup, restart, network refresh, fail-closed behavior, and
  aggregation of parallel/sequential tunnel TCP attempts for IPv4-only profiles;
- tunnel marker metadata;
- VoIP route policy for direct, enabled/disabled tunnel-for-calls, active
  tunnel, blocked tunnel, and tunnel-routed P2P cases.

Go bridge tests cover:

- address and endpoint parsing;
- domain endpoint preprocessing to `IP:port` and DNS failure reporting;
- direct TCP tunnel socket exports for tgnet;
- direct TCP/UDP tunnel socket exports for WebRTC VoIP;
- tunnel DNS lookup, complete TCP writes, and stale runtime-generation socket
  rejection;
- network-change handling through fake bind refreshes;
- AmneziaWG `device.IpcSet` acceptance for generated AWG UAPI, including
  `jc/jmin/jmax`, `s1..s4`, `h1..h4`, and `i1..i5`.

The Gradle Go test tasks keep `GOCACHE` and `GOMODCACHE` under the Gradle
user home directory, never under `TMessagesProj/jni`.

## Static Guards

`verifyTunnelStaticGuards` protects invariants that should not be removed
accidentally:

- no Android `VpnService`, `Builder.establish()`, or `/dev/tun` in tunnel
  integration files;
- no silent direct fallback for tgnet or VoIP selected for tunnel routing;
- profile storage uses Android Keystore-backed AES-GCM and does not write
  plaintext profile contents to `mainconfig`;
- all route-mode changes go through `NetworkRouteSettings`;
- `ConnectionsManager.setProxySettings(...)` preserves the active native tunnel
  route;
- unsupported-device UI checks exist for add/import/scan/enable/select;
- parser rejects multiple peers;
- VoIP paths use `PROTOCOL_TUNNEL`, `TunnelPacketSocketFactory`, and the
  direct Go TCP/UDP tunnel socket bridge; private P2P UDP/STUN/ICE candidates
  remain available when Telegram allows P2P, but their sockets use the tunnel;
- tunnel VoIP DNS uses `tgTunnelLookupHost`, reflector TCP uses raw reflector
  framing, and WebRTC TLS socket wrapping uses `SSLAdapter` over the Go tunnel
  TCP handle;
- WireGuard and AmneziaWG Go cache/module artifacts stay out of
  `TMessagesProj/jni`;
- WireGuard and AmneziaWG use one shared Go bridge with a linker version script
  that hides non-API Go runtime symbols;
- Android Go bridge builds use the pinned managed Go 1.24.4 toolchain;
- AmneziaWG imports stay limited to the intended Go bridge dependency.

## Packaging Expectations

APK verification requires these libraries for every supported ABI:

- `libtg-wg.so`;
- `libtg-tunnel-go.so`;
- `libtg-awg.so`;

APK verification rejects entries containing:

- `gomod/`;
- `gocache/`;
- `tg_wg/go/build`;
- `tg_awg/go/build`;
- `tg_tunnel/go/build`;
- `libtg-wg-go.so`;
- `libtg-awg-go.so`;
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
- simulate startup and tunnel TCP connection failures, verify traffic fails
  closed, and verify reconnect delays cycle through 1s, 2s, 2s, 3s, 3s, 5s,
  5s before restarting the sequence; verify the profile status changes from
  `Tunnel failed` to `Tunnel failed, reconnecting` when each retry starts and
  finally to `Tunnel connected` after TCP succeeds, and verify the failure
  reason appears once in a short toast when `Tunnel failed` is entered;
- with an IPv4-only profile, trigger parallel and sequential IPv6/IPv4 tgnet
  attempts; verify an immediate IPv6 `no route to host` closes only that attempt,
  IPv4 continues through the tunnel, and no direct socket fallback occurs;
- switch Wi-Fi/LTE while each protocol is active;
- send messages/media while each protocol is active;
- start new private and group/live VoIP sessions while each protocol is active;
- repeat private and group/live session creation with `Use tunnel for calls`
  enabled and disabled;
- verify allowed private P2P and fallback relay both use the tunnel when
  `Use tunnel for calls` is enabled;
- verify Telegram datacenter traffic does not go direct while either protocol is
  enabled.

## Maintenance

- Keep Java logic unit-testable without Android runtime dependencies.
- Keep Android-only Go exports and logging behind Android build tags.
- Pin AmneziaWG to a reviewed tag or commit and update tests when the pin
  changes.
- Re-run license review when tunnel dependencies change.
- Do not place Go module/cache output under `TMessagesProj/jni`.
- Do not add SOCKS5 UDP ASSOCIATE assertions for tunnel routing; VoIP UDP tunnel
  coverage belongs to the direct tunnel socket bridge.
- Do not add real-server tests to default Gradle tasks.
- When an invariant intentionally changes, update tests, static guards, docs,
  and `AGENTS.md` together.
