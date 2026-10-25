# Tunnel Testing

## Canonical Commands

Run exactly one command according to the requested scope. The Telegram and
signing properties are prerequisites supplied in the user's global
`gradle.properties`; do not pass them through `-P` arguments or modify them.

For no-emulator Java/Go and host C++ tunnel checks without APK packaging:

```bash
./gradlew --no-daemon tunnelCheck
```

For JNI/CMake/native changes or a complete debug APK check:

```bash
./gradlew --no-daemon tunnelPackageDebug
```

`tunnelPackageDebug` transitively runs `tunnelCheck`, the native APK packaging
verification, and the APK build. The lower-level Gradle tasks are implementation
details, not alternative canonical commands.

## Automated Coverage

Preparation tests under `Tools/tests/test_prepare_tgcalls.py` run through the
canonical command. They exercise a real temporary Git repository: clean and
repeated preparation, dirty upstream isolation, changed patches, missing/edited
outputs, revision mismatch, unapplicable patches, concurrent preparation, and
preparation inside a parent repository. Static guards read the prepared sources
that CMake compiles, not old copies under `jni/voip/tgcalls`.

After upstream merges, synchronize submodules to their recorded gitlinks with
`git submodule update --init --recursive` before running the canonical check.
An older local submodule checkout can otherwise hide a patch-base mismatch that
fails on a clean checkout or CI.

Native configure/build tasks must prepare tgcalls, while `externalNativeBuildClean*`
tasks must not. Making cleanup generate files under `build/` blocks Gradle's
producer/delete ordering when `clean` and assemble run in one invocation.
`./gradlew clean` removes root/module `build/` directories, `buildSrc/build/`,
and native staging trees (`.cxx/` and legacy `.externalNativeBuild/`). This includes
APKs, object files, generated tgcalls sources, downloaded native Go toolchains
and their module/build caches. Host Go test caches also live under the module's
`build/` directory. The next native build downloads its Go toolchain and modules
again. Gradle's project state and shared dependency caches, Android SDK/NDK and
source submodules remain build inputs and are not deleted.

Cleanup uses Gradle `Delete` tasks, makes read-only Go cache directories writable,
and never follows symlinks. Permission failures identify the affected path.
A qualified module clean only removes that module's build outputs;
both `clean` and `:clean` at the repository root cover all modules.

JVM unit tests under `TMessagesProj/src/test/java` cover:

- userspace config generation and key validation;
- WireGuard and AmneziaWG parser detection, forced-protocol behavior, validation
  errors, and UAPI output;
- profile serialization, encrypted envelope behavior, schema round trips, and
  legacy WireGuard profile migration;
- controller startup, restart, network refresh, fail-closed behavior, and
  aggregation of parallel/sequential tunnel TCP attempts for IPv4-only profiles;
- eight concurrent download TCP attempts retain the runtime while pending or
  successful; an all-failed group restarts only after the last failure settles;
- repeated route updates across all accounts retain a blocked enabled tunnel
  after native failure or a missing profile, until explicit disable;
- tunnel marker metadata;
- VoIP route policy for direct, enabled/disabled tunnel-for-calls, active
  tunnel, blocked tunnel, and tunnel-routed P2P cases.

Go bridge tests cover:

- address and endpoint parsing;
- domain endpoint preprocessing to `IP:port` and DNS failure reporting;
- direct TCP tunnel socket exports for tgnet;
- direct TCP/UDP tunnel socket exports for WebRTC VoIP;
- tunnel DNS lookup, complete TCP writes, and stale runtime-generation socket
  rejection;
- network-change handling through fake bind refreshes;
- AmneziaWG `device.IpcSet` acceptance for generated AWG UAPI, including
  `jc/jmin/jmax`, `s1..s4`, `h1..h4`, and `i1..i5`.

The Gradle Go test tasks keep `GOCACHE` and `GOMODCACHE` under the Gradle
user home directory, never under `TMessagesProj/jni`.

## Tunnel Connection Trace

All dedicated tunnel logs use the independent build property
`TG_TUNNEL_LOGS_ENABLED` (default `true`), in both debug and release builds.
Set it in the user or project `gradle.properties` before building:

```properties
TG_TUNNEL_LOGS_ENABLED=true
```

