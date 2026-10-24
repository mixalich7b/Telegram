# Gradle clean implementation plan

**Goal:** Reclaim project build space with ordinary `./gradlew clean`, including
Android outputs, generated sources, native build trees and tunnel Go caches.

**Design:** Extend Gradle's `Delete` tasks with explicit project-owned output
directories. Make read-only Go cache directories writable without following
symlinks. Keep native cleanup independent of tgcalls preparation. Move host Go
test caches under the module build directory. Gradle dependency caches, the
Android SDK/NDK, source submodules and signing configuration are build inputs.

**Implementation:** `gradle/clean.gradle` owns cleanup; root `build.gradle`
applies it; `TMessagesProj/build.gradle` owns Go test output locations.

- [x] Add fixture checks for root/module cleanup, read-only directories,
  symlinks, missing directories and combined clean/build ordering.
- [x] Configure root/module Delete tasks and move Go test caches.
- [x] Update tunnel build documentation and cleanup invariants.
- [x] Run fixture checks, a real clean, and `tunnelPackageDebug`; finish with
  another clean to leave the workspace free of generated build outputs.

Use the current workspace and leave changes uncommitted. No new reset task.
Do not remove Gradle's active project state or global dependency caches.

Validation: real project clean passed in 14 seconds. The enhanced fixture
passed root/module cleanup, read-only directory removal, external/dangling
symlinks, and CLI clean/assemble ordering with real Java buildSrc classes. A
review found no production defect; its initial ordering coverage gap was fixed.
`./gradlew --no-daemon tunnelPackageDebug` passed in 10m 28s (319 tasks executed,
all four native ABIs, 77 JVM tests without failures, Go/static/C++/APK checks).
Final `./gradlew --no-daemon clean` passed in 13 seconds and removed the measured
19.15 GiB in the main native, Android and buildSrc output directories. Gradle
may recreate a small root `build/reports/problems` report at build completion.
