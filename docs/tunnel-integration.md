# Tunnel Integration

## Purpose

Telegram traffic can be routed through WireGuard or AmneziaWG inside the
Telegram process, without Android `VpnService`, without `/dev/tun`, and without
a device-level VPN session.

Users manage both protocol types from the existing proxy settings flow. They can
add profiles manually, import config files, scan config QR codes, persist
settings across restarts, enable a tunnel before authorization, switch saved
profiles, and delete profiles.

## Protocols

- WireGuard uses `wireguard-go` with `tun/netstack`.
- AmneziaWG uses `github.com/amnezia-vpn/amneziawg-go v0.2.18` with
  `tun/netstack`; the pinned module requires Go `1.24.4`.

AmneziaWG is built as a sibling runtime, not as a replacement for WireGuard.
The separate native libraries reduce regression risk for existing WireGuard
profiles and keep packaging checks explicit.

Reviewed upstream references:

- `https://github.com/amnezia-vpn/amneziawg-go`
- `https://docs.amnezia.org/documentation/amnezia-wg/`

## Routing

Both protocols use direct tunnel socket bridges for `tgnet` and WebRTC VoIP:

```text
tgnet TCP traffic
  -> native tgnet tunnel TCP transport
  -> direct TCP socket API in wireguard-go or amneziawg-go tun/netstack
  -> UDP to the configured peer
  -> Telegram datacenters

WebRTC VoIP relay and P2P traffic, when Use Tunnel For Calls is enabled
  -> Instance.Proxy.PROTOCOL_TUNNEL marker
  -> native TunnelPacketSocketFactory
  -> direct TCP/UDP socket API in wireguard-go or amneziawg-go tun/netstack
  -> UDP to the configured peer
  -> Telegram relay/datacenter/peer path
```

The app never creates an Android VPN session. When a tunnel is user-enabled,
tgnet and VoIP traffic selected for tunnel routing fail closed. Explicitly
disabled tunnel-for-calls routing is a deliberate direct route, not fallback
behavior.

## Route Policy

`NetworkRouteSettings` is the policy layer for route-mode changes.

- Ordinary proxy, WireGuard, and AmneziaWG are mutually exclusive.
- Enabling any tunnel disables ordinary proxy, proxy-for-calls, and proxy
  rotation.
- Enabling ordinary proxy disables the active tunnel first.
- Disabling a tunnel leaves ordinary proxy, proxy-for-calls, and proxy rotation
  disabled.
- `Use Tunnel For Calls` is persisted independently and defaults to enabled.
- If startup, restart, or native network refresh fails while a tunnel is enabled,
  the app applies blocked tunnel route state instead of going direct.
- On devices without Keystore-backed profile storage, add/import/scan/enable and
  profile selection actions are blocked. Stale enabled settings remain
  fail-closed and disable-able.

`ConnectionsManager.setProxySettings(...)` must preserve the native tunnel route
while a tunnel is enabled; user proxy or direct-route writes must not replace it.

## Java Architecture

The public Java entry point is protocol-neutral:

- `TunnelManager`: start/stop/select/save/delete/import, active proxy settings,
  network changes, and support gating.
- `TunnelProtocol`: `WIREGUARD` and `AMNEZIA_WG`.
- `TunnelConfigParser`: protocol-neutral parser wrapper.
- `TunnelProxySettings` and `TunnelVoipRouting`: tunnel marker metadata and VoIP
  route policy.
- `WireGuardManager`, `WireGuardProxySettings`, and `WireGuardVoipRouting`:
  compatibility facades for older call sites.

The existing `WireGuardProfile`, `WireGuardSettings`,
`WireGuardSecureStore`, `WireGuardConfigParser`, and
`WireGuardUserspaceConfig` classes remain the storage/parser/config-generation
implementation. They now carry both WireGuard common fields and AmneziaWG fields
to avoid a high-risk rename across UI and persistence code.

`WireGuardController` owns runtime start/restart/stop, fail-closed tunnel route
application, and network-refresh recovery.
`TunnelManager` selects the native runtime from the active profile protocol.

## Profile Storage

Route state lives in global `mainconfig`:

- `tunnel_enabled`
- `tunnel_current_profile_id`
- `tunnel_voip_enabled`

Legacy `wireguard_enabled` and `wireguard_current_profile_id` are still read and
written during the compatibility period.

