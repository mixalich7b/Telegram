# WireGuard Integration Plan

## Goal

Add an option for the Android Telegram client to send Telegram network traffic through a WireGuard server without starting Android `VpnService`, without creating a TUN device, and without changing UI.

The first implementation stage uses a hardcoded WireGuard configuration in code. UI and persistent user settings are intentionally out of scope.

## Key Constraint

The standard Android WireGuard `GoBackend` is not suitable for this requirement as-is. It creates an Android VPN tunnel through `VpnService.Builder.establish()` and passes the resulting TUN file descriptor to `wgTurnOn`.

For this client, WireGuard must run completely inside the process and expose stream dialing to Telegram networking code. The practical userspace path is:

```text
tgnet TCP traffic
  -> internal SOCKS5 server on 127.0.0.1
  -> wireguard-go + tun/netstack
  -> UDP packets to the configured WireGuard server
  -> Telegram datacenter
```

This avoids Android VPN APIs and does not create a virtual network interface on the device.

## Current Telegram Network Entry Points

- `TMessagesProj/jni/tgnet/ConnectionSocket.cpp`
  - Creates sockets, connects, reads, writes, and handles SOCKS5 / MTProto TLS proxy logic.
- `TMessagesProj/jni/tgnet/ConnectionsManager.cpp`
  - Stores proxy settings and schedules reconnects.
- `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`
  - Exposes `setProxySettings(...)` and native proxy initialization.
- `TMessagesProj/jni/CMakeLists.txt`
  - Builds native `tgnet`.

## MVP Architecture

Use Telegram's existing SOCKS5 support as the integration point:

1. Start an internal SOCKS5 server bound to `127.0.0.1` on an ephemeral port.
2. Authenticate it with generated username/password so only this process should use it.
3. For each SOCKS5 `CONNECT host:port`, open the outbound TCP stream through WireGuard userspace netstack.
4. Programmatically call:

```java
ConnectionsManager.setProxySettings(true, "127.0.0.1", port, username, password, "");
```

5. Do not touch proxy UI in this stage.

## Implementation Tasks

### 1. Static Configuration

Add a code-only config holder, for example:

```text
org.telegram.messenger.WireGuardConfig
```

It should contain:

- `ENABLED`
- interface private key
- interface address list
- peer public key
- optional preshared key
- peer endpoint host/port
- allowed IPs
- DNS servers for userspace netstack
- MTU
- persistent keepalive

No real keys should be committed in the default version.

Current status:

- Added `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardConfig.java`.
- `ENABLED` is `false` by default.
- Real WireGuard keys/endpoints are intentionally empty.
- Keys are stored in standard WireGuard/base64 form and converted to userspace IPC hex form in code.

### 2. WireGuard Runtime

Add a runtime wrapper, for example:

```text
org.telegram.messenger.WireGuardManager
```

Responsibilities:

- Start once during application initialization.
- Load native WireGuard shared library.
- Start the Go userspace WireGuard device.
- Start the internal SOCKS5 server.
- Apply Telegram proxy settings.
- Stop or restart on network changes if needed.

Current status:

- Added `TMessagesProj/src/main/java/org/telegram/messenger/WireGuardManager.java`.
- Startup is called from `ApplicationLoader.postInitApplication()`.
- Network changes call `WireGuardManager.onNetworkChanged()`.
- `ConnectionsManager.init(...)` applies WireGuard internal SOCKS settings per account when enabled.
- If WireGuard is enabled but fails to start, a blocked local proxy is applied to avoid silent direct fallback.

### 3. Native / Go Library

Create a dedicated Android shared library, for example `libtg-wg.so`.

Use:

- `wireguard-go`
- `wireguard-go/tun/netstack`
- `device.NewDevice(...)`
- `device.IpcSet(...)`
- `device.Up()`
- `Net.DialContext(...)`

Export a minimal JNI or C ABI:

- `start(config, socksHost, socksPort, socksUsername, socksPassword) -> actualPort`
- `stop()`
- `isRunning()`
- `getStats()` optional for diagnostics

The SOCKS server can be implemented either in Go or Java. For MVP, Go is preferred because it can directly call `tnet.DialContext` without per-connection JNI callbacks.

Current status:

