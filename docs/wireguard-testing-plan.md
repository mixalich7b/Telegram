# WireGuard Test Plan

## Goal

Cover the Telegram-to-WireGuard integration without requiring an emulator for the primary verification path.

The tests must validate our glue code and build integration only. They must not duplicate coverage for `wireguard-go`, gVisor/netstack, Android `VpnService`, or Telegram's existing tgnet/SOCKS implementation.

## Required Layers

### JVM Unit Tests

Use local Android Gradle unit tests in `TMessagesProj/src/test/java` with JUnit4.

Primary checks:

- WireGuard base64 keys are converted to userspace IPC hex correctly.
- Invalid keys fail before native startup.
- Userspace IPC config contains required peer/interface fields.
- Validation catches missing required config fields.
- Controller logic is fail-closed:
  - disabled config does not start native runtime;
  - enabled + native failure applies blocked local proxy;
  - enabled + native success applies the generated internal SOCKS proxy;
  - applying proxy settings for all accounts cannot be bypassed by later user proxy changes;
  - repeated start does not create multiple runtimes;
  - network change notification is sent only while running;
  - network refresh failure restarts the runtime and reapplies proxy settings;
  - restart failure applies blocked proxy settings.
- VoIP routing logic:
  - WireGuard proxy disables private-call P2P;
  - WireGuard proxy forces TCP relay endpoint selection in the current safe mode;
  - blocked WireGuard proxy still keeps VoIP in fail-closed routing mode.

### Go Unit Tests

Use standard `go test ./...` in `TMessagesProj/jni/tg_wg/go`.

Primary checks:

- Address parsing accepts IPv4, IPv6, and CIDR values.
- Empty address entries are ignored.
- Invalid address entries fail.
- SOCKS5 handshake supports username/password auth.
- Wrong credentials and unsupported methods/commands fail.
- IPv4, IPv6, and domain targets are parsed correctly.
- The SOCKS bridge can proxy bytes through a fake dialer without a real WireGuard peer.
- HTTP CONNECT handshake supports proxy authorization for WebRTC TCP proxy routing.
- HTTP CONNECT success/failure responses are emitted by the connection handler after the fake dialer succeeds/fails.
- Network change handling calls `BindUpdate()` on an active fake device.
- `BindUpdate()` success/failure status is returned to Java through the native ABI.

### Native / Packaging Checks

Use Gradle tasks, no emulator:

- Static guard checks:
  - hardcoded WireGuard default remains disabled;
  - no committed config keys/endpoints are present by default;
  - our WireGuard integration code does not reference `VpnService`, `Builder.establish`, or `/dev/tun`;
  - `ConnectionsManager.setProxySettings(...)` keeps WireGuard proxy settings authoritative when enabled;
  - private VoIP setup references WireGuard routing decisions;
  - WireGuard-created VoIP proxies use `PROTOCOL_HTTP_CONNECT`, while the default `Instance.Proxy` constructor remains SOCKS5 for user proxies;
  - `NativeInstance.makeGroup(...)`, `VoIPService`, `LivePlayer`, and group native networking keep the group-call WireGuard proxy path wired;
  - native private/group WebRTC managers contain `PROXY_HTTPS` support for the WireGuard HTTP CONNECT path;
  - native private/group WebRTC managers, including private VoIP V2 custom and reference networking paths, filter both `CF_HOST` and `CF_REFLEXIVE` candidates in WireGuard HTTP CONNECT mode so direct ICE paths cannot bypass the proxy;
  - private VoIP V2 paths keep TCP candidates enabled and retain TCP TURN servers when a proxy is present, because HTTP CONNECT relay sockets require TCP;
  - the Go WireGuard proxy contains `httpConnectHandshake(...)`;
  - `TMessagesProj/jni/tg_wg/go/build` is absent because Go cache inside `jni` is packaged incorrectly.
