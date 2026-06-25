# Tunnel UI Configuration

## Purpose

Tunnel configuration lives in the existing Telegram proxy settings flow. The UI
manages WireGuard and AmneziaWG profiles before and after authorization while
preserving the same fail-closed and mutual-exclusion rules as the runtime.

## Proxy Settings Screen

`ProxyListActivity` is the combined route settings screen. It shows ordinary
proxy controls and tunnel controls:

- `Use Proxy`;
- `Use WireGuard / AmneziaWG`;
- `Use tunnel for calls` while a tunnel is active;
- proxy list, add, rotation, and delete controls;
- tunnel profile list;
- `Add Connection`;
- `Import Config`;
- `Scan Config QR Code`.

When a tunnel is active, ordinary proxy rows remain visible but inactive,
`Use Proxy For Calls` is hidden, and selecting or enabling an ordinary proxy
disables the active tunnel first.

`Use tunnel for calls` defaults to enabled and is persisted independently from
the selected tunnel profile. It controls private-call relay, group/conference
calls, and live/group streaming for newly created sessions. Telegram-authorized
private P2P remains direct regardless of this setting. Existing active sessions
are not rerouted when the setting changes.

Each saved tunnel profile row shows the display name, protocol label, endpoint
or status, active/connecting/failed state when relevant, and edit/delete actions.
Profile rows must not show private keys, preshared keys, or AmneziaWG
header/signature details.

`LoginActivity` keeps the proxy/tunnel settings entry visible before login and
opens the same `ProxyListActivity`. Global settings are reused after login.

## Profile Editor

`WireGuardSettingsActivity` is protocol-aware and is used for manual entry and
for reviewing imported drafts before saving.

Common fields for both protocols:

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

AmneziaWG profiles additionally show:

- junk packets: `Jc`, `Jmin`, `Jmax`;
- paddings: `S1`, `S2`, `S3`, `S4`;
- headers: `H1`, `H2`, `H3`, `H4`;
- signature packets: `I1`, `I2`, `I3`, `I4`, `I5`.

Manual entry, file import, and QR import use the same parser and validation
rules. No profile is saved on validation failure.

## Import And QR

`Import Config` opens an Android content picker, reads UTF-8 text with a 64 KB
limit, parses it with `TunnelConfigParser`, and opens the parsed draft in
`WireGuardSettingsActivity`. The source URI is not persisted.

`Scan Config QR Code` opens `CameraScanActivity` in `TYPE_QR` mode with gallery
import enabled. Camera and gallery results are treated as raw config text and
use the same parser/review path as file import.

Protocol detection belongs to the shared parser:

- configs with AmneziaWG-specific interface keys become AmneziaWG drafts;
- standard configs without AmneziaWG keys become WireGuard drafts;
- `TYPE_QR_LOGIN` remains separate and keeps its `tg://login?token=` guard.

## Unsupported Devices

If Keystore-backed profile storage is unavailable:

- block add/import/scan/enable/select actions for both protocols;
- show the unsupported-device dialog;
- never write plaintext profile contents;
- keep stale enabled tunnel state fail-closed;
- allow the user to disable stale enabled state.

## Delete And Selection

Deleting a saved tunnel profile requires confirmation.

Deleting the active profile stops the active runtime, clears the current profile
id, persists disabled tunnel state, leaves ordinary proxy/proxy-for-calls/proxy
rotation disabled, and refreshes route status UI.

Selecting another profile while a tunnel is enabled restarts through the
blocked-proxy-first path so there is no direct routing gap.

## Status Labels

Status surfaces in `DialogsActivity`, `LoginActivity`, and profile rows must not
label an AmneziaWG profile as WireGuard. Top-level route status can be generic
(`Tunnel connected`, `Tunnel connecting`, `Tunnel failed`) or protocol-specific
when there is enough space.

## Non-Goals

- Exporting or sharing profiles.
- UDP ASSOCIATE support.
- Multiple peers per profile.
- Device-level VPN UI or Android VPN permission prompts.
- Committing real WireGuard keys, AmneziaWG keys, endpoints, Telegram `APP_ID`,
  or Telegram `APP_HASH`.