- Added JNI wrapper `TMessagesProj/jni/tg_wg/tg_wg_jni.cpp`.
- Added Go bridge under `TMessagesProj/jni/tg_wg/go/`.
- The Go bridge creates `wireguard-go/tun/netstack`, starts a WireGuard device, and runs an authenticated internal SOCKS5 server.
- Native build is gated by `TG_WIREGUARD`; default is off.
- Enable native build with either `-PTG_WIREGUARD=true` or `TG_WIREGUARD=true` in `local.properties`.
- Added `go.sum` for reproducible Go module resolution.
- Go module/cache output is forced outside `TMessagesProj/jni` via `TG_WG_BUILD_DIR=${CMAKE_CURRENT_BINARY_DIR}/tg_wg_go_build`, so Android Gradle does not scan downloaded module `.so` files as app JNI libraries.
- The project-wide CMake `--exclude-libs` linker flags are emitted once per archive. This avoids a linker failure in the standalone `libtg-wg.so` target on `x86_64`.

### 4. Startup Integration

Call `WireGuardManager.startIfEnabled()` from application initialization before or during `ConnectionsManager` initialization.

The chosen hook should be early enough that account network connections pick up proxy settings before opening direct sockets.

### 5. Proxy Conflict Policy

For MVP:

- If hardcoded WireGuard is enabled, it has priority.
- Existing proxy UI remains unchanged.
- Telegram runtime proxy settings are overridden in memory with internal SOCKS5 settings.
- Future stage can add UI and explicit conflict resolution.

### 6. Failure Policy

MVP behavior:

- If WireGuard startup fails, log the failure and do not silently fall back to direct Telegram connections unless explicitly configured.
- If internal SOCKS5 or WireGuard runtime stops, disconnect active Telegram connections and retry startup.
- Avoid leaking traffic directly when `WireGuardConfig.ENABLED` is true.

### 7. Review Remediation Plan

This section tracks the fixes required after review before the implementation can be considered complete for the original requirement.

Current status: implemented in TCP-safe VoIP mode. UDP ASSOCIATE remains a future enhancement.

#### 7.1 Prevent Runtime Proxy Bypass

Problem:

- `ConnectionsManager.init(...)` applies the internal WireGuard SOCKS proxy when WireGuard is enabled.
- Later calls to `ConnectionsManager.setProxySettings(...)` can still clear proxy settings or replace them with a user-configured proxy.
- Those later calls are reachable from existing proxy UI, proxy rotation, proxy links, and cleanup flows.
- With `WireGuardConfig.ENABLED = true`, this can violate the fail-closed requirement by allowing direct traffic or a non-WireGuard proxy after startup.

Target behavior:

- When `WireGuardConfig.ENABLED` is `true`, runtime Telegram proxy settings are always the internal WireGuard SOCKS endpoint or the blocked local fail-closed endpoint.
- Existing proxy UI and stored preferences remain unchanged in this stage.
- Any call path that tries to change Telegram proxy settings while WireGuard is enabled must reapply WireGuard settings instead of the requested user proxy settings.
- User proxy settings continue to work unchanged when WireGuard is disabled.

Implementation plan:

1. Add a Java-side method such as `WireGuardManager.applyProxySettingsForAllAccounts()`.
2. Keep `WireGuardManager.applyProxySettingsForAccount(int account)` as the single source of truth for choosing:
   - internal SOCKS host/port/credentials when WireGuard is running;
   - blocked local proxy when WireGuard is enabled but unavailable.
3. Update `ConnectionsManager.setProxySettings(...)` to check `WireGuardManager.isEnabled()` before applying the requested proxy values.
4. If WireGuard is enabled, ignore the requested proxy values, apply WireGuard settings to every account, and preserve the existing per-account side effects that are still required, such as active-account promo/config checks.
5. Do not modify stored proxy preferences from this guard; only override in-memory native `tgnet` settings.
6. Add unit coverage around the controller/facade logic so enabled WireGuard cannot be overridden by later proxy calls.
7. Add a static guard that fails the build if `ConnectionsManager.setProxySettings(...)` no longer checks `WireGuardManager.isEnabled()`.

Verification:

- With WireGuard disabled, existing proxy settings still pass through unchanged.
- With WireGuard enabled and running, `ConnectionsManager.setProxySettings(false, "", ...)` still leaves every account pointed at the internal WireGuard SOCKS endpoint.
- With WireGuard enabled and failed, every account gets the blocked local proxy and does not fall back to direct.
- The full Gradle command still runs the new tests together with the build.

Current status:

- Added `WireGuardManager.applyProxySettingsForAllAccounts()`.
- `ConnectionsManager.setProxySettings(...)` now calls `WireGuardManager.applyProxySettingsForAccount(...)` for each account before considering user proxy values.
- Existing proxy preferences and UI state are not modified; only runtime native proxy settings are overridden.
- Added controller tests and static guards for this behavior.

#### 7.2 Handle Android Network Changes

Problem:

- Android network change broadcasts currently reach `WireGuardManager.onNetworkChanged()`.
- The Java controller forwards the event to native code only when the runtime is marked running.
- The Go userspace runtime currently only logs the event, so Wi-Fi/LTE changes can leave the WireGuard UDP bind or peer endpoint state stale.

Target behavior:

- WireGuard remains usable after Wi-Fi/LTE/VPN-disabled network transitions without creating an Android VPN interface.
- If the userspace WireGuard bind can be refreshed in place, keep the internal SOCKS listener and update the bind.
- If refresh fails, restart the WireGuard runtime and reapply proxy settings.
- If restart fails, apply the blocked local proxy so Telegram traffic does not silently go direct.

Implementation plan:

1. Change the native network-change ABI to return a status code instead of `void`.
   - JNI: `nativeOnNetworkChanged() -> int`.
   - C ABI: `tgWgOnNetworkChanged() -> int`.
2. In Go, protect runtime state with `stateMu` and call `state.device.BindUpdate()` when a runtime exists.
3. Return a success code when `BindUpdate()` succeeds or no runtime is active; return a negative error code on failure.
4. Extend `WireGuardController.onNetworkChanged()` so a failed native refresh:
   - stops the native runtime;
   - clears `running` and `startAttempted`;
   - attempts a clean restart with the existing config;
   - reapplies internal SOCKS settings to all accounts on success;
   - applies blocked proxy settings to all accounts on failure.
5. Keep the existing `ConnectionsManager.checkConnection()` call path; it already runs after the network broadcast and updates tgnet network availability.
6. Add test seams for native network-change status so JVM tests can verify success, failure, restart, and fail-closed behavior without an emulator.
7. Add Go tests around `onNetworkChangedRuntime()` by introducing a small device interface that can fake `BindUpdate()` success/failure.

Verification:

- Unit tests prove `BindUpdate()` is called only while running.
- Unit tests prove native refresh failure causes a restart attempt.
- Unit tests prove restart failure applies the blocked proxy.
- Manual/device smoke test switches Wi-Fi/LTE and confirms traffic resumes through the WireGuard server.

Current status:

- `nativeOnNetworkChanged()` and `tgWgOnNetworkChanged()` now return status codes.
- The Go runtime calls `device.BindUpdate()` on network changes.
- Java controller restarts WireGuard and reapplies proxy settings when native refresh fails.
- Restart failure applies the blocked local proxy to all accounts.
- Added Go and JVM tests for success/failure paths.

#### 7.3 Route VoIP Through WireGuard

Problem:

- The current MVP routes `tgnet` through the internal WireGuard SOCKS endpoint.
- Private VoIP calls have an existing `Instance.Proxy` path, but it is only populated from user proxy preferences when `proxy_enabled_calls` is set.
- Group calls use `NativeInstance.makeGroup(...)`, which currently has no Java proxy parameter and no proxy field in the group native descriptor.
- The internal Go proxy supports TCP `CONNECT` semantics. MTProto uses SOCKS5, while WebRTC VoIP uses HTTP CONNECT because this WebRTC tree has an HTTPS proxy socket adapter but does not implement SOCKS5 client proxy sockets in `BasicPacketSocketFactory`.
- Legacy `libtgvoip` also supports SOCKS5 UDP proxying, which requires UDP ASSOCIATE support if we want to preserve UDP relay behavior through WireGuard.

Target behavior:

- When WireGuard is enabled, private VoIP calls must use the internal WireGuard proxy regardless of user proxy preferences.
- VoIP must not use direct P2P paths while WireGuard routing is mandatory.
- Group-call networking must receive the same WireGuard proxy policy, not only private one-to-one calls.
- Initial safe implementation may force VoIP to TCP relay through the internal HTTP CONNECT path.
- UDP relay support can be restored after adding SOCKS5 UDP ASSOCIATE to the Go bridge.

Implementation plan:

1. Add a Java value object or adapter method that exposes the current WireGuard proxy endpoint for VoIP:
   - host;
   - port;
   - username;
   - password;
   - blocked/failure state.