Use `false` to disable them. Only literal `true` and `false` are accepted.
Gradle passes the same value to Java BuildConfig, CMake and the shared Go
library; standalone CMake uses the same-named ON/OFF option. Rebuild and install
the APK after changing it. This flag has no dependency on `LOGS_ENABLED`, the
Telegram logging menu or the build type. It controls Java lifecycle/storage
logs, tgnet tunnel TCP/MTProto events, JNI bridge errors, Go bridge/device errors,
and dedicated VoIP tunnel socket logs. Turning general app logging on cannot
re-enable these logs when the tunnel flag is false.

Tunnel diagnostics go to logcat under `Telegram/Tunnel` and to the dedicated
`tunnel.log` in the app's normal logs directory. Java, tgnet, JNI, Go and VoIP
share one asynchronous native file worker (`libtg-tunnel-log.so`, with no Go
runtime), independent of Telegram's FileLog. It starts before tunnel startup;
filesystem writes do not run on lifecycle, tgnet or UI threads. Capture logcat
with:

```bash
adb logcat -v threadtime 'Telegram/Tunnel:D' '*:S'
```

The file uses UTC timestamps and severity, appends across restarts, and rotates
at 4 MiB into `tunnel.1.log` (at most 8 MiB of retained logs). A bounded queue
holds up to 1024 pending messages; overload is reported by a
`file_log_overflow dropped=...` record. Abrupt process termination can lose queued
file records; logcat is emitted immediately. File I/O failures are reported in
logcat without disrupting tunnel routing.

Settings → Debug → Send Logs / Send Last Logs includes both tunnel files with
ordinary logs, even when general app logging is off. Export drains preceding
queued records and copies both files on the file worker before building the
ZIP; new traffic and rotation cannot change that snapshot. The usual 24-hour
filter still applies to Send Last Logs. Temporary snapshots are deleted after
export. Clear Logs clears the tunnel files on their worker and allows later
records to create a new file. When the build flag is false, no new tunnel files
are created; previously saved files can still be exported or cleared.

Start capture before reproducing the delay; add `-d` to dump the retained logcat
buffer, or redirect output to a file to preserve a long reproduction. These logs
cover both WireGuard and AmneziaWG. They include:

- `lifecycle_queued`, `lifecycle_begin`, `lifecycle_end`: operation, generation,
  queue wait (`queue_wait_ms`), and execution duration (`elapsed_ms`);
- `runtime_starting`, `native_start_begin`, `native_start_result`: startup,
  native status code and duration (including config construction, library loading,
  peer endpoint DNS resolution and runtime creation);
- `runtime_failed`: reason, generation, retryability and blocked route;
- `runtime_stop_begin`, `runtime_stopped`, `runtime_stop_failed`: shutdown timing;
- `network_refresh_begin/result/failed`: bind refresh timing, status, and restart
  decision after failure;
- `reconnect_scheduled`, `reconnect_timer_fired`, `reconnect_started/cancelled`:
  requested delay (`delay_ms`), actual wait (`actual_wait_ms`), and schedule ID;
- existing `runtime_active/refreshed` and `tcp_attempt_*` events;
- `tcp_connected_timing`: time since runtime activation or refresh
  (`route_elapsed_ms`), not the duration of an individual TCP dial.

Durations use a monotonic clock. `runtime_active` means local runtime startup,
not a completed peer handshake; `tcp_attempt_connected` confirms a tunnel TCP
connection. A begin event without a result identifies work still in progress.
Large queue wait means earlier lifecycle work is blocking; large native startup
means config/library/DNS/runtime work; large TCP wait points to the peer/network
or netstack. Native startup status `-10` identifies peer endpoint DNS failure.
Java exceptions expose their type without exception messages or stack traces
that might contain profile data. Native tgnet's per-attempt TCP timing and
MTProto readiness follow the same tunnel build flag and use the same tag.

Read one lifecycle generation in this order:

- `runtime_active`: the WireGuard or AmneziaWG userspace runtime started;
  `runtime_refreshed` starts a new lifecycle generation after a successful
  network-binding refresh without restarting that runtime;
- `route_state`: the native route reached an account's tgnet queue;
- `tcp_dial_start` followed by `tcp_dial_connected`, `tcp_dial_failed`, or
  `tcp_dial_cancelled`: one direct tunnel TCP attempt, correlated by `attempt`;
  terminal events include time spent in the tunnel TCP dial as `elapsed_ms`;
