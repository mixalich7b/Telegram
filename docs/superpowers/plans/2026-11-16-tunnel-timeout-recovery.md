# Tunnel TCP timeout recovery implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline after approval. Steps use checkbox syntax for tracking.

**Goal:** Recover from repeated tunnel TCP timeouts without manual tunnel toggling or interrupting other pending/successful attempts.

**Architecture:** Classify native terminal outcomes correctly, then bound admission to a continuously failing attempt group. Use the existing Java controller, serial lifecycle queue, failure settlement and reconnect backoff.

**Tech stack:** C++17, Java, JNI, host C++/JVM tests, Android Gradle.

**Spec:** `docs/superpowers/specs/2026-11-16-tunnel-timeout-recovery-design.md`

## Global constraints

- No direct fallback, Android VpnService or device TUN.
- Never restart while another admitted attempt is pending or the current group has succeeded.
- Keep cancellation distinct from failure and preserve the original lifecycle generation.
- Lifecycle/native recovery stays on the serial lifecycle queue; no native work under the controller state lock or on the UI thread.
- Reconnect backoff resets on TCP success, not userspace runtime startup.
- Keep all diagnostics behind TG_TUNNEL_LOGS_ENABLED and out of profile/payload data.
- Keep the existing 20-second Go timeout and 250 ms failure settlement; propose a separate 20-second failing-group admission window.

## Review focus

- Timeout versus success/closure races: exactly one terminal outcome, no stale callbacks applied to a new generation.
- IPv6/alternate-address failure with working IPv4: no premature restart or direct bypass.
- Continuous overlapping retries across accounts: finite admission window, no unbounded postponement of recovery.
- Success during draining: cancel recovery and reopen admission without stopping the runtime.
- Disable/network refresh/profile change during recovery: invalidate timers and reject stale generation admissions; no resurrection of a disabled route.

## Task 1: Classify native TCP timeout outcomes

**Files:**
- Modify `TMessagesProj/jni/tgnet/ConnectionSocket.cpp` and `.h`.
- Extract a small host-testable outcome helper under `TMessagesProj/jni/tgnet/` if required to test the real completion logic without an Android event loop.
- Add host coverage under `TMessagesProj/src/test/cpp/` and wire it into the existing canonical host checks in `TMessagesProj/build.gradle`.

**Interfaces:** Keep existing delegate `onTunnelTcpConnectFailed`, `onTunnelTcpConnected`, and `onTunnelTcpConnectCancelled` callbacks and their generation argument. Centralize terminal completion around the existing `connectAttemptCompleted.exchange(true)` ownership rule.

- [x] Write failing tests for: unfinished dial timeout emits FAILED once; explicit suspension emits CANCELLED once; timeout after CONNECTED emits no additional dial result; late Go failure/success after timeout emits no second result; replacement attempts retain distinct generations. Exercise real outcome logic, not assertions on source tokens.
- [x] Observe the timeout case failing against the current cancellation behavior.
- [x] In `checkTimeout`/`closeSocket`, classify reason=2 on an unfinished tunnel dial as failure before common cleanup can mark it cancelled. Leave transport errors after successful dialing and ordinary close/suspend semantics intact. Use one completion helper across success, native failure and cancellation.
- [x] Preserve existing correlation formats and add a fixed failure source such as `tgnet_timeout` or `go_connect_failed`. Never invent a system errno for a Go API result.
- [x] Verify the tests, including a deterministic completion race test, and inspect that cleanup cannot report both failure and cancellation. Keep native state/generation checks for delayed Go callbacks.

## Task 2: Guarantee progress for a continuously failing attempt group

**Files:**
- Modify `TMessagesProj/src/main/java/org/telegram/messenger/TunnelController.java` and `TunnelManager.java`.
- Modify `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`.
- Modify `TMessagesProj/jni/tgnet/Defines.h`, `ConnectionSocket.cpp`, and `TMessagesProj/jni/TgNetWrapper.cpp`.
- Extend `TMessagesProj/src/test/java/org/telegram/messenger/TunnelControllerTest.java`.

**Interfaces:** Change `onTunnelTcpConnectStarted(account, lifecycleGeneration)` to return admission (`boolean` in Java, `bool` in C++). Update the complete JNI chain from `(IJ)V`/CallStaticVoidMethod to `(IJ)Z`/CallStaticBooleanMethod. The admission decision and increment of pending count must be atomic under the controller lock; it must not perform native work. A missing delegate or JNI exception must reject admission fail-closed.

- [x] First write failing scheduler-driven tests with no real sleeps:
  - `timedOutOnlyGroupSchedulesRecovery`: failed terminal results, pending=0, no success schedules settlement then exactly one stop/reconnect.
  - `continuousFailuresEventuallyDeferNewAdmissions`: repeated starts cannot extend the first-failure 20-second window; expiry denies new starts without incrementing pending.
  - `drainingWaitsForLastAdmittedAttempt`: expiry with pending>0 does not stop the runtime; the last unsuccessful completion allows recovery.
  - `successDuringDrainReopensAdmission`: success cancels settlement/window, accepts new attempts and prevents stop.
  - `ordinaryCancellationsNeverOpenRecoveryWindow`: no failure-driven restart on suspension alone.
  - `staleRecoveryTimersCannotAffectNewGeneration`: old timers/callbacks do nothing after disable, refresh or profile restart.
  - Retain existing eight-parallel-attempt and sequential IPv4-after-IPv6 tests; add a four-account staggered-timeout sequence matching the supplied trace, including a new start within 250 ms of pending reaching zero.
