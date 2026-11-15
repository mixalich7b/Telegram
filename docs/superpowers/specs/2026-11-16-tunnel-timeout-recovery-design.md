# Tunnel TCP timeout recovery design

## Problem and evidence

The supplied 2026-11-15 log excerpt shows generation 3 repeatedly timing out
before TCP establishment (8–12 seconds), with no successful TCP connection.
Native cleanup reports those attempts as cancellations. Go's 20-second dial
eventually fails, but the closed native state suppresses the failure callback.
The controller consequently never observes a failed group. At 14:04:37.979 UTC
it even reports pending=0, success_seen=false, settle_scheduled=false.
After disable/enable at 14:05:22–24 UTC, generation 5 establishes TCP and all
four accounts reach MTProto ready within approximately half a second.

This identifies a recovery defect, not the initial cause of missing responses
in the old runtime. No evidence establishes an IPv6 failure in this excerpt.

## Proposed behavior

1. Treat an unfinished tunnel TCP dial terminated by tgnet's connection timeout
   as failed. Preserve ordinary cancellation for suspension, route changes,
   shutdown and explicit closure. A socket timeout after dial completion must
   not complete the initial attempt again.
2. Give every admitted attempt exactly one connected/failed/cancelled outcome,
   tied to its original lifecycle generation. Ignore late/duplicate outcomes.
3. Preserve the existing 250 ms failure settlement, parallel-attempt success
   protection, and sequential alternate-address opportunity.
4. To prevent perpetual joining of a failing group, propose a 20-second recovery
   window starting at its first failure, independent of brief pending=0 gaps.
   Any successful admitted connection cancels that episode. If the window
   expires with failures and no success, temporarily defer new admissions while
   existing attempts finish normally. Once pending=0, settle and recover using
   the existing lifecycle queue and backoff. Never stop a runtime while an
   admitted attempt remains pending, or when the group has succeeded.
5. Deferring admission must not block the Android UI or tgnet thread: return a
   transient failure on the tunnel route, without creating a Go dial. Deferred
   attempts are not counted as admitted or failed attempts. A success during
   draining reopens admission; route disable/change invalidates episode timers.

The 20-second window is a proposed policy value, chosen to match the existing
Go dial budget, not an experimentally established optimum. It is not a promise
that recovery completes in 20 seconds: admitted attempts must drain, then the
existing settlement, backoff and runtime startup run. Timer execution may also
be delayed by Android scheduling/suspend.

## Alternatives and boundaries

Only classifying timeouts fixes the missing failure signal but cannot guarantee
progress under continuously overlapping retries. Lowering Go's timeout merely
changes which layer wins the race. Restarting immediately on any timeout breaks
parallel/alternate-address safety. Prefer classification plus bounded admission.

Do not change profile validation, MTProto authentication, routing for calls,
IPv6 policy or persistence. Keep the Go timeout at 20 seconds in this change.
Do not add direct fallback, VpnService or TUN. Preserve current fail-closed and
logging/privacy invariants. This work does not diagnose the original UDP/server
path failure; add only recovery reason/state diagnostics needed to observe it.

## Status

Implementation approved by the user on 2026-11-16; progress and verification are tracked in the matching implementation plan.
