# Repository Notes for Tunnel Integration

This repository contains an Android Telegram client with in-process WireGuard
and AmneziaWG integrations.

## Feature Goal

Route Telegram traffic through a WireGuard or AmneziaWG server without using
Android `VpnService`, without creating a TUN interface on the device, and
without a device-level VPN session.

The current implementation supports UI-managed WireGuard and AmneziaWG profiles
in the existing proxy settings flow. Users can add profiles manually, import
standard WireGuard or AmneziaWG config files, scan config QR codes, persist
settings across app restarts, enable a tunnel before authorization, switch
between multiple tunnel profiles, and delete saved profiles.

Current routing shape:

```text
tgnet TCP traffic
  -> native tgnet tunnel TCP transport
  -> direct TCP socket API in wireguard-go tun/netstack
  -> UDP to configured WireGuard peer
  -> Telegram datacenters

WebRTC VoIP relay traffic
  -> native tunnel packet socket factory
  -> direct TCP/UDP socket API in wireguard-go tun/netstack
  -> UDP to configured WireGuard peer
  -> Telegram relay/datacenter

Private-call P2P traffic, when Telegram allows P2P
  -> native tunnel packet socket factory
  -> direct UDP socket API in wireguard-go tun/netstack
  -> UDP to configured WireGuard peer
  -> Telegram peer path
```

AmneziaWG uses the same direct tgnet TCP and VoIP TCP/UDP tunnel socket shape
through `amneziawg-go` tun/netstack, then sends obfuscated UDP to the configured
AmneziaWG peer.

## Hard Constraints

- Do not introduce Android `VpnService`, `Builder.establish()`, `/dev/tun`, or a
  device-level VPN session for this feature.
- Do not commit real WireGuard keys, endpoints, Telegram `APP_ID`, or Telegram
  `APP_HASH`.
- When a tunnel is user-enabled, tgnet and VoIP traffic selected for tunnel
  routing must fail closed. Do not add a silent direct fallback. Explicitly
  disabled tunnel-for-calls routing is a deliberate direct route.
- Existing proxy behavior must stay mutually exclusive with WireGuard and
  AmneziaWG through `NetworkRouteSettings`.
- Tunnel profiles must be stored with Android Keystore-backed encryption on
  supported devices. Do not add plaintext SharedPreferences fallback for
  profile private keys, preshared keys, endpoints, or AmneziaWG masking fields.
- On devices without Keystore-backed tunnel profile storage, UI must not
  enable/add/import/scan tunnel profiles. Stale enabled settings should remain
  fail-closed and disable-able.
- UDP ASSOCIATE is not implemented. Tunnel-routed VoIP UDP uses the direct
  tunnel socket API, not SOCKS5 UDP ASSOCIATE.

## Code Map

- Java configuration and orchestration:
  - `TMessagesProj/src/main/java/org/telegram/messenger/TunnelProtocol.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/TunnelConfigParser.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/TunnelManager.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/TunnelProxySettings.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/TunnelVoipRouting.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardUserspaceConfig.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardManager.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/TunnelController.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardProxySettings.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardVoipRouting.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardProfile.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardSettings.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardSecureStore.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardConfigParser.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/NetworkRouteSettings.java`
- UI:
  - `TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/WireGuardSettingsActivity.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/CameraScanActivity.java`
- Telegram runtime proxy enforcement:
  - `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`
- Startup hook:
  - `TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`
- Shared Go tunnel runtime:
  - `TMessagesProj/jni/tg_tunnel/go/`
  - exports `tgWg*`, `tgAwg*`, and `tgTunnel*` from one
    `libtg-tunnel-go.so`
  - pinned AmneziaWG upstream module: `github.com/amnezia-vpn/amneziawg-go v0.2.18`
  - upstream Go requirement: `go 1.24.4`
  - `TMessagesProj/jni/CMakeLists.txt`
- JNI WireGuard runtime:
  - `TMessagesProj/jni/tg_wg/tg_wg_jni.cpp`
  - `TMessagesProj/jni/CMakeLists.txt`
- JNI AmneziaWG runtime:
  - `TMessagesProj/jni/tg_awg/tg_awg_jni.cpp`
  - `TMessagesProj/jni/CMakeLists.txt`