- APK packaging checks:
  - with `TG_WIREGUARD=false`, the APK must not contain `libtg-wg.so` or `libtg-wg-go.so`;
  - with `TG_WIREGUARD=true`, the APK must contain both libraries for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`;
  - no APK must contain Go cache/module paths such as `gomod`, `gocache`, `tg_wg/go/build`, or `vdso_*.so`.

## Build Integration

The existing working build command is:

```bash
./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug
```

New no-emulator checks must run with this task:

- `:TMessagesProj:testDebugUnitTest`
- `:TMessagesProj:testWireGuardGo`
- `:TMessagesProj:verifyWireGuardStaticGuards`
- `:TMessagesProj_App:verifyWireGuardAfatDebugPackaging`

Packaging verification is tied to the current `TG_WIREGUARD` Gradle property:

- if `-PTG_WIREGUARD=true`, require WireGuard libraries;
- otherwise require their absence.

## Maintenance Notes

- Keep Java unit-testable logic free from Android runtime-only APIs. Use `java.util.Base64` in pure helpers rather than `android.util.Base64`.
- Keep Go host-testable code free from Android cgo imports. Android C exports and Android logging must stay behind Android build tags.
- Do not place Go module/cache output under `TMessagesProj/jni`; Android Gradle scans `sourceSets.main.jniLibs.srcDirs` recursively and may package dependency `.so` files.
- The Gradle Go test task must set `GOCACHE` and `GOMODCACHE` outside `build/`; it currently uses the root `.gradle/wireguard-go-test`.
- The Gradle Go test task uses `go test -count=1 ./...` so a full forced build actually runs Go tests instead of only reporting Go's internal test cache.
- When checking APK ZIP entries from Groovy, convert interpolated expected paths to `String` before `Set.contains(...)`. `GString` keys can fail equality/hash lookup against ZIP entry `String` values.
- Current MTProto coverage uses SOCKS5 `CONNECT`. Current WebRTC VoIP safe mode uses HTTP `CONNECT` because this WebRTC tree's `BasicPacketSocketFactory` has an HTTPS proxy adapter but does not implement SOCKS5 client proxy sockets.
- Do not add UDP ASSOCIATE assertions until the Go proxy implements SOCKS5 command `0x03`.
- Do not add tests that require a real WireGuard server to the default build task. Real-network validation belongs to a separate manual/device smoke suite.

## Current Implementation Status

- Plan created.
- JVM unit tests added under `TMessagesProj/src/test/java/org/telegram/messenger`.
- Java production code now delegates pure config generation to `WireGuardUserspaceConfig` and fail-closed state logic to `WireGuardController`.
- Java production code now delegates VoIP routing decisions to `WireGuardVoipRouting`.
- Go Android cgo exports/logging are split behind Android build tags so host-side `go test` can run without Android headers.
- Go unit tests added under `TMessagesProj/jni/tg_wg/go`.
- Review remediation implemented:
  - user proxy changes cannot override WireGuard runtime proxy settings while enabled;
  - network changes call `BindUpdate()` and Java restarts/fails closed on native refresh failure;
  - private VoIP and group/live-call native instances receive the WireGuard proxy in TCP-safe mode.
- Self-review remediation:
  - found that WebRTC `BasicPacketSocketFactory` ignores `PROXY_SOCKS5` for client TCP sockets;
  - WireGuard-created VoIP proxies now use HTTP CONNECT, and the local Go proxy accepts HTTP CONNECT alongside SOCKS5;
  - Go tests cover HTTP CONNECT auth, success copy path, and dial failure response.
- Final self-review remediation:
  - native private/group WebRTC managers now drop host and reflexive ICE candidates in WireGuard HTTP CONNECT mode;
  - private VoIP V2 custom and reference networking now apply the same proxy, TCP relay, TCP TURN retention, and no-host/no-reflexive invariants;
  - static guards verify these invariants without an emulator.
- `:TMessagesProj_App:assembleAfatDebug` now depends on JVM unit tests, Go tests, and static guards, then runs APK packaging verification.
- Verification results:
  - `go test -count=1 ./...` with project-local Go caches: passed after self-review HTTP CONNECT remediation.
  - `:TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:verifyWireGuardStaticGuards`: passed after final host-candidate remediation.
  - `:TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=true --rerun-tasks`: passed after self-review HTTP CONNECT remediation; Go tests, JVM tests, static guards, native CMake builds for four ABI, and APK packaging verification all executed.
  - `:TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=true`: passed after final host-candidate remediation; packaging check confirmed `libtg-wg.so` and `libtg-wg-go.so` for all four ABI.
  - `:TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=false`: passed; packaging check confirmed WireGuard libraries are absent.