Profile contents live in `wireguard_secure` as an encrypted serialized blob
protected by Android Keystore and `AES/GCM/NoPadding`. Android API 23 or newer
is required for profile storage support. There is no plaintext
SharedPreferences fallback.

Encrypted profile schema v2 stores the protocol and AmneziaWG fields. Schema v1
profiles are read as `protocol=WIREGUARD`.

Sensitive profile contents include:

- private key and preshared key;
- peer endpoint;
- local addresses, DNS, allowed IPs, MTU, keepalive;
- AmneziaWG `Jc/Jmin/Jmax`, `S1..S4`, `H1..H4`, and `I1..I5`.

Do not log sensitive fields, show them in profile lists, store them in plaintext
preferences, or commit real profiles.

## Config Parsing

Only one `[Interface]` and one `[Peer]` section are supported. Multiple peers
remain unsupported until runtime and UI support them deliberately.

Common WireGuard fields:

```ini
[Interface]
PrivateKey =
Address =
DNS =
MTU =

[Peer]
PublicKey =
PresharedKey =
AllowedIPs =
Endpoint =
PersistentKeepalive =
```

AmneziaWG fields are accepted only in `[Interface]`:

```ini
Jc =
Jmin =
Jmax =
S1 =
S2 =
S3 =
S4 =
H1 =
H2 =
H3 =
H4 =
I1 =
I2 =
I3 =
I4 =
I5 =
```

Parser behavior:

- comments beginning with `#` or `;` are accepted;
- whitespace is trimmed;
- `Address`, `DNS`, and `AllowedIPs` are comma-separated;
- endpoints can use domains, IPv4 addresses, or bracketed IPv6 addresses;
- automatic import sets `protocol=AMNEZIA_WG` when any AmneziaWG-specific key is
  present;
- standard configs without AmneziaWG keys remain `protocol=WIREGUARD`;
- forced AmneziaWG parsing can create an AmneziaWG profile from a standard
  WireGuard-shaped config;
- forced WireGuard parsing rejects AmneziaWG keys instead of silently dropping
  obfuscation settings.

Validation:

- common WireGuard keys, endpoint, addresses, allowed IPs, MTU, and keepalive use
  existing validation;
- if any junk-packet field is set, `Jc`, `Jmin`, and `Jmax` must all be positive
  and `Jmin <= Jmax`;
- `S1..S4` must be non-negative;
- `H1..H4` accept an unsigned 32-bit value or `start-end` with `start <= end`;
- non-empty `H1..H4` ranges must not overlap;
- `I1..I5` follow the pinned `amneziawg-go` custom packet grammar: `b`, `t`,
  `r`, `rd`, `rc`, `d`, `ds`, and `dz` tags. `b` requires even-sized hex and
  `r`/`rd`/`rc`/`dz` lengths are bounded by Java validation.

AmneziaWG UAPI device keys are emitted before the first `public_key=` line,
because `public_key` switches the upstream UAPI parser from device configuration
to peer configuration. Zero or unset AmneziaWG values are omitted.

Domain peer endpoints are resolved by the Go runtime to `IP:port` before calling
the upstream UAPI parser. DNS resolution failure keeps startup failed and
therefore fail-closed.

## Native Runtime And Build

Native libraries:

- `libtg-wg.so`: JNI/C++ wrapper for WireGuard.
- `libtg-tunnel-go.so`: shared Go bridge for WireGuard and AmneziaWG.
- `libtg-awg.so`: JNI/C++ wrapper for AmneziaWG.

WireGuard and AmneziaWG share one Go `c-shared` library. Loading two independent
Go shared libraries in the same Android process can corrupt the Go runtime when
users switch between protocol profiles, so `libtg-wg-go.so` and
`libtg-awg-go.so` must not be rebuilt or packaged. The shared bridge is built
with a linker version script that exports only `tgWg*`, `tgAwg*`, and
`tgTunnel*` C API symbols; Go runtime and cgo helper symbols remain local.

Android bridge builds use the managed Go 1.24.4 toolchain from the native build
directory instead of whatever `go` binary is first on `PATH`.

Bridge behavior shared by both protocols:

- `netstack.CreateNetTUN`;
- direct TCP tunnel socket exports for tgnet;
- direct TCP/UDP tunnel socket exports for WebRTC VoIP;
- tunnel DNS lookup export for native WebRTC hostname resolution;
- endpoint DNS preprocessing;
- bind refresh on network change;
- host-side endpoint and tunnel socket tests.

