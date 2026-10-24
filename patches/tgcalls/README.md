# Tunnel routing patches for tgcalls

Upstream is the unchanged `TMessagesProj/jni/tgcalls` submodule. Its expected
commit is recorded in `upstream-revision`; `series` lists patches in application
order. The patches preserve private legacy, V2 custom/reference, native/WASM pump,
and group/live routing, including SOCKS5 CONNECT and tunnel DNS/TCP/UDP sockets.

The current patch base is `03d7b8662512af7528f476c02094862c1a37177d` (Telegram
13.0.3 merge). It retains upstream's V2 reference negotiation and DTLS-role fixes
alongside the tunnel socket, DNS, P2P and ordinary-proxy routing changes.

`Tools/prepare-tgcalls.py` exports committed upstream objects and applies the
series into `TMessagesProj/build/generated/tunnel-tgcalls`. It never modifies the
submodule or imports uncommitted upstream edits. Gradle's `prepareTunnelTgcalls`
and CMake use this same directory. Static guards inspect these prepared sources;
CMake also checks that its actual target includes the routed implementations.

Preparation requires Python 3 and Git on macOS/Linux. A file lock serializes
preparation across ABI configurations. Input fingerprints and output file hashes
avoid rewriting a valid tree, and repair missing/modified generated sources.
A revision mismatch or an unapplicable patch stops the build even if an older
prepared tree exists. Do not edit generated sources as a way to ship changes.

To update upstream:

1. Review the new upstream commit against the previous pin and the patch series.
2. Port the patch onto a temporary clean export of the new upstream; preserve all
   routing invariants in `AGENTS.md`, including both pump policy call sites.
3. Update the submodule gitlink, `upstream-revision` and patches together.
4. Run `./gradlew --no-daemon tunnelPackageDebug`. It includes preparation tests,
   routing guards, JVM/Go/host C++ tests, native compilation and APK verification.
5. Commit the gitlink and patch inputs, never the generated tree or a dirty
   submodule checkout. Validate real-server calls using `docs/tunnel-testing.md`.
