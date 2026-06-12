# AmneziaWG Integration Plan

## Purpose

Add AmneziaWG 2.0 support beside the existing in-process WireGuard
integration. Users must be able to keep, add, delete, import, scan, select, and
enable both WireGuard and AmneziaWG profiles from the same proxy settings flow.

The routing and security rules remain the same as WireGuard:

- no Android `VpnService`, no `Builder.establish()`, no `/dev/tun`, and no
  device-level VPN session;
- no silent direct fallback while a tunnel protocol is user-enabled;
- profile keys and AmneziaWG masking fields are stored only in Keystore-backed
  encrypted profile storage;
- unsupported devices cannot add/import/scan/enable profiles, while stale
  enabled settings stay fail-closed and disable-able;
- ordinary proxy and tunnel profiles stay mutually exclusive through
  `NetworkRouteSettings`;
- VoIP remains TCP relay through HTTP CONNECT. UDP ASSOCIATE is still not
  implemented.

Reviewed upstream references:

- `https://github.com/amnezia-vpn/amneziawg-go`
- `https://docs.amnezia.org/documentation/amnezia-wg/`

## Current Baseline

The existing WireGuard implementation has a useful split:

- `WireGuardProfile`, `WireGuardConfigParser`, `WireGuardUserspaceConfig`,
  `WireGuardSettings`, and `WireGuardSecureStore` own profile data, parsing,
  UAPI config generation, persistence, and secure storage.
- `WireGuardController` owns start/restart/stop, fail-closed proxy application,
  network-refresh recovery, and generated loopback proxy credentials.
- `WireGuardManager` is the Java entry point and bridge from settings to the
  controller.
- `NetworkRouteSettings` is the policy layer that makes ordinary proxy and
  WireGuard mutually exclusive.
- `TMessagesProj/jni/tg_wg/go` builds `libtg-wg-go.so`, creates
  `wireguard-go` `tun/netstack`, exposes SOCKS5 and HTTP CONNECT on loopback,
  resolves domain endpoints to IP endpoints, and calls `device.IpcSet`.
- `TMessagesProj/jni/tg_wg/tg_wg_jni.cpp` loads the Go shared library and
  exposes `nativeStart`, `nativeOnNetworkChanged`, and `nativeStop`.

This shape should be preserved. The AmneziaWG work should generalize the Java
policy/profile surface where useful, but keep protocol-specific native runtimes
separate until real-server parity is proven.

## Protocol Notes

AmneziaWG is a fork of `wireguard-go` with the same core device/netstack shape
and additional device-level UAPI keys:

- junk packets: `Jc`, `Jmin`, `Jmax`;
- paddings: `S1`, `S2`, `S3`, `S4`;
- dynamic headers: `H1`, `H2`, `H3`, `H4`;
- signature packets: `I1`, `I2`, `I3`, `I4`, `I5`.

The Go UAPI keys are lower-case (`jc`, `jmin`, `s1`, `h1`, `i1`, etc.) and must
be emitted before the first `public_key=` line, because `public_key` switches
the UAPI parser from device configuration to peer configuration.

If an AmneziaWG value is unset or parses as zero, omit the UAPI line instead of
emitting `jc=0`, `jmin=0`, or similar. Upstream treats missing values as zero,
while its UAPI parser rejects some explicitly non-positive values.

## Target Routing

WireGuard remains:

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

AmneziaWG adds the parallel runtime:

```text
tgnet TCP traffic
  -> authenticated SOCKS5 on 127.0.0.1
  -> amneziawg-go tun/netstack
  -> obfuscated UDP to AmneziaWG peer
  -> Telegram datacenters

WebRTC VoIP TCP relay traffic
  -> authenticated HTTP CONNECT on 127.0.0.1
  -> amneziawg-go tun/netstack
  -> obfuscated UDP to AmneziaWG peer
  -> Telegram relay/datacenter
```

Only one tunnel runtime may be enabled at a time.

## Java Architecture

Introduce a protocol-neutral layer and keep compatibility facades where they
reduce churn:

- `TunnelProtocol` enum: `WIREGUARD`, `AMNEZIA_WG`.
- `TunnelProfile` or `NetworkTunnelProfile`: common WireGuard fields plus
  protocol-specific AmneziaWG fields.