- Private VoIP routing:
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/Instance.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/NativeInstance.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java`
  - `TMessagesProj/jni/voip/org_telegram_messenger_voip_Instance.cpp`
  - `TMessagesProj/jni/voip/tgcalls/Instance.h`
  - `TMessagesProj/jni/voip/tgcalls/TunnelPacketSocketFactory.cpp`
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
- Docs:
  - `docs/tunnel-integration.md`
  - `docs/tunnel-ui-configuration.md`
  - `docs/tunnel-testing.md`

## Runtime Invariants

- `TunnelManager` is the Java entry point. `WireGuardManager` is a compatibility
  facade. Keep runtime proxy selection centralized in `TunnelManager` and
  `TunnelController`.
- Native tunnel lifecycle work and reconnect attempts must run on the dedicated
  serial tunnel lifecycle queue, never on the Android UI thread. Do not hold the
  controller state lock across native start, stop, or network-refresh calls.
- Reconnect backoff resets after tgnet reports a successfully established tunnel
  TCP connection, not merely after the userspace runtime starts.
- `NetworkRouteSettings` is the route-mode policy layer for proxy/tunnel mutual
  exclusion.
- `ConnectionsManager.setProxySettings(...)` must not clear or replace the
  native tunnel route while a tunnel is enabled.
- If tunnel startup, restart, or network-refresh recovery fails, apply blocked
  tunnel route state to all accounts instead of going direct.
- Network changes should call the native refresh path. If native bind refresh
  fails, restart the runtime and reapply proxy settings; if restart fails, fail
  closed.
- tgnet tunnel routing uses the direct native tunnel TCP transport, not a
  loopback SOCKS/HTTP proxy.
- Profile contents are encrypted separately from `mainconfig`; only route state
  such as enabled/current profile id and tunnel-for-calls belongs in global
  preferences.
- AmneziaWG `Jc/Jmin/Jmax`, `S1..S4`, `H1..H4`, and `I1..I5` fields are profile
  contents and must stay inside the encrypted profile blob.
- Go module/cache output must stay outside `TMessagesProj/jni`; Android Gradle
  scans JNI folders recursively and may otherwise package dependency `.so`
  files.
- WireGuard and AmneziaWG must be built into one Go c-shared library
  (`libtg-tunnel-go.so`) so the app process has a single Go runtime. Do not
  reintroduce separate `libtg-wg-go.so` and `libtg-awg-go.so` libraries.
- Direct tunnel socket handles must be tied to the active runtime generation;
  stale handles from a stopped/restarted runtime must fail closed.
- Tunnel TCP writes must write complete protocol frames or fail; do not treat
  partial TCP writes as successful VoIP sends.

## UI Invariants

- Tunnel settings live in the combined proxy settings flow.
- The login/start screen must expose the proxy/tunnel settings entry before
  authorization.
- Enabling WireGuard or AmneziaWG disables ordinary proxy, proxy-for-calls, and
  proxy rotation.
- Enabling ordinary proxy disables the active tunnel first.
- Disabling a tunnel leaves ordinary proxy, proxy-for-calls, and proxy rotation
  disabled.
- `Use Proxy For Calls` must not be available while a tunnel is active.
- `Use Tunnel For Calls` must be available while a tunnel is active and must
  default to enabled for existing and new installs.
- Manual entry, config-file import, and QR-code import must use the same
  validation rules.
- Deleting a saved tunnel profile requires user confirmation.
- Deleting the active tunnel profile must stop the active runtime.
- Multiple `[Peer]` sections remain unsupported until runtime and UI support them
  deliberately.

## VoIP Invariants

- Tunnel-enabled private calls must ignore user proxy preferences.
- `Use Tunnel For Calls` controls tunnel socket routing for private-call P2P,
  relay, group/conference calls, and live/group streaming. It applies only to
  newly created VoIP sessions.
- Private-call P2P availability must follow Telegram's requested P2P value and
  must not be disabled by tunnel routing. When tunnel-for-calls is enabled,
  P2P host/reflexive UDP candidates must use the tunnel socket API, not direct
  device UDP.
- When tunnel-for-calls is enabled, private-call relay may use UDP or TCP, but
  both must use the tunnel socket API and must not bypass the tunnel.
- Tunnel-created VoIP proxies use `Instance.Proxy.PROTOCOL_TUNNEL`. Keep
  default/user `Instance.Proxy` behavior as SOCKS5 unless deliberately changing
  user proxy semantics.
- Native WebRTC paths must use `TunnelPacketSocketFactory` and the synthetic
  tunnel `NetworkManager` when a tunnel proxy is present. They must not call
  `BasicPortAllocator::set_proxy(...)` for tunnel proxies.
- Native WebRTC DNS resolution in tunnel mode must use the `tgTunnelLookupHost`
  bridge and tunnel netstack DNS, not the device/system resolver.
- Native private WebRTC paths must keep UDP/STUN and host/reflexive ICE
  candidates available when P2P is enabled, while routing their sockets through
  the tunnel socket API.
- If P2P is disabled, native private WebRTC paths must retain relay-only
  behavior by disabling UDP/STUN and filtering direct ICE candidates.
- Native group/live WebRTC paths must use tunnel sockets in tunnel mode.
- Telegram reflector TCP over tunnel must use the raw reflector framing
  (`0xeeeeeeee` prologue and little-endian 32-bit packet lengths), not generic
  WebRTC 16-bit TCP packet framing.
- WebRTC TLS socket wrapping for tunnel TCP sockets must use `SSLAdapter`
  over the Go tunnel TCP handle, not a device socket or a separate Go TLS stack.
  Raw reflector TCP does not use TLS wrapping.
- Private VoIP has multiple native paths. When changing routing, audit:
  - legacy/private `NetworkManager.cpp`;
  - V2 custom `NativeNetworkingImpl.cpp`;
  - V2 reference `InstanceV2ReferenceImpl.cpp`;
  - group `GroupNetworkManager.cpp`.
- Private V2 reference networking must retain TCP TURN servers with
  `?transport=tcp` when a proxy is present.
- Active VoIP sessions are not dynamically rerouted by UI toggles. New sessions
  read the current tunnel settings at creation time.

## Tests and Verification

Fast no-emulator checks:

```bash
./gradlew --no-daemon :TMessagesProj:testDebugUnitTest :TMessagesProj:testWireGuardGo :TMessagesProj:testAmneziaWGGo :TMessagesProj:verifyTunnelStaticGuards
```

Packaging check:

```bash
./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug
```

Important details:

- `:TMessagesProj_App:assembleAfatDebug` is expected to run JVM unit tests,
  WireGuard Go tests, AmneziaWG Go tests, static guards, and APK packaging
  verification.
- Static guards are part of the safety net. If an invariant intentionally
  changes, update tests, guards, docs, and this file together.
- Long Gradle builds can take many minutes. Do not kill a running Gradle build
  unless explicitly asked.
- Prefer no-emulator coverage for glue logic. Real WireGuard/AmneziaWG server
  validation, packet captures, and active call checks belong to manual/device
  smoke testing.

## Documentation Policy

When changing this feature, update docs in the same change if behavior,
architecture, build commands, test coverage, or known risks change.
