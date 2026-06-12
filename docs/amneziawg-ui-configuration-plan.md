# AmneziaWG UI Configuration Plan

## Purpose

Extend the existing combined proxy/WireGuard settings flow so users can manage
both WireGuard and AmneziaWG profiles. The UI must preserve the same security
and routing behavior as the current WireGuard flow.

## Target User Flows

Users can:

- add a WireGuard profile manually;
- add an AmneziaWG profile manually;
- import a standard WireGuard config file;
- import an AmneziaWG config file with AmneziaWG 2.0 fields;
- scan a WireGuard or AmneziaWG config QR code;
- review imported profiles before saving;
- enable either protocol before authorization;
- switch between saved profiles of either protocol;
- delete inactive profiles;
- delete the active profile, which stops the active tunnel.

## Proxy List Screen

`ProxyListActivity` remains the entry point. Rename visible WireGuard-only
sections to cover both protocols, for example:

- route toggle: `Use WireGuard / AmneziaWG`;
- section header: `Tunnel Connections` or `WireGuard / AmneziaWG Connections`;
- add row: `Add Connection`;
- import row: `Import Config`;
- scan row: `Scan Config QR Code`.

Each saved profile row should show:

- profile display name;
- protocol label (`WireGuard` or `AmneziaWG`);
- endpoint or status text;
- active/connecting/failed state for the selected profile;
- edit and delete actions.

Do not show private keys, preshared keys, or AmneziaWG signature/header details
in list rows.

Selecting any profile enables that profile through `NetworkRouteSettings`.
Enabling a tunnel disables ordinary proxy, proxy-for-calls, and proxy rotation.
Selecting or enabling an ordinary proxy disables the active tunnel first.

## Add Profile Flow

When the user taps `Add Connection`, show a small protocol choice sheet:

- `WireGuard`;
- `AmneziaWG`.

Open the profile editor with the selected protocol. If secure storage is not
supported, show the existing unsupported-device dialog before opening the editor.

## Profile Editor

`WireGuardSettingsActivity` should become protocol-aware or be replaced with a
protocol-neutral editor. Common fields stay visible for both protocols:

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

For `protocol=AMNEZIA_WG`, add an AmneziaWG section:

- junk packets: `Jc`, `Jmin`, `Jmax`;
- paddings: `S1`, `S2`, `S3`, `S4`;
- headers: `H1`, `H2`, `H3`, `H4`;
- signature packets: `I1`, `I2`, `I3`, `I4`, `I5`.

Use single-line numeric fields for integer/range values. Use wider or multiline
fields for `I1`..`I5`, because CPS strings can be long.

The Done button should require the common WireGuard-required fields for both
protocols. AmneziaWG fields can be optional only when omitted/zero behavior is
intentional and accepted by validation. If the user enters any related field,
validate the whole group before saving.

## Validation UX

Manual entry, config-file import, and QR import must use the same parser and
validation code.

Errors should identify the invalid field group without logging sensitive
contents:

- invalid base WireGuard key/address/endpoint fields;
- invalid `Jc/Jmin/Jmax` range;
- invalid `S1`..`S4` range;
- invalid `H1`..`H4` syntax or overlapping ranges;
- invalid `I1`..`I5` CPS syntax;
- multiple `[Peer]` sections.

No profile should be saved on validation failure.

## File Import

Keep the Android content picker flow and 64 KB limit. Treat selected files as
UTF-8 text.

Import behavior:

1. Parse one `[Interface]` and one `[Peer]`.
2. If any AmneziaWG-specific key is present, create an AmneziaWG draft.
3. Otherwise create a WireGuard draft.
4. Open the editor for review before saving.

The source URI must not be persisted.

## QR Import

Keep `CameraScanActivity` in `TYPE_QR` mode with gallery import enabled. Accept
raw config text that contains `[Interface]` and `[Peer]`; final protocol
detection belongs to the shared parser.

Telegram login QR scanning remains separate in `TYPE_QR_LOGIN`.

## Unsupported Devices

If Keystore-backed profile storage is unavailable:

- hide or block add/import/scan/enable/select actions for both protocols;
- show the unsupported-device dialog;
- do not write plaintext profile contents;
- keep stale enabled tunnel state fail-closed;
- allow the user to disable stale tunnel state.

## Delete Behavior

Deleting a saved profile requires confirmation.

If the profile is active:

1. stop the active tunnel;
2. clear the current profile id;
3. persist disabled tunnel state;
4. leave ordinary proxy, proxy-for-calls, and proxy rotation disabled;
5. refresh route status UI.

Inactive deletion should only remove the selected encrypted profile.

## Status Surfaces

Update status labels in `DialogsActivity` and `LoginActivity` so they do not say
`WireGuard connected` when the active profile is AmneziaWG. Recommended labels:

- `Tunnel connected`;
- `Tunnel connecting`;
- `Tunnel failed`;

or protocol-specific labels:

- `WireGuard connected`;
- `AmneziaWG connected`.

The profile row can show the exact protocol; the top-level route status can be
generic if that keeps strings manageable.

## Strings And Localization

Add string resources for:

- AmneziaWG protocol name;
- combined tunnel route toggle/header/add/import/scan labels;
- AmneziaWG field labels;
- invalid AmneziaWG config errors;
- unsupported-device text that covers both protocols.

Keep legacy WireGuard strings if existing screens or migration messages still
refer to WireGuard specifically.

## Manual Smoke Checklist

UI/device validation should cover:

- existing WireGuard profiles still appear after migration;
- new WireGuard manual profile save, enable, disable, edit, and delete;
- new AmneziaWG manual profile save, enable, disable, edit, and delete;
- importing standard WireGuard config creates a WireGuard draft;
- importing AmneziaWG config creates an AmneziaWG draft with all extra fields;
- QR import follows the same detection and validation rules;
- invalid AmneziaWG fields save nothing;
- deleting the active AmneziaWG profile stops the runtime;
- pre-authorization settings work from `LoginActivity`;
- ordinary proxy, proxy-for-calls, rotation, WireGuard, and AmneziaWG remain
  mutually exclusive.

## Non-Goals

- Exporting or sharing profiles.
- UDP ASSOCIATE support.
- Multiple peers per profile.
- Device-level VPN UI or Android VPN permission prompts.
