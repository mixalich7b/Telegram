# WireGuard UI Configuration

## Purpose

WireGuard configuration lives in the existing Telegram proxy settings flow. Users
can configure WireGuard before or after authorization, create profiles manually,
import config files, scan config QR codes, delete saved profiles, persist
settings across restarts, and switch between saved profiles.

WireGuard and the ordinary proxy feature are mutually exclusive. All route-mode
changes go through `NetworkRouteSettings`.

## Screens

`ProxyListActivity` is the combined route settings screen. It shows:

- `Use Proxy`;
- `Use WireGuard`;
- existing proxy list, add, rotation, and delete controls;
- WireGuard profile list;
- `Add WireGuard Connection`;
- `Import WireGuard Config`;
- `Scan WireGuard QR Code`.

When WireGuard is active, ordinary proxy rows remain visible but inactive,
`Use Proxy For Calls` is hidden, and selecting or enabling an ordinary proxy
disables WireGuard first.

`WireGuardSettingsActivity` is used for manual profile entry and for reviewing
imported drafts before saving. Manual entry, file import, and QR import all use
the same validation rules. Keys are not shown in list rows.

Saved WireGuard profiles can be deleted from the profile list after user
confirmation. Deleting the active profile stops WireGuard and leaves traffic in
the normal disabled-WireGuard state.

`LoginActivity` keeps the proxy/WireGuard settings entry visible before login and
opens the same `ProxyListActivity`; global settings are reused after login.

In builds without native WireGuard support, or on Android versions before API
23 where Keystore-backed profile storage is unavailable, add/import/scan/enable
and profile selection actions show `WireGuardUnavailableInThisBuild`. Stale
enabled settings remain fail-closed and can still be disabled.

## Profile Data

Route state is stored in global `mainconfig` with these keys:

- `wireguard_enabled`
- `wireguard_current_profile_id`

Profile contents are stored separately in `wireguard_secure` under
`wireguard_profiles_encrypted_v1`. The profile blob is serialized with an
embedded schema version and then encrypted with `AES/GCM/NoPadding` using an
Android Keystore key. There is no plaintext SharedPreferences fallback.

Profile fields:

- name;
- private key;
- interface addresses;
- DNS servers;
- MTU;
- peer public key;
- preshared key;
- endpoint;
- allowed IPs;
- persistent keepalive.

Private keys are sensitive: do not log them, show them in status text, store
them in plaintext preferences, or commit real profiles.

## Import

`Import WireGuard Config` opens an Android content picker, reads the selected
file as UTF-8 text, enforces a 64 KB maximum, parses it with
`WireGuardConfigParser`, and opens the parsed draft in
`WireGuardSettingsActivity`. The source URI is not persisted.

`Scan WireGuard QR Code` opens `CameraScanActivity` in `TYPE_QR` mode with the
existing gallery button enabled. Camera and gallery QR results are treated as raw
WireGuard config text and go through the same parser/review path as file import.
Typical QR input is generated from a standard config file:

```bash
qrencode -t ansiutf8 < profile.conf
```

The scanner reuses the project QR stack:

- Google Vision `BarcodeDetector` when available;
- bundled ZXing `QRCodeReader` fallback;
- `CameraScanActivityDelegate.shouldAcceptQrText(...)` to let WireGuard accept
  only payloads that look like raw configs with `[Interface]` and `[Peer]`.

`TYPE_QR_LOGIN` keeps its own `tg://login?token=` guard, so Telegram
authorization QR scanning remains separate from WireGuard QR import.

## Config Parser

`WireGuardConfigParser` supports a single `[Interface]` and a single `[Peer]`
section with standard WireGuard fields:

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

Rules:

- comments beginning with `#` or `;` are accepted;
- whitespace is trimmed;
- `Address`, `DNS`, and `AllowedIPs` are comma-separated;
- endpoint hosts can be domains, IPv4 addresses, or bracketed IPv6 addresses;
- multiple peers are rejected;
- required keys, required addresses, endpoint port, MTU, and key formats are
  validated before saving.

## Route Behavior

Enabling WireGuard:

1. Verifies native build support.
2. Validates the selected profile.
3. Disables ordinary proxy, proxy-for-calls, and proxy rotation.
4. Persists the selected profile id and enabled state.
5. Applies a blocked local proxy while starting or restarting the runtime.
6. Applies the generated internal proxy on success.
7. Leaves traffic fail-closed on failure.

Disabling WireGuard stops the runtime, clears native tgnet proxy settings for all
accounts, persists `wireguard_enabled=false`, and leaves ordinary proxy,
proxy-for-calls, and proxy rotation disabled.

Selecting another profile while WireGuard is enabled restarts through the
blocked-proxy-first path so there is no direct routing gap.

## VoIP

New private/group/live VoIP sessions read `WireGuardManager.getProxySettings()`
when they are created. WireGuard VoIP uses HTTP CONNECT, disables P2P, filters
direct ICE candidates, and forces TCP relay safe mode. Active sessions are not
dynamically rerouted by UI changes.

UDP ASSOCIATE is not implemented.

## Manual Smoke

Manual/device validation should cover:

- setup before login, app restart, and login reuse;
- manual entry, config-file import, QR camera scan, and QR screenshot import;
- invalid file/QR import creates no profile and does not enable WireGuard;
- profile deletion confirmation, including deleting the active profile;
- proxy/WireGuard mutual exclusion;
- profile switching and failed startup fail-closed behavior;
- private and group/live VoIP new-session routing;
- packet capture confirming Telegram traffic exits through the WireGuard server
  while enabled.

## Non-Goals

- Android `VpnService`, `Builder.establish()`, `/dev/tun`, or device-level VPN.
- UDP ASSOCIATE support.
- Silent direct fallback while WireGuard is enabled.
- Exporting or sharing WireGuard profiles.
- Committing real WireGuard keys, endpoints, Telegram `APP_ID`, or
  Telegram `APP_HASH`.