- `tcp_attempt_*`: the Java aggregate state after the native callback, including
  `pending`, `success_seen`, and whether the 250 ms failure settlement was
  scheduled or cancelled; `tcp_attempt_group_failed` confirms that settlement
  completed with no success and selected a runtime restart;
- `mtproto_ready`: the first valid packet on the current datacenter's generic
  connection for that active route generation; `route_elapsed_ms` measures from
  active native route application.

The trace includes Telegram datacenter destinations but never profile contents,
WireGuard or AmneziaWG peer endpoints, private/preshared keys, or request
payloads. If `tcp_dial_connected` is slow, investigate the tunnel handshake or
netstack path. If it is fast but `mtproto_ready` is slow, investigate tgnet
address selection, MTProto handshake, or request processing. If no attempt
succeeds, use the aggregate `pending` and settlement events to distinguish a
long-running attempt from reconnect backoff.

Host checks exercise the native logger with the tunnel flag both on and off,
and each app-wide log state; disabled logging must not evaluate log arguments
or create files. They also cover records before initialization, Java/Go C ABI
inputs, queue drain, concurrent producers, stable export snapshots, rotation,
clear/reopen, preservation of prior-process records and recovery after export
or file-open failure. Go tests exercise enabled and disabled bridge diagnostics. Static guards prevent
Java and dedicated VoIP diagnostics from returning to app-wide log sinks and
prevent upstream Go device loggers from bypassing the flag via stdout.

## Static Guards

`verifyTunnelStaticGuards` protects invariants that should not be removed
accidentally:

- no Android `VpnService`, `Builder.establish()`, or `/dev/tun` in tunnel
  integration files;
- no silent direct fallback for tgnet or VoIP selected for tunnel routing;
- profile storage uses Android Keystore-backed AES-GCM and does not write
  plaintext profile contents to `mainconfig`;
- all route-mode changes go through `NetworkRouteSettings`;
- `ConnectionsManager.setProxySettings(...)` preserves the active native tunnel
  route;
- account startup and proxy updates gate the main WEB carrier behind tunnel
  selection; tunnel activation stops the main WEB carrier without resetting the
  native route, and delayed rotation cannot switch away from the tunnel;
- ordinary proxy persistence retains `proxy_type`, and ordinary VoIP proxies
  are restricted to SOCKS5;
- unsupported-device UI checks exist for add/import/scan/enable/select;
- parser rejects multiple peers;
- VoIP paths use `PROTOCOL_TUNNEL`, `TunnelPacketSocketFactory`, and the
  direct Go TCP/UDP tunnel socket bridge; private P2P UDP/STUN/ICE candidates
  remain available when Telegram allows P2P, but their sockets use the tunnel;
- private Java call creation forwards the selected proxy instead of `null`;
- native/WASM pump host retains descriptor routing and applies route policy on
  both creation and reconfiguration; legacy reflector TCP has no device socket
  factory in tunnel mode;
- tunnel VoIP DNS uses `tgTunnelLookupHost`, reflector TCP uses raw reflector
  framing, and WebRTC TLS socket wrapping uses `SSLAdapter` over the Go tunnel
  TCP handle;
- WireGuard and AmneziaWG Go cache/module artifacts stay out of
  `TMessagesProj/jni`;
- WireGuard and AmneziaWG use one shared Go bridge with a linker version script
  that hides non-API Go runtime symbols;
- Android Go bridge builds use the pinned managed Go 1.24.4 toolchain;
- AmneziaWG imports stay limited to the intended Go bridge dependency.

## Packaging Expectations

APK verification requires these libraries for every supported ABI:

- `libtg-wg.so`;
- `libtg-tunnel-go.so`;
- `libtg-awg.so`;

Static command-contract checks also require every Android Gradle native target
filter to include `tmessages.49`, `tg-wg`, and `tg-awg`. The shared Go library
is produced transitively from the JNI wrapper targets.

APK verification rejects entries containing:

- `gomod/`;
- `gocache/`;
- `tg_wg/go/build`;
- `tg_awg/go/build`;
- `tg_tunnel/go/build`;
- `libtg-wg-go.so`;
- `libtg-awg-go.so`;
- `vdso_`;
- downloaded Go module shared objects accidentally packaged from build caches.

## Manual Device Validation

Manual validation requires a real WireGuard server, a real AmneziaWG 2.0 server,
and packet capture or server-side connection logs where possible.

Cover these flows:

- migrate an app install with existing WireGuard profiles;
- configure WireGuard and AmneziaWG manually before login;
- import WireGuard and AmneziaWG config files;
- scan WireGuard and AmneziaWG QR codes from camera and gallery;
- restart the app and verify state persists;
- log in and verify the same settings are available after authorization;
- switch direct/proxy/WireGuard/AmneziaWG modes and verify mutual exclusion;
- switch between multiple profiles across both protocols;
- delete inactive and active profiles;
- simulate startup and tunnel TCP connection failures, verify traffic fails
  closed, and verify reconnect delays cycle through 1s, 2s, 2s, 3s, 3s, 5s,
  5s before restarting the sequence; verify the profile status changes from
  `Tunnel failed` to `Tunnel failed, reconnecting` when each retry starts and
  finally to `Tunnel connected` after TCP succeeds, and verify the failure
  reason appears once in a short toast when `Tunnel failed` is entered;
- with an IPv4-only profile, trigger parallel and sequential IPv6/IPv4 tgnet
  attempts; verify an immediate IPv6 `no route to host` closes only that attempt,
  IPv4 continues through the tunnel, and no direct socket fallback occurs;
- switch Wi-Fi/LTE while each protocol is active;
- send messages/media while each protocol is active;
- start new private and group/live VoIP sessions while each protocol is active;
- repeat private and group/live session creation with `Use tunnel for calls`
  enabled and disabled;
- verify allowed private P2P and fallback relay both use the tunnel when
  `Use tunnel for calls` is enabled;
- verify Telegram datacenter traffic does not go direct while either protocol is
  enabled.

## Maintenance

- Keep Java logic unit-testable without Android runtime dependencies.
- Keep Android-only Go exports and logging behind Android build tags.
- Pin AmneziaWG to a reviewed tag or commit and update tests when the pin
  changes.
- Re-run license review when tunnel dependencies change.
- Do not place Go module/cache output under `TMessagesProj/jni`.
- Do not add SOCKS5 UDP ASSOCIATE assertions for tunnel routing; VoIP UDP tunnel
  coverage belongs to the direct tunnel socket bridge.
- Do not add real-server tests to default Gradle tasks.
- When an invariant intentionally changes, update tests, static guards, docs,
  and `AGENTS.md` together.

## Proxy And Tunnel Interaction Smoke Checks

On a device, exercise both WG and AWG with a saved WEB proxy: cold start,
WEB-to-tunnel switching, a blocked tunnel, and more than one Telegram account.
The main WEB carrier must not run while the tunnel is selected, and a failed
tunnel must not reconnect directly. Check a delayed rotation callback and a
proxy-error dialog opened before tunnel activation: neither may change the
selected tunnel route. Diagnostic proxy availability probes are separate from
the selected Telegram route.

Check SOCKS5/MTPROTO/WEB links and editor saves. Editing the disabled current
proxy must retain its type without enabling it; explicit proxy activation must
disable the tunnel. Verify ordinary proxy-for-calls appears only for SOCKS5,
and tunnel-for-calls continues to control only newly created sessions. Include
private P2P/relay, group and live sessions in the server-backed smoke test.

Verify that the proxy/tunnel entry remains available before login. Static
guards do not replace route-switching UI or real-server checks.

### Native/WASM Call Routing

On arm64, exercise both 18.0.0 and 19.0.0 with WG and AWG. Cover P2P on/off,
UDP relay, TCP-only TURN, hostname TURN with tunnel DNS, session reconfiguration,
and stopped/restarted tunnel runtimes. Capture traffic to confirm no direct
relay/peer or system DNS bypass when tunnel-for-calls is enabled. Repeat with
tunnel-for-calls disabled and with an ordinary SOCKS5 call proxy.
Static source guards and APK compilation cover wiring, not actual packet paths;
these runtime cases require a device and test servers.

SOCKS5 regression tests also run transitively in the canonical commands using a
host C++17 compiler (`CXX`, default `c++`). They cover no-auth and authenticated
CONNECT, IPv4/IPv6/domain destinations, byte-wise output draining, fragmented
responses, coalesced application data, malformed replies and proxy rejection.
The native socket adapters and both normal/raw TCP factories are compiled in
the APK check; socket callback timing still requires a device smoke test.
Ordinary SOCKS5 call tests must verify TCP relay traffic reaches the proxy and
UDP relay is suppressed; proxy failure must not connect directly to a relay.
