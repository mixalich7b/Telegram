# WireGuard Integration

## Goal

Route Telegram traffic through a WireGuard server from inside the Telegram
process, without Android `VpnService`, without `/dev/tun`, and without creating a
device-level VPN session.

The current implementation supports user-managed WireGuard profiles, config-file
import, persistence across app restarts, and mutual exclusion with the existing
Telegram proxy feature.

## Architecture

WireGuard runs as an in-process userspace runtime based on `wireguard-go` and
`tun/netstack`. It exposes loopback proxy endpoints to the existing Telegram
networking code:

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

`WireGuardManager` is the Java entry point. It reads persisted profiles,
starts/stops the runtime through `WireGuardController`, and applies either the
generated internal proxy or the blocked fail-closed proxy to all tgnet accounts.

`ConnectionsManager.setProxySettings(...)` remains a low-level safety guard:
when WireGuard is user-enabled it cannot clear or replace the internal
WireGuard proxy with a user proxy or direct route.

## UI And Persistence

WireGuard configuration is integrated into the existing proxy settings screen:

- `ProxyListActivity` shows proxy and WireGuard route controls in one place.
- `WireGuardSettingsActivity` edits a profile manually and reviews imported
  config files before saving.
- `LoginActivity` keeps the proxy/WireGuard settings entry visible before
  authorization, so a user can configure WireGuard before logging in.
- `DialogsActivity` and route status UI consider WireGuard as an active route.

WireGuard profiles are stored in global `mainconfig`, matching the current proxy
password storage model. Real keys and endpoints must never be committed to the
repository. `WireGuardConfig.ENABLED` remains `false` by default and committed
config fields stay empty.

The profile model is:

- interface private key;
- interface addresses;
- optional DNS;
- MTU;
- peer public key;
- optional preshared key;
- endpoint;
- allowed IPs;
- persistent keepalive;
- user-visible profile name and stable id.

The standard config parser accepts one `[Interface]` and one `[Peer]`, comments,
whitespace, comma-separated values, domain endpoints, IPv4 endpoints, and
bracketed IPv6 endpoints. The Go runtime resolves domain peer endpoints to
`IP:port` before passing the userspace config to `wireguard-go`, whose UAPI
endpoint parser only accepts IP address endpoints. DNS resolution failure keeps
startup failed and therefore uses the existing fail-closed route. Multiple peers
are rejected until the runtime and UI deliberately support them.

## Route Policy

All user-facing route changes go through `NetworkRouteSettings`.

- Enabling WireGuard disables ordinary proxy, proxy-for-calls, and proxy
  rotation.
- Enabling an ordinary proxy disables WireGuard first.
- Disabling WireGuard clears the internal runtime proxy and leaves ordinary proxy
  disabled. Traffic then uses the normal direct path unless the user explicitly
  enables a proxy.
- In `TG_WIREGUARD=false` builds, UI add/import/enable actions are blocked with
  an unavailable message. Stale persisted enabled settings remain fail-closed and
  can still be disabled by the user.

If WireGuard startup, restart, or native network refresh fails while user-enabled,
the app applies a blocked local proxy to all accounts instead of going direct.

## VoIP And Live Routing

When WireGuard is enabled, private and group/live VoIP setup reads
`WireGuardManager.getProxySettings()`.

Current VoIP mode is TCP-safe:

- WireGuard-created VoIP proxies use HTTP CONNECT.
- Private calls ignore user proxy preferences while WireGuard is active.
- P2P is disabled for WireGuard calls.
- TCP relay is forced.
- Native WebRTC paths call `BasicPortAllocator::set_proxy(...)`.
- Native WebRTC paths disable UDP/STUN and filter direct host/reflexive ICE
  candidates in WireGuard HTTP CONNECT mode.
- Private V2 reference networking keeps TCP TURN servers with `?transport=tcp`
  when a proxy is present.

Active VoIP sessions are not dynamically rerouted when the user changes
WireGuard settings. They continue with the route captured at instance creation;
new sessions read the current settings.

UDP ASSOCIATE is not implemented. Do not assume UDP relay works through
WireGuard yet.

## Build Integration

WireGuard native integration is opt-in through `TG_WIREGUARD`.

- Default builds must work with WireGuard native libraries absent.
- `BuildConfig.TG_WIREGUARD_ENABLED` exposes native availability to Java UI.
- `libtg-wg.so` wraps JNI/C++ integration.
- `libtg-wg-go.so` contains the Go WireGuard bridge.
- Go module/cache output must stay outside `TMessagesProj/jni`; Android Gradle
  scans JNI folders recursively and may otherwise package dependency `.so`
  files.

## Key Files

- Java orchestration:
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardManager.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardController.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardSettings.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardProfile.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardConfigParser.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/NetworkRouteSettings.java`
- Runtime proxy enforcement:
  - `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`
- UI:
  - `TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/WireGuardSettingsActivity.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java`
- Go/JNI runtime:
  - `TMessagesProj/jni/tg_wg/go/`
  - `TMessagesProj/jni/tg_wg/tg_wg_jni.cpp`
  - `TMessagesProj/jni/CMakeLists.txt`
- VoIP routing:
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/Instance.java`
  - `TMessagesProj/src/main/java/org/telegram/messenger/voip/NativeInstance.java`
  - `TMessagesProj/src/main/java/org/telegram/ui/Stories/LivePlayer.java`
  - `TMessagesProj/jni/voip/tgcalls/NetworkManager.cpp`
  - `TMessagesProj/jni/voip/tgcalls/v2/NativeNetworkingImpl.cpp`
  - `TMessagesProj/jni/voip/tgcalls/v2/InstanceV2ReferenceImpl.cpp`
  - `TMessagesProj/jni/voip/tgcalls/group/GroupNetworkManager.cpp`
- Build and guards:
  - `TMessagesProj/build.gradle`
  - `TMessagesProj_App/build.gradle`

## Verification

Primary checks are documented in `docs/wireguard-testing-plan.md`.

Manual/device validation should still cover:

- login and message/media traffic through a real WireGuard server;
- WireGuard import and manual profile setup before authorization;
- route switching between direct, proxy, and WireGuard;
- Wi-Fi/LTE transitions;
- private and group/live VoIP calls;
- packet capture confirming no direct Telegram datacenter traffic while
  WireGuard is enabled.

## Known Limits

- UDP ASSOCIATE is not implemented, so VoIP uses TCP relay safe mode.
- Non-`tgnet` networking beyond the audited VoIP/live paths must be reviewed
  separately if it needs mandatory WireGuard routing.
- `wireguard-go/tun/netstack` adds binary size and CPU overhead.
- Release requires license review for WireGuard-related dependencies and this
  Telegram client codebase.
