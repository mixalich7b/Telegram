# WireGuard UI Configuration

## Goal

Expose WireGuard configuration in the existing Telegram proxy settings flow.

Users can:

- enable and disable WireGuard;
- create profiles manually;
- import a standard WireGuard config file;
- save settings across app restarts;
- keep multiple profiles and switch between them;
- configure WireGuard before Telegram authorization.

WireGuard and the existing proxy feature are mutually exclusive.

## Screens

### Proxy List

`ProxyListActivity` is the combined route settings screen.

It contains:

- `Use Proxy`;
- `Use WireGuard`;
- existing proxy list, add, rotation, and delete controls;
- WireGuard profile list;
- `Add WireGuard Connection`;
- `Import WireGuard Config`.

When WireGuard is active:

- ordinary proxy rows remain visible as saved profiles, but inactive;
- `Use Proxy For Calls` is hidden;
- selecting or enabling an ordinary proxy disables WireGuard first.

When the current build does not include WireGuard native support:

- WireGuard add/import/enable/profile-selection actions show
  `WireGuardUnavailableInThisBuild`;
- stale persisted enabled WireGuard settings remain fail-closed;
- the user can still disable stale enabled WireGuard settings.

### Profile Editor

`WireGuardSettingsActivity` is used for both manual entry and review of imported
configs.

Fields:

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

Keys are not shown in list rows. Manual profiles and imported profiles use the
same validation rules.

### Import

`Import WireGuard Config` opens an Android content picker, reads the selected
file as text, limits input size to 64 KB, parses it with `WireGuardConfigParser`,
and opens the parsed draft in `WireGuardSettingsActivity`.

The imported URI is not persisted. Only the reviewed profile data is saved.

### Before Login

`LoginActivity` keeps the proxy/WireGuard settings entry visible before
authorization. The entry opens the same combined `ProxyListActivity`, and the
global settings saved there are reused after login.

## Persisted Settings

WireGuard settings are stored in global `mainconfig`.

Preference keys:

- `wireguard_enabled`
- `wireguard_current_profile_id`
- `wireguard_profile_list`
- `wireguard_profile_schema_version`

The profile list uses a versioned serialized format so migrations stay explicit.
Private keys are sensitive: do not log them, do not put them in status text, and
do not commit real profiles to the repository.

## Config Parser

`WireGuardConfigParser` supports:

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

- accept comments beginning with `#` or `;`;
- trim whitespace;
- parse comma-separated `Address`, `DNS`, and `AllowedIPs`;
- accept domain endpoints, IPv4 endpoints, and bracketed IPv6 endpoints;
- require exactly one `[Interface]` and one `[Peer]`;
- reject multiple peers;
- validate base64 keys through `WireGuardUserspaceConfig`;
- require private key, interface address, peer public key, endpoint, and allowed
  IPs;
- validate port and MTU ranges.

## Route Behavior

`NetworkRouteSettings` centralizes route-mode changes.

Enabling WireGuard:

1. Verifies native build support.
2. Validates the selected profile.
3. Disables ordinary proxy, proxy-for-calls, and proxy rotation.
4. Persists `wireguard_enabled=true` and the selected profile id.
5. Applies blocked local proxy while starting or restarting the runtime.
6. Applies the generated internal proxy on success.
7. Leaves traffic fail-closed on failure.

Disabling WireGuard:

1. Persists `wireguard_enabled=false`.
2. Stops the runtime.
3. Clears native tgnet proxy settings for all accounts.
4. Leaves ordinary proxy, proxy-for-calls, and proxy rotation disabled.

Selecting another profile while WireGuard is enabled restarts through the
blocked-proxy-first path so there is no direct routing gap.

## VoIP

New private/group/live VoIP sessions read `WireGuardManager.getProxySettings()`
when they are created.

WireGuard VoIP uses HTTP CONNECT, disables P2P, filters direct ICE candidates,
and forces TCP relay safe mode. Active VoIP sessions are not reconfigured by UI
toggles; they keep the route captured at instance creation.

UDP ASSOCIATE is not implemented in this UI stage.

## Manual Smoke

Manual/device validation should cover:

- configure and enable WireGuard before login;
- restart the app and verify persisted state;
- log in and verify the same profile appears in proxy settings;
- enable ordinary proxy and verify WireGuard turns off;
- enable WireGuard and verify ordinary proxy turns off;
- disable WireGuard and verify direct traffic resumes;
- import invalid config and verify it is not enabled;
- switch between two valid profiles and verify reconnect;
- test failed WireGuard startup and verify traffic remains fail-closed;
- verify private and group/live VoIP use TCP-safe WireGuard routing for new
  sessions;
- use packet capture to confirm Telegram traffic exits through the WireGuard
  server while enabled.

## Non-Goals

- Do not add Android `VpnService`, `Builder.establish()`, `/dev/tun`, or a
  device-level VPN session.
- Do not add UDP ASSOCIATE support.
- Do not silently fall back to direct traffic while WireGuard is enabled.
- Do not export or share WireGuard profiles.
- Do not commit real WireGuard keys, peer endpoints, Telegram `APP_ID`, or
  Telegram `APP_HASH`.