- [x] Confirm the recovery/starvation tests fail before changing the controller.
- [x] Track a first-failure episode and its scheduled deadline independently of the short failure-settlement task. On deadline, close admission only if failures remain with no success. Do not block the route globally to drain: that would close admitted sockets. Allow them their existing bounded dial deadlines.
- [x] Cancel the recovery episode on successful TCP connection and on lifecycle invalidation. Preserve failed-episode state across newly joining attempts and brief empty gaps. Ordinary cancelled-only groups must never start the window.
- [x] Reject new native starts during draining with a transient tunnel-only close (`EAGAIN`), log `tcp_attempt_deferred`, and create no Go thread/dial. Denied attempts receive no connected/failed/cancelled aggregate callback. Resolve admission before starting network work and before a deferred state could be mistaken for a counted attempt.
- [x] Reuse the existing settlement and reconnect/backoff path after all admitted attempts finish unsuccessfully. Success always wins over a pending restart decision for the current group.
- [x] Log episode start/deadline, admission deferral, drain completion, success cancellation and recovery reason with generation and pending count. Aggregate repetitive deferrals; retain existing privacy and flag behavior.
- [x] Verify all scheduler tests, stale JNI signature guards and callback balance, including concurrent multi-account admission at the deadline. Review compatibility delegates that implement `onTunnelTcpConnectStarted` before changing its return type.

## Task 3: Documentation, canonical verification and device acceptance

**Files:** `AGENTS.md`, `docs/tunnel-testing.md`, `docs/tunnel-integration.md`, and `TMessagesProj/build.gradle` static guards/host-test wiring.

- [x] Document timeout classification, failing-group window/draining semantics and new events. Clarify the distinction between logical tgnet termination and late completion of a Go dial. Update guards for JNI boolean admission and outcome handling.
- [x] Run exactly the native canonical command: `./gradlew --no-daemon tunnelPackageDebug`. Require JVM, Go, host C++, independent logging on/off, static guards and final APK packaging verification to pass. Do not add Telegram/signing `-P` arguments or run alternative lower-level Gradle verification tasks.
- [x] Request an independent review focused on completion races, JNI admission, cross-account pending balance, scheduler invalidation, and fail-closed behavior. Fix material findings and rerun the canonical command when code changes require it.
- [ ] Device acceptance — not run; requires a device and reproducible peer-path failure. When that environment is available: interrupt the tunnel peer path without disabling the profile, keep several accounts retrying, restore connectivity, and verify automatic recovery without toggling. Also verify a working concurrent/alternate-address connection prevents runtime restart. Capture a fresh export; do not claim this smoke test from host/build results.
- [x] Report any remaining unknowns about the original UDP/server-path failure. Create a commit only when requested; keep supplied `tunnel-logs` out of it.

## Completion criteria

An unfinished dial timeout is observable as failure exactly once. Ordinary cancellation remains harmless. A failing group cannot admit replacements forever; existing attempts and successful alternatives retain protection. Recovery uses existing fail-closed lifecycle/backoff semantics, with no direct fallback. Canonical packaging verification passes; device verification is separately recorded if performed.

## Execution record

- Implementation authorized by the user on 2026-11-16; working inline in the existing feature checkout. No commit requested.
- Task 1: host test first failed because the outcome helper was absent. Implemented the shared terminal-outcome helper and integrated native close/success/failure paths. Host tests pass, including 100 competing timeout/success completions and late-result rejection. Unfinished socket errors also count as failure; explicit reason=0 closure stays cancellation.
- Task 2 RED: new admission tests initially failed to compile against the void API; after changing the return contract only, five recovery tests failed because no recovery deadline existed. Implemented the episode/deadline/draining state and complete boolean JNI chain.
- Task 2 targeted result: all 42 TunnelController JVM tests pass without Gradle or real sleeps. Corrected the success-during-drain fixture to keep another attempt in the successful group: success must not protect unrelated future groups forever.
- Native/JNI integration and static guards completed. Independent review returned no material findings. Canonical APK verification completed successfully; JVM/Go/host C++ and static guards passed. Device/network reproduction has not been run.

- Additional admission exception test reproduced a leaked pending count when diagnostic logging threw after admission. Moved all potentially throwing admission work before committing pending; the test now passes. JNI rejects missing callbacks and clears exceptions without printing Java details. Static guards cover boolean JNI admission, admission before Go thread creation and timeout-aware cleanup.

- Independent reviewer checked terminal ownership, boolean JNI admission, counter balance, generation invalidation, success during draining, native rejection without Go work and test wiring; no material findings. Review did not execute tests or device flows.

- Final verification: `./gradlew --no-daemon tunnelPackageDebug` completed with `BUILD SUCCESSFUL in 2m 27s` (320 tasks: 33 executed, 287 up-to-date). All 88 JVM tests passed with zero failures/errors/skips; Go, host C++, logging on/off tests and static guards passed. Native build completed for arm64-v8a, armeabi-v7a, x86 and x86_64; final APK packaging verification passed. Build log: `/private/tmp/telegram-tunnel-recovery-build.log`.
- APK: `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`. No device smoke test, network interruption or installation was performed. The initial UDP/server-path cause remains unknown; this change corrects the verified recovery defect, not the underlying cause of lost responses.
- All implementation/automated-verification steps are complete; the explicitly separate device acceptance item remains open. No commit created; supplied `tunnel-logs` remains untouched.
