# Upstream merge fixes

> Execution: apply the already requested review fixes inline; retain the user's pending merge and do not commit it.

**Goal:** Make the staged upstream 13.0.3 merge build reproducibly and preserve scheduled voice messages and accurate recorder settings.

**Architecture:** Rebase the existing tunnel patch on the staged upstream tgcalls revision. Defer V2 recording completion until the schedule picker confirms or cancels, with a small independently testable completion gate. Share the effective recorder setting between recording and UI.

**Spec:** The three findings in the preceding review and repository `AGENTS.md`.

## Constraints and review focus

- Preserve all tunnel routing, encrypted storage and fail-closed invariants.
- Edit patch inputs, never the generated tgcalls tree.
- Preserve the existing merge entries; stage the requested fixes without committing. Update clean submodule checkouts to the staged gitlinks.
- Cover confirmation before/after finalization, cancellation and duplicate callbacks.
- Check unset, explicit and server-controlled V2 settings in the UI and recording paths.

## Tasks

- [x] Rebase `patches/tgcalls/0001-tunnel-routing.patch`, update `upstream-revision`, synchronize both submodules and document the pin.
- [x] Add regression coverage for deferred V2 completion and integrate it with `AudioRecorderV2Controller` and `MediaController`.
- [x] Share effective V2 selection in `SharedSettings` and `AudioRecorderSettingsActivity`, including toggle behavior.
- [x] Run `./gradlew --no-daemon tunnelPackageDebug`, review the final diff, and record results.

## Progress

- Before changes: preparation on staged tgcalls fails with a revision mismatch; applying the old patch to that revision fails at `InstanceV2ReferenceImpl.cpp:108`.
- The working tree contains the user's uncommitted merge. Fixes remain uncommitted alongside it.
- Five completion regression tests pass. Removing the waiting-for-decision guard in a temporary copy makes the delayed-confirmation and post-encoding-cancellation tests fail.
- Patch routing additions/deletions are unchanged; only upstream context and line offsets changed. Both submodule checkouts match the merge's staged gitlinks.
- Independent review found no actionable regressions. Recorder/device integration remains a manual smoke check.
- `./gradlew --no-daemon tunnelPackageDebug`: BUILD SUCCESSFUL; 77 JVM tests passed, plus Go, host C++, preparation/static checks, all four native ABIs and APK packaging verification. Both submodules used their staged upstream revisions.

## Recorder device smoke checks

In scheduled messages, record a voice message and confirm or cancel the date
picker both immediately and after a delay. Confirmed messages must retain the
chosen date; cancelled recordings must not be sent.

In release builds without a saved recorder preference, the V2 switch must match
the server-controlled default, and the first toggle must invert that effective
state. Explicit preferences must override the server default in both settings
and recording.