2. In `VoIPService` private-call setup:
   - call `WireGuardManager.startIfEnabled()` before constructing VoIP networking config;
   - if WireGuard is enabled and running, create `Instance.Proxy` from the internal WireGuard proxy endpoint;
   - if WireGuard is enabled but unavailable, either fail the call setup early or pass the blocked proxy so it cannot connect directly;
   - set `enableP2p = false` in `Instance.Config` while WireGuard is enabled;
   - force `Instance.ENDPOINT_TYPE_TCP_RELAY` for the initial safe implementation unless UDP ASSOCIATE has already been implemented and verified.
3. Extend group-call construction:
   - add an optional `Instance.Proxy` parameter to `NativeInstance.makeGroup(...)`;
   - pass it through `makeGroupNativeInstance(...)`;
   - add an optional proxy field to `GroupInstanceDescriptor`;
   - pass the proxy into the group network manager.
4. Update group native networking:
   - use `cricket::BasicPortAllocator::set_proxy(...)` with HTTP CONNECT when the proxy was created by WireGuard;
   - keep default `Instance.Proxy` behavior as SOCKS5 for user-configured VoIP proxies;
   - disable direct/P2P candidate paths while WireGuard routing is mandatory;
   - if the initial implementation remains TCP-only, restrict group networking to TCP relay candidates that the CONNECT path can carry.
5. Decision for this implementation: keep TCP-only VoIP safe mode.
   - TCP-only safe mode is lower risk and prevents direct leakage, but can degrade call quality.
   - UDP ASSOCIATE preserves normal relay behavior, but requires implementing and testing UDP packet encapsulation in the Go SOCKS server.
6. If UDP ASSOCIATE is implemented:
   - support SOCKS5 command `0x03`;
   - bind a local UDP socket on `127.0.0.1`;
   - return the UDP relay address in the SOCKS response;
   - parse and emit SOCKS5 UDP datagrams;
   - send outbound UDP through `wireguard-go/tun/netstack` with `DialUDP`/`DialContext("udp", ...)`;
   - map client UDP flows to remote endpoints and close them on control-connection close.
7. Add tests:
   - JVM tests for private-call routing decisions: WireGuard proxy overrides user proxy, disables P2P, and forces TCP relay in safe mode.
   - JVM tests for group-call proxy propagation at the Java/JNI boundary where practical.
   - Go tests for HTTP CONNECT WebRTC proxying and UDP ASSOCIATE if UDP support is implemented.
   - Static guard that `VoIPService` and `NativeInstance.makeGroup(...)` reference the WireGuard proxy path when `WireGuardConfig.ENABLED` can be true.
8. Add manual/device verification:
   - private voice call;
   - private video call;
   - group voice chat;
   - group video/screen-share where supported;
   - Wi-Fi/LTE switch during an active call.

Verification:

- Packet capture on device shows no direct UDP/TCP packets to Telegram VoIP endpoints when WireGuard is enabled.
- Server-side capture shows VoIP endpoint traffic exiting from the WireGuard server.
- App-side logs show private and group VoIP instances receiving the internal WireGuard proxy.
- Calls fail closed if the WireGuard runtime is not available.

Current status:

- Added `WireGuardProxySettings` as the shared runtime proxy endpoint.
- Added `WireGuardVoipRouting` for unit-testable private-call routing decisions.
- Private calls now use the WireGuard proxy regardless of `proxy_enabled_calls` when WireGuard is enabled.
- Private calls disable P2P and force `ENDPOINT_TYPE_TCP_RELAY` while WireGuard routing is active.
- `NativeInstance.makeGroup(...)` accepts an optional `Instance.Proxy`.
- `VoIPService` group calls and `LivePlayer` group/live streaming pass the WireGuard proxy into native group networking.
- `Instance.Proxy` now carries a protocol flag: default/user proxies stay SOCKS5, WireGuard-created VoIP proxies use HTTP CONNECT.
- The local Go proxy accepts HTTP CONNECT alongside SOCKS5 so WebRTC TCP sockets can use the existing HTTPS proxy adapter and still dial through WireGuard netstack.
- `GroupInstanceDescriptor` and `GroupNetworkManager` now carry the proxy into WebRTC `BasicPortAllocator::set_proxy(...)`.
- Group native networking disables UDP/STUN candidate paths when the WireGuard proxy is present.
- Private and group native WebRTC managers now drop `CF_HOST` and `CF_REFLEXIVE` candidates in WireGuard HTTP CONNECT mode, preventing direct ICE host paths from bypassing the proxy.
- Private VoIP V2 custom networking now keeps TCP enabled when a proxy is present, configures `BasicPortAllocator::set_proxy(...)`, and disables UDP/STUN plus host/reflexive candidates for WireGuard HTTP CONNECT mode.
- Private VoIP V2 reference PeerConnection networking now configures the injected allocator with the WireGuard proxy, forces relay-only ICE when a proxy is present, keeps TCP TURN servers via `?transport=tcp`, and enables TCP candidates so HTTP CONNECT relay sockets can be used.
- Added JVM tests for private-call routing decisions and static guards for group-call proxy wiring.
- Static guards now cover old private networking, V2 custom networking, V2 reference networking, and group networking.
- UDP ASSOCIATE is not implemented yet; that remains the path for restoring UDP relay call quality.

