# AmneziaWG Implementation TODO

This is the high-level execution checklist for adding AmneziaWG 2.0 beside the
current in-process WireGuard support. Do not implement multiple phases at once
unless the earlier phase is fully verified.

## Ground Rules

- [ ] Keep all WireGuard constraints from `AGENTS.md` valid for AmneziaWG too.
- [ ] Do not add Android `VpnService`, `Builder.establish()`, `/dev/tun`, or a
  device-level VPN session.
- [ ] Do not add silent direct fallback while any tunnel protocol is enabled.
- [ ] Do not store private keys, preshared keys, endpoints, or AmneziaWG masking
  fields in plaintext preferences.
- [ ] Keep ordinary proxy, WireGuard, and AmneziaWG mutually exclusive through
  `NetworkRouteSettings`.
- [ ] Keep VoIP tunnel routing on HTTP CONNECT/TCP relay only.

## Phase 0: Pin External Scope

- [ ] Choose and pin an `amneziawg-go` tag or commit.
- [ ] Record the pinned version in the implementation PR/docs.
- [ ] Confirm the pinned `go.mod` Go version and Android build requirements.
- [ ] Review `amneziawg-go` license and transitive dependency license impact.
- [ ] Verify the pinned upstream UAPI keys and validation behavior for
  `jc/jmin/jmax`, `s1..s4`, `h1..h4`, and `i1..i5`.

## Phase 1: Introduce Protocol-Neutral Java Model

- [ ] Add `TunnelProtocol` with `WIREGUARD` and `AMNEZIA_WG`.
- [ ] Add a protocol-neutral profile model that preserves all current
  `WireGuardProfile` fields.
- [ ] Add AmneziaWG fields to the profile model:
  `Jc/Jmin/Jmax`, `S1..S4`, `H1..H4`, and `I1..I5`.
- [ ] Keep old WireGuard classes as wrappers or adapters until all callers are
  migrated.
- [ ] Add unit tests for copying, normalization, display names, and validation
  defaults.

## Phase 2: Secure Storage And Migration

- [ ] Add a new encrypted profile schema or bump the current encrypted schema.
- [ ] Store protocol and AmneziaWG fields only inside the encrypted blob.
- [ ] Migrate existing encrypted WireGuard profiles to `protocol=WIREGUARD`.
- [ ] Migrate `wireguard_enabled` and `wireguard_current_profile_id` only after
  encrypted profile migration succeeds.
- [ ] Preserve fail-closed behavior if migration fails while WireGuard was
  enabled.
- [ ] Add unit tests for old-profile migration, new-profile round trips, wrong
  key rejection, and no plaintext profile keys in `mainconfig`.

## Phase 3: Parser And UAPI Builder

- [ ] Replace or wrap `WireGuardConfigParser` with a protocol-aware parser.
- [ ] Keep standard WireGuard config parsing behavior unchanged.
- [ ] Detect AmneziaWG imports when AmneziaWG-specific keys appear in
  `[Interface]`.
- [ ] Reject multiple `[Peer]` sections.
- [ ] Validate AmneziaWG numeric ranges and header range overlap.
- [ ] Validate `I1..I5` CPS syntax against the pinned upstream grammar.
- [ ] Emit WireGuard UAPI exactly as before for WireGuard profiles.
- [ ] Emit AmneziaWG device UAPI keys before `public_key=` for AmneziaWG
  profiles.
- [ ] Omit zero/unset AmneziaWG values from UAPI output.
- [ ] Add JVM tests for parser detection, invalid configs, and generated UAPI.

## Phase 4: AmneziaWG Native Runtime

- [ ] Add `TMessagesProj/jni/tg_awg/go/` as a sibling of `tg_wg/go`.
- [ ] Reuse the current Go bridge structure for netstack, SOCKS5,
  HTTP CONNECT, endpoint resolution, and network refresh.
- [ ] Switch the AmneziaWG bridge imports to
  `github.com/amnezia-vpn/amneziawg-go`.
