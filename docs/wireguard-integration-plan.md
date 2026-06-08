# WireGuard Integration

## Purpose

Route Telegram traffic through a WireGuard server inside the Telegram process,
without Android `VpnService`, without `/dev/tun`, and without a device-level VPN
session.

The feature is always included in supported builds and user-controlled at
runtime. It supports UI-managed profiles, manual entry, config-file import,
config QR import, persistence across restarts, profile switching, profile
deletion, and setup before authorization.

## Routing

WireGuard runs as a userspace runtime based on `wireguard-go` and
`tun/netstack`. Telegram traffic reaches it through loopback proxies:

```text
tgnet TCP traffic
  -> authenticated SOCKS5 on 127.0.0.1
  -> wireguard-go tun/netstack
  -> UDP to WireGuard peer
  -> Telegram datacenters

WebRTC VoIP TCP relay traffic
  -> authenticated HTTP CONNECT on 127.0.0.1
  -> wireguard-go tun/netstack
  -> UDP to WireGuard peer
  -> Telegram relay/datacenter
```

`WireGuardManager` is the Java entry point. It loads profiles, starts/stops the
runtime through `WireGuardController`, and applies either the generated internal
proxy or the blocked fail-closed proxy to all tgnet accounts.

`ConnectionsManager.setProxySettings(...)` must preserve WireGuard proxy
authority while WireGuard is user-enabled; user proxy or direct-route writes must
not replace the internal proxy in that state.

## Route Policy

`NetworkRouteSettings` is the policy layer for route-mode changes.

- Enabling WireGuard disables ordinary proxy, proxy-for-calls, and proxy
  rotation.
- Enabling ordinary proxy disables WireGuard first.
- Disabling WireGuard stops the runtime and leaves ordinary proxy,
  proxy-for-calls, and proxy rotation disabled.
- If startup, restart, or native network refresh fails while WireGuard is
  enabled, the app applies a blocked local proxy instead of going direct.
- On devices without Keystore-backed WireGuard profile storage, WireGuard
  add/import/scan/enable actions are blocked instead of falling back to plaintext
  profile storage. Stale enabled settings remain fail-closed and disable-able.

## Profiles And Configs

Route state lives in global `mainconfig`. Profile contents live in
`wireguard_secure` as an `AES/GCM/NoPadding` encrypted blob protected by an
Android Keystore key, and require Android API 23 or newer. Profiles contain
interface key/address data, peer key/endpoint data, allowed IPs, keepalive, MTU,
optional DNS/preshared key, a stable id, and a user-visible name.

`WireGuardConfigParser` accepts one `[Interface]` and one `[Peer]`, comments,
whitespace, comma-separated values, domain endpoints, IPv4 endpoints, and
bracketed IPv6 endpoints. Multiple peers are rejected until runtime and UI
support them deliberately.

Domain peer endpoints are resolved by the Go runtime to `IP:port` before passing
the userspace config to `wireguard-go`, whose UAPI endpoint parser accepts IP
address endpoints. DNS resolution failure keeps startup failed and therefore
fail-closed.

Real keys, peer endpoints, Telegram `APP_ID`, and Telegram `APP_HASH` must not be
committed.

## UI

WireGuard is configured from the existing proxy settings flow:

- `ProxyListActivity`: combined proxy/WireGuard route controls and profile list;
- `WireGuardSettingsActivity`: manual profile editing and imported draft review;
- `LoginActivity`: pre-authorization access to the same settings flow;
- `DialogsActivity`: route status UI.

Manual entry, file import, and QR import use the same parser and review screen.
Deleting the active profile stops WireGuard.
See `docs/wireguard-ui-configuration-plan.md` for UI-specific rules.

## VoIP

When WireGuard is enabled, new private/group/live VoIP sessions read
`WireGuardManager.getProxySettings()` at creation time.

WireGuard VoIP is TCP-safe:

- WireGuard-created VoIP proxies use HTTP CONNECT.
- Private calls ignore user proxy preferences, disable P2P, and force TCP relay.
- Native WebRTC paths set the proxy, disable UDP/STUN, and filter direct
  host/reflexive ICE candidates.
- V2 reference networking keeps TCP TURN servers with `?transport=tcp` when a
  proxy is present.

Active sessions are not dynamically rerouted by UI changes. UDP ASSOCIATE is not
implemented.

## Build

WireGuard native integration is always built.

- `libtg-wg.so` wraps JNI/C++ integration.
- `libtg-wg-go.so` contains the Go WireGuard bridge.
- Keystore-backed secure storage gates Java UI availability.
- Go module/cache output must stay outside `TMessagesProj/jni` because Android
  Gradle scans JNI folders recursively.

## Key Files

- Java runtime and settings:
  `WireGuardManager`, `WireGuardController`, `WireGuardSettings`,
  `WireGuardSecureStore`, `WireGuardProfile`, `WireGuardConfigParser`,
  `NetworkRouteSettings`.
- UI:
  `ProxyListActivity`, `WireGuardSettingsActivity`, `LoginActivity`,
  `DialogsActivity`.
- Tgnet enforcement:
  `ConnectionsManager`.
- Go/JNI runtime:
  `TMessagesProj/jni/tg_wg/go/`, `TMessagesProj/jni/tg_wg/tg_wg_jni.cpp`,
  `TMessagesProj/jni/CMakeLists.txt`.
- VoIP routing:
  `VoIPService`, `Instance`, `NativeInstance`, `LivePlayer`, native
  `tgcalls` network managers.

## Verification

Primary automated and manual checks are in `docs/wireguard-testing-plan.md`.

Manual release validation should include a real WireGuard server, route
switching, Wi-Fi/LTE transitions, file and QR import, private/group/live VoIP,
profile deletion, startup failure, and packet capture confirming no direct
Telegram datacenter traffic while WireGuard is enabled.

## Known Limits

- UDP ASSOCIATE is not implemented.
- Non-`tgnet` networking beyond audited VoIP/live paths must be reviewed before
  being treated as mandatory WireGuard-routed.
- `wireguard-go/tun/netstack` adds binary size and CPU overhead.
- Release requires license review for WireGuard-related dependencies and this
  Telegram client codebase.