### 8. Verification

Minimum checks:

- App builds for supported Android ABIs.
- With `ENABLED = false`, behavior is unchanged.
- With `ENABLED = true`, Telegram connects through internal SOCKS5.
- Device does not show an Android VPN interface/session.
- Packet capture shows only UDP traffic to the configured WireGuard server, not direct Telegram datacenter TCP connections.
- Server-side logs confirm Telegram datacenter traffic exits from the WireGuard server.
- Test login, message send/receive, media download/upload, push connection, private VoIP calls, group calls, Wi-Fi/LTE switch, and app background/foreground.

Current verification notes:

- Gradle wrapper and initial dependencies were downloaded.
- `env GRADLE_USER_HOME=/Users/mixalich7b/projects/Telegram/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=false`
  - Result: `BUILD SUCCESSFUL in 16m 1s`.
  - This verifies that the default disabled WireGuard path still builds.
- `env GRADLE_USER_HOME=/Users/mixalich7b/projects/Telegram/.gradle ./gradlew --no-daemon :TMessagesProj_App:assembleAfatDebug -PTG_WIREGUARD=true`
  - Initial failure: missing `go.sum`; fixed with `go mod tidy`.
  - Initial failure: `libtg-wg.so` inherited a malformed multi-archive `--exclude-libs` linker flag; fixed by emitting one linker flag per archive in CMake.
  - Initial failure: Android Gradle scanned `TMessagesProj/jni/tg_wg/go/build/gomod/.../vdso_amd64.so` as a JNI library; fixed by moving Go build/cache output under the ABI-specific CMake build directory.
  - Result after fixes: `BUILD SUCCESSFUL in 1m 14s`.
  - Result after final host-candidate remediation: `BUILD SUCCESSFUL in 1m 30s`; static guards now check `CF_HOST` and `CF_REFLEXIVE` filtering for private and group WebRTC managers.
  - Follow-up review found private VoIP V2 networking gaps; fixed by applying the same HTTP CONNECT proxy, TCP relay, and no-host/no-reflexive candidate invariants to `NativeNetworkingImpl` and `InstanceV2ReferenceImpl`.

## Second-Stage Alternative

If the internal SOCKS5 hop is unacceptable, replace POSIX socket calls in `ConnectionSocket.cpp` with a transport abstraction:

```text
ConnectionSocket
  -> PlainSocketTransport
  -> WireGuardStreamTransport
```

This would replace:

- `socket/connect`
- `epoll_ctl`
- `recv`
- `send`
- `close`

The WireGuard transport would call a C/JNI API backed by Go `net.Conn`. This is cleaner but higher risk because it changes Telegram's native event-loop assumptions.

## Open Risks

- `wireguard-go/tun/netstack` adds binary size and CPU overhead.
- Non-`tgnet` networking beyond the audited VoIP paths must be reviewed separately if it also needs mandatory WireGuard routing.
- TCP-only VoIP safe mode can degrade call quality. Restoring UDP relay quality requires implementing SOCKS5 UDP ASSOCIATE in the Go bridge.
- Go Android builds need a reproducible toolchain setup in Gradle/CMake.
- License review is required before release: `wireguard-go` is MIT, `wireguard-android` tunnel library is Apache-2.0, this Telegram client code includes GPL components.

## Plan Update Policy

During implementation, update this file when:

- the selected architecture changes;
- a task is completed or split;
- a blocker changes the scope;
- verification discovers a new requirement.