- [ ] Export `tgAwgStart`, `tgAwgStop`, and `tgAwgOnNetworkChanged`.
- [ ] Add `TMessagesProj/jni/tg_awg/tg_awg_jni.cpp`.
- [ ] Build `libtg-awg-go.so` and `libtg-awg.so`.
- [ ] Keep Go caches and module cache outside `TMessagesProj/jni`.
- [ ] Add Go tests for endpoint resolution, SOCKS5, HTTP CONNECT, bind refresh,
  and AmneziaWG UAPI acceptance.

## Phase 5: Controller And Route Policy

- [ ] Generalize `WireGuardController` into a tunnel controller or add a shared
  controller wrapper.
- [ ] Start the native runtime selected by the active profile protocol.
- [ ] Keep blocked-proxy-first behavior on enable, restart, profile switch, and
  recovery.
- [ ] Keep network-change handling: native refresh first, restart second,
  blocked proxy if restart fails.
- [ ] Generalize `WireGuardProxySettings` and `WireGuardVoipRouting` or add
  protocol-neutral wrappers.
- [ ] Update `NetworkRouteSettings` so ordinary proxy, WireGuard, and AmneziaWG
  remain mutually exclusive.
- [ ] Add controller and route-policy unit tests for both protocols.

## Phase 6: Tgnet And Startup Integration

- [ ] Update `ConnectionsManager.setProxySettings(...)` preservation logic to
  honor any active tunnel protocol.
- [ ] Update `ApplicationLoader` startup to start the active tunnel profile.
- [ ] Update network-change hooks to call the protocol-neutral manager.
- [ ] Keep stale enabled settings fail-closed and disable-able on unsupported
  devices.
- [ ] Add or update static guards for these invariants.

## Phase 7: UI Integration

- [ ] Rename WireGuard-only UI labels where the screen now manages both
  protocols.
- [ ] Add protocol selection when creating a manual profile.
- [ ] Make the profile editor protocol-aware.
- [ ] Add AmneziaWG fields to the editor.
- [ ] Keep import and QR scan using the same parser/review path as manual entry.
- [ ] Show protocol labels in saved profile rows.
- [ ] Keep delete confirmation for inactive and active profiles.
- [ ] Disable add/import/scan/enable/select actions on unsupported devices.
- [ ] Update `LoginActivity` and `DialogsActivity` status text so AmneziaWG is
  not mislabeled as WireGuard.

## Phase 8: VoIP Integration

- [ ] Update private VoIP Java paths to read protocol-neutral tunnel proxy
  settings.
- [ ] Keep HTTP CONNECT for tunnel-created VoIP proxies.
- [ ] Keep P2P disabled and TCP relay forced while any tunnel is active.
- [ ] Audit legacy private, V2 custom, V2 reference, group, and live paths.
- [ ] Keep UDP/STUN disabled and direct ICE candidates filtered in native WebRTC
  paths.
- [ ] Add/update VoIP routing unit tests for AmneziaWG.

## Phase 9: Build, Guards, And Packaging

- [ ] Add `testAmneziaWGGo`.
- [ ] Rename or extend static guard task to cover both protocols.
- [ ] Require `libtg-awg.so` and `libtg-awg-go.so` in APK packaging checks.
- [ ] Reject `tg_awg/go/build`, `gomod`, `gocache`, and accidental Go shared
  objects in packaged APK entries.
- [ ] Ensure `assembleAfatDebug` runs JVM tests, WireGuard Go tests,
  AmneziaWG Go tests, static guards, and packaging verification.

## Phase 10: Documentation And Manual Smoke

- [ ] Update `AGENTS.md` with the new AmneziaWG files and invariants.
- [ ] Update WireGuard docs where the behavior becomes protocol-neutral.
- [ ] Keep AmneziaWG docs aligned with implementation decisions.
- [ ] Run fast no-emulator verification.
- [ ] Run `assembleAfatDebug`.
- [ ] Smoke test a real WireGuard server.
- [ ] Smoke test a real AmneziaWG 2.0 server.
- [ ] Verify startup failure and network-change failure are fail-closed.
- [ ] Verify packet capture or server logs show no direct Telegram datacenter
  traffic while either tunnel protocol is enabled.
