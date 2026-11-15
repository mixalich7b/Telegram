# Tunnel connection delay diagnostics

## Intent

Explain delays between tunnel route activation, TCP connection establishment,
and Telegram's connected state using the exported tunnel logs. Existing traces
cover lifecycle operations, individual TCP dials, aggregate attempt settlement,
and `mtproto_ready`, but do not explain tgnet reconnect decisions or the period
between a successful dial and a Telegram response.

## Approaches

Recommended: extend the existing `tunnel_trace` with connection phase transitions
and reconnect decisions. This preserves the existing export workflow and keeps
the trace bounded to events rather than packets.

An alternative is continuous packet/request tracing. It produces substantially
more output, increases overhead, and risks recording unnecessary user data;
it is outside this proposal. Device packet captures remain a separate manual
investigation tool.

## Proposed scope

- Correlate connection events by account, tunnel lifecycle generation,
  connection identity and individual connection attempt. Keep connection and
  lifecycle generations distinct, including when an old attempt is cancelled.
- Record requested/effective IPv6 policy and whether application is deferred
  because a call is active. Record the active policy on native route application
  and address-family rejection, so an export can distinguish old IPv6 attempts
  from attempts made under the new policy.
- Record connection start, TCP completion, first actual socket write, first
  socket read, and MTProto readiness. Use monotonic durations to distinguish
  waiting to send, waiting for a response, and processing a response. Do not
  equate socket write completion with peer acknowledgement.
- Record timeout/disconnect reason and numeric error, connection type and DC,
  connection phase, elapsed time, and whether any data was sent/received.
- Record explicit reconnect, suspension, address/port retry decisions and
  timer scheduling/firing. Include requested and actual timer delay, network
  availability, and MTProto handshake state at relevant decision points.
- Enrich blocked-route diagnostics with account/generation context and aggregate
  repeated blocked attempts so a short startup window cannot flood the log.
  Flush/report accumulated suppression when the route changes.
- Keep lifecycle events for network refresh and restart; identify initiating
  reasons where callers can supply a fixed, non-sensitive reason label.

## Constraints

All new events use `TG_TUNNEL_LOGS_ENABLED` and the dedicated shared tunnel sink,
independent of general app logging. Do not log payloads, request contents,
authentication/session keys, profile contents, peer endpoints, exception
messages or stack traces. Avoid per-packet/per-request logs and extra network
operations. Retain fail-closed routing, reconnect policy and call/IPv6 deferral
semantics. Logging changes must not alter retry timing or route selection.

## Implementation and verification

Primary integration points are `Connection.cpp`, `ConnectionSocket.cpp`,
`ConnectionsManager.cpp`, `NetworkAddressSettings.java`, and tunnel lifecycle
callers. Keep tracing state separate from routing state. Update
`docs/tunnel-testing.md` with event meanings and diagnostic examples.

Verify diagnostic state/timing and suppression with host tests where practical,
including resets across attempts/generations and disabled logging. Run the
canonical native check `./gradlew --no-daemon tunnelPackageDebug`. A fresh
device reproduction is needed to assess the original delay; a build alone
cannot establish its cause.

## Status

Approved by the user on 2026-11-15; implementation tracked in the matching plan.