- `TunnelConfigParser`: parse one `[Interface]` and one `[Peer]`; detect
  AmneziaWG if any AmneziaWG-specific keys are present; reject multiple peers.
- `TunnelUserspaceConfig`: build UAPI config for both protocols. The WireGuard
  path emits the current UAPI. The AmneziaWG path emits the same base UAPI plus
  AmneziaWG device keys before `public_key=`.
- `TunnelSecureStore` and `TunnelSettings`: encrypted profile store and
  route-state keys for both protocols.
- `TunnelController`: rename or extract from `WireGuardController`, parameterized
  with a protocol label and a `NativeRuntime`.
- `TunnelManager`: single entry point for start/stop/select/save/delete/import,
  active proxy settings, network change handling, and support gating.
- `WireGuardManager`: keep as a thin compatibility facade during migration, or
  remove only after all callers and static guards are moved.

Keep `NetworkRouteSettings` as the only route-mode policy layer. Add
`enableTunnel(profileId)` or equivalent, and keep `enableWireGuard(profileId)` as
a temporary wrapper if needed. Enabling either tunnel protocol must disable
ordinary proxy, proxy-for-calls, and proxy rotation. Enabling ordinary proxy must
disable the active tunnel first.

## Profile Storage And Migration

Do not add a plaintext SharedPreferences fallback. Store every profile field
that can contain keys, endpoints, or AmneziaWG masking details in the encrypted
profile blob.

Recommended storage plan:

1. Add a new encrypted schema for protocol-neutral profiles. Either use a new
   preference/key alias such as `telegram_tunnel_profiles_v1`, or bump the
   existing encrypted WireGuard profile schema to a version that includes the
   protocol and AmneziaWG fields.
2. Keep global route state in `mainconfig` only:
   - enabled flag;
   - current profile id;
   - current protocol only if the current profile id cannot be resolved alone.
3. On first load, migrate existing `WireGuardSecureStore` profiles to
   `protocol=WIREGUARD`.
4. Migrate `wireguard_enabled` and `wireguard_current_profile_id` to the new
   route-state keys only after encrypted profile migration succeeds.
5. If migration or secure storage fails while legacy WireGuard was enabled,
   preserve fail-closed behavior and let the user disable the stale state.

AmneziaWG profile fields to add:

- `amneziaJc`, `amneziaJmin`, `amneziaJmax`;
- `amneziaS1`, `amneziaS2`, `amneziaS3`, `amneziaS4`;
- `amneziaH1`, `amneziaH2`, `amneziaH3`, `amneziaH4`;
- `amneziaI1`, `amneziaI2`, `amneziaI3`, `amneziaI4`, `amneziaI5`.

Treat these fields as profile contents: no logs, no list-row display, no
plaintext preferences, no real committed examples.

## Config Parsing And Validation

Common WireGuard fields stay unchanged:

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

AmneziaWG-specific keys should be accepted in `[Interface]`:

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

Validation rules:

- base WireGuard keys, endpoint, local addresses, allowed IPs, MTU, and
  keepalive use the existing validation;
- `Jc` accepts zero/unset and positive values in the documented range for
  AmneziaWG 2.0; if `Jc > 0`, require valid `Jmin` and `Jmax`;
- `Jmin` and `Jmax` must be positive when emitted, `Jmin <= Jmax`, and should be
  bounded to the documented 64..1024 byte range unless the pinned upstream
  version requires a different range;
- `S1`, `S2`, `S3` accept 0..64; `S4` accepts 0..32;
- `H1`..`H4` accept either a single unsigned 32-bit value or `start-end` with
  `start <= end`;
- reject overlapping non-empty `H1`..`H4` ranges before runtime start;
- `I1`..`I5` should match the pinned `amneziawg-go` CPS grammar. For the current
  upstream README that means `<b 0x...>`, `<r N>`, `<rd N>`, `<rc N>`, and
  `<t>` tags, with even-sized hex for `<b>` and bounded generated lengths;
- if Java validation and upstream parser differ, the pinned upstream parser is
  the final source of truth and tests must document the difference.

Automatic import should set `protocol=AMNEZIA_WG` when any AmneziaWG-specific
key is present. Standard configs without those keys remain `protocol=WIREGUARD`
unless the user explicitly creates an AmneziaWG profile manually.

## Native Runtime

Use a sibling native runtime first:

- `TMessagesProj/jni/tg_awg/go/`
- `TMessagesProj/jni/tg_awg/tg_awg_jni.cpp`
- `libtg-awg-go.so`
- `libtg-awg.so`

The Go bridge can reuse almost all code from `TMessagesProj/jni/tg_wg/go`:

- `netstack.CreateNetTUN`;
- authenticated SOCKS5 CONNECT;
- authenticated HTTP CONNECT for VoIP;
- endpoint DNS preprocessing;
- bind refresh on network change;
- host-side proxy and endpoint tests.

The protocol-specific change is the dependency/import set:

- `github.com/amnezia-vpn/amneziawg-go/conn`
- `github.com/amnezia-vpn/amneziawg-go/device`
- `github.com/amnezia-vpn/amneziawg-go/tun/netstack`

Pin `amneziawg-go` to a tag or commit in `go.mod`; do not track `master`
implicitly. Align the Android Go bootstrap version with the pinned module's
`go` directive.

Do not replace the current WireGuard runtime with AmneziaWG in the first change.
Although upstream documents zero-valued AmneziaWG behavior as WireGuard-like,
keeping `libtg-wg-go.so` and `libtg-awg-go.so` separate reduces regression risk
for existing WireGuard users and makes packaging/static guards clearer.

## Build Integration

Update `TMessagesProj/jni/CMakeLists.txt` to build the AmneziaWG Go shared
library using a separate build directory:

- keep `GOMODCACHE` and `GOCACHE` outside `TMessagesProj/jni`;
- use a separate `TG_AWG_BUILD_DIR`, for example under the CMake binary dir;
- add explicit CMake dependencies for the AmneziaWG Go bridge files.

Update Gradle tasks:

- add `testAmneziaWGGo`;
- either add `verifyAmneziaWGStaticGuards` or broaden
  `verifyWireGuardStaticGuards` into `verifyTunnelStaticGuards`;
- update APK packaging verification to require `libtg-awg.so` and
  `libtg-awg-go.so` for every supported ABI;
- forbid AmneziaWG Go module/cache artifacts in APK entries.

## Tgnet And VoIP

The tgnet path should not care whether the active tunnel is WireGuard or
AmneziaWG. `ConnectionsManager.setProxySettings(...)` must preserve the active
internal tunnel proxy while any tunnel protocol is enabled.

The VoIP path remains unchanged semantically:

- active tunnel proxy uses HTTP CONNECT;
- private calls ignore user proxy preferences while a tunnel is active;
- P2P is disabled;
- TCP relay safe mode is forced;
- native WebRTC paths call `BasicPortAllocator::set_proxy(...)`;
- UDP/STUN and direct host/reflexive ICE candidates stay disabled/filtered;
- active sessions are not dynamically rerouted.

Prefer protocol-neutral names such as `TunnelProxySettings` and
`TunnelVoipRouting`; keep old WireGuard wrappers only as temporary adapters.

## Implementation Phases

1. Add protocol-neutral model, settings, secure-store schema, and migration from
   existing WireGuard profiles and route-state keys.
2. Add parser and userspace config builder support for AmneziaWG fields, with
   JVM unit tests for detection, validation, and UAPI output.
3. Add the AmneziaWG Go/JNI runtime by cloning the current bridge structure and
   swapping imports to `amneziawg-go`.
4. Generalize the controller/manager/policy path so enabling any tunnel profile
   applies blocked proxy first, starts the selected native runtime, then applies
   the generated internal proxy.
5. Update UI flows for adding, importing, scanning, displaying, selecting, and
   deleting both protocol types.
6. Update tgnet/VoIP callers to use protocol-neutral proxy settings.
7. Update Gradle tests, packaging checks, static guards, docs, and manual smoke
   checklists.

## Risks And Open Questions

- `amneziawg-go` validation and public docs have changed over time. Pin a
  commit/tag and make tests match that exact version.
- Some third-party docs use wider ranges for `Jc/Jmin/Jmax` than the official
  AmneziaWG 2.0 page. Prefer the pinned upstream parser plus official docs, and
  document any deliberate compatibility widening.
- Android binary size will increase because a second Go shared library is
  packaged.
- Real AmneziaWG 2.0 server testing is required before treating the protocol as
  production-ready.
- License review is required for `amneziawg-go` and its transitive modules.