Go module/cache output must stay outside `TMessagesProj/jni`; Android Gradle
scans JNI directories recursively and may otherwise package dependency shared
objects or cache files.

`TMessagesProj_App:assembleAfatDebug` verifies that tunnel libraries are present
for every supported ABI and that Go cache/module artifacts are absent from the
APK.

## VoIP

New private/group/live VoIP sessions read
`TunnelManager.getVoipProxySettings()` at creation time.

Tunnel VoIP behavior:

- Java passes an internal `Instance.Proxy.PROTOCOL_TUNNEL` marker, not a
  loopback proxy endpoint;
- private calls ignore user proxy preferences while a tunnel is active;
- `Use Tunnel For Calls` controls private P2P, private relay,
  group/conference, and live/group streaming routing and defaults to enabled;
- Telegram-authorized private P2P remains enabled, but host/reflexive UDP
  candidates use the tunnel socket API while tunnel-for-calls is enabled;
- private relay may use UDP or TCP while tunnel-for-calls is enabled; both
  socket types go through `TunnelPacketSocketFactory`;
- native WebRTC paths use `TunnelPacketSocketFactory` and a synthetic tunnel
  `NetworkManager` instead of `BasicPortAllocator::set_proxy(...)` for tunnel
  proxies;
- WebRTC hostname resolution in tunnel mode uses the Go tunnel netstack DNS via
  `tgTunnelLookupHost`;
- private paths keep UDP/STUN and host/reflexive ICE candidates when P2P is
  enabled, with all sockets opened through the tunnel bridge;
- private paths retain relay-only filtering when P2P is disabled;
- group/live paths use the same tunnel socket factory in tunnel mode;
- reflector TCP over tunnel uses Telegram raw TCP framing, while TURN TCP keeps
  STUN/TURN framing;
- WebRTC TLS socket wrapping in tunnel mode uses `SSLAdapter` over the Go
  tunnel TCP handle; raw reflector TCP does not use TLS wrapping;
- V2 reference networking keeps TCP TURN servers with `?transport=tcp` when a
  tunnel or ordinary proxy is present.

Active sessions are not dynamically rerouted by UI changes. UDP ASSOCIATE is not
implemented; tunnel VoIP UDP uses the direct tunnel socket bridge instead.

## Key Files

- Java runtime/settings:
  `TunnelManager`, `TunnelProtocol`, `TunnelProxySettings`, `TunnelVoipRouting`,
  `TunnelConfigParser`, `WireGuardManager`, `WireGuardController`,
  `WireGuardSettings`, `WireGuardSecureStore`, `WireGuardProfile`,
  `WireGuardConfigParser`, `WireGuardUserspaceConfig`, `NetworkRouteSettings`.
- UI:
  `ProxyListActivity`, `WireGuardSettingsActivity`, `LoginActivity`,
  `DialogsActivity`, `LaunchActivity`, `CameraScanActivity`.
- Tgnet enforcement:
  `ConnectionsManager`.
- Shared Go native runtime:
  `TMessagesProj/jni/tg_tunnel/go/`.
- WireGuard JNI wrapper:
  `TMessagesProj/jni/tg_wg/tg_wg_jni.cpp`.
- AmneziaWG JNI wrapper:
  `TMessagesProj/jni/tg_awg/tg_awg_jni.cpp`.
- VoIP routing:
  `VoIPService`, `Instance`, `NativeInstance`, `LivePlayer`, native `tgcalls`
  network managers.
- Build and guards:
  `TMessagesProj/build.gradle`, `TMessagesProj_App/build.gradle`,
  `TMessagesProj/jni/CMakeLists.txt`.

## Known Limits

- UDP ASSOCIATE is not implemented.
- Multiple `[Peer]` sections are unsupported.
- Non-`tgnet` networking beyond audited VoIP/live paths must be reviewed before
  being treated as mandatory tunnel-routed.
- `wireguard-go` and `amneziawg-go` increase binary size and CPU overhead.
- Real WireGuard and AmneziaWG server validation is required for release
  confidence.
- License review is required for tunnel dependencies and this Telegram client
  codebase.

See `docs/tunnel-ui-configuration.md` for UI behavior and
`docs/tunnel-testing.md` for verification.
