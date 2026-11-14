# Merge master into feat/wg-embed: 2026-11-14

## Inputs

- Current branch before merge: `4271e63a0`.
- Local master: `21f7d109d` (Telegram 13.2.2 / 7303).
- Common ancestor: `0aae959806c57be8ad457980f2a5c6277ff18635`.
- Working tree was clean before merge; preview found 15 conflicting files.

## Resolution plan (communicated before resolving conflicts)

1. Combine upstream Gradle/CMake changes with all tunnel targets, the single Go
   runtime, independent tunnel logging and canonical checks.
2. Preserve tgnet delegate hooks and merge upstream API changes. Audit native
   and Java ConnectionsManager/JNI, fail-closed routing, generation handling,
   parallel TCP attempts and network refresh.
3. Retain pre-authorization tunnel access and proxy/tunnel mutual exclusion while
   integrating upstream UI/observer changes.
4. Combine audio/settings changes without losing custom recording behavior.
5. Port the tgcalls patch series to the upstream gitlink and audit all five
   private/group/live network paths.
6. Run `./gradlew --no-daemon tunnelPackageDebug`, resolve failures and commit
   the merge after verification.

## Resolutions and audit

- All Android native target lists retain `tmessages.49`, `tg-wg`, `tg-awg` and
  add upstream `twidget`; native platform follows upstream Android 23.
- Version follows upstream 13.2.2 / 7303; custom application package is retained.
- `ConnectionsManagerDelegate` retains tunnel lifecycle callbacks and its virtual
  destructor. CAPTCHA generation is synchronized across Java, JNI and C++.
- Upstream tgnet edits add CAPTCHA retries/generation and deserialization bounds;
  they do not change our transport socket implementation. Tunnel managers,
  controller, encrypted storage and Go socket bridge remain unchanged.
- Upstream IPv6 relay alternatives use the same routed socket factories as IPv4.
  Unsupported tunnel address families still fail closed.
- tgcalls stays an unmodified upstream submodule at
  `545f5ae599f8282e1ae3f38aaff166d179d75703`. Patches were ported using a temporary
  export/merge; only patch inputs and the pin are committed.
- CMake compiles `MtProtoDtlsTransport.cpp` in place of the removed ICE transport.
  Upstream external transport security, SCTP options and video-negotiation fixes
  remain. Remote candidate IP resolution retains its upstream default-on value.
- Legacy, V2 custom/reference, native/WASM pump and group network paths retain
  tunnel socket factories and tunnel DNS. Tunnel reflector factories receive
  no device socket factory; pump creation and reconfiguration retain descriptor
  P2P/route policy. TCP TURN and reflector framing remain intact.
- Proxy/login observers use upstream weak observers, including custom tunnel and
  route notifications. Route changes still go through NetworkRouteSettings.
- Proxy cell theme failures use the dedicated sanitized TunnelLog sink, satisfying
  the independent logging guard without logging raw exception data.
- Recording enablement remains centralized in SharedSettings. Upstream's pending
  recording result lifecycle now owns schedule-picker retention; controller
  completion is delivered immediately to avoid double retention. Audio focus
  and Bluetooth SCO are released while the picker is open.
- Independent network/UI review found no critical or important issues.

## Verification

The canonical command is `./gradlew --no-daemon tunnelPackageDebug`.
The first executable run failed the independent-log static guard after upstream
introduced FileLog into ProxyListActivity; the source was corrected and the full
canonical command restarted.

Final run: **BUILD SUCCESSFUL in 10m 54s**, exit code 0; 319 actionable tasks
(50 executed, 269 up-to-date). It passed 77 JVM tests across 8 suites with no
failures/errors/skips, shared Go tests, host SOCKS5/logging checks, all 9 tgcalls
preparation tests, static guards, native compilation for arm64-v8a/armeabi-v7a/
x86/x86_64, APK assembly and `verifyWireGuardAfatDebugPackaging`.
Compiler/deprecation warnings remain in upstream sources.

Real-server handshakes, packet capture, and active calls require device smoke
validation; automated checks do not establish those results. Additional upstream
merge smoke scenarios are listed in docs/tunnel-testing.md.
