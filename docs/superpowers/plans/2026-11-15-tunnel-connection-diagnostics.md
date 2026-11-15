# Tunnel connection diagnostics implementation plan

> Execute inline using superpowers:executing-plans. User approved the design and implementation on 2026-11-15.

**Goal:** Explain TCP-to-MTProto delays and route/retry transitions in exported tunnel logs.
**Architecture:** Add a host-testable diagnostic timeline and blocked-attempt aggregation, integrate on the existing tgnet queue, and add fixed Java policy/lifecycle reason events.
**Tech stack:** C++17, Java, existing dedicated tunnel logger.
**Spec:** `docs/superpowers/specs/2026-11-15-tunnel-connection-diagnostics-design.md`

## Constraints and review focus

Keep routing and retry semantics unchanged. Use only TG_TUNNEL_LOGS_ENABLED and the existing sink. Never record secrets/payloads. Preserve old attempt generations after route changes; reset phase timestamps per attempt; ignore empty/failed I/O; suppress bursts per account and flush summaries on route changes; logging off must evaluate no diagnostic arguments.

## Tasks

- [x] Add host tests for diagnostic first-I/O timing, attempt reset, terminal events and blocked burst aggregation, including logger off/general logging combinations. Observed missing-helper compile failure before implementation; host binaries then passed with flag on/off and both general logging states.
- [x] Integrate helper with socket start/connect/read/write/close/timeout and tgnet retry/MTProto readiness. Distinguish local socketpair I/O from completed Go tunnel writes/reads.
- [x] Add IPv6 requested/effective/deferred events and fixed lifecycle initiator labels. Update docs and invariant coverage.
- [x] Run `./gradlew --no-daemon tunnelPackageDebug`, review diff for sensitive data and routing changes, and report verification limits.

## Execution record

- Existing branch: feat/wg-embed. Continue in the user's workspace; only pre-existing untracked items are the approved design and supplied tunnel-logs. No commits or integration requested.
- First canonical run: JVM, Go, host C++, logger on/off and static guards passed. Native compilation caught ambiguous inherited `instanceNum` references in new diagnostics; qualified them as `ConnectionSocket::instanceNum`.
- Independent read-only review found a previously uninitialized `failedConnectionCount` that new startup diagnostics could read before the first packet. Initialized it to zero; this gives the retry counter its intended initial value. No other defects reported. Review did not exercise device/network/call flows.
- Final canonical verification: `BUILD SUCCESSFUL in 1m 33s`; all 80 JVM tests passed (zero failures/errors/skips), Go and host C++ checks passed, static guards passed, native APK built for all four configured ABIs and packaging verification passed. Logger tests include tunnel flag on/off, both general-log states, and exported diagnostics. Existing upstream/compiler/Gradle warnings remain.
- Device smoke testing was not performed. The change adds diagnostics; identifying the original connection delay still requires a fresh exported reproduction from this APK.
