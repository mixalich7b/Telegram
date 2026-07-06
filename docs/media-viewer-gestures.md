# Media Viewer Gestures

`PhotoViewer` handles regular full-screen chat media. `SecretMediaViewer`
handles secret media and should keep the same swipe-to-dismiss feel.

Horizontal swipes switch to the previous or next item by distance or by
velocity. The velocity path uses `VelocityTracker.getXVelocity()` with the same
`dp(650)` threshold used for regular page flings.

Vertical swipe-to-dismiss starts only for a clearly vertical one-finger gesture
at scale `1`: the current guard is `dy >= dp(30)` and `dy / 2 > dx`. Once the
vertical drag is active, dismiss on either sufficient distance
(`containerHeight / 8`) or sufficient vertical velocity (`dp(650)`). In
`PhotoViewer`, upward dismisses should continue to respect the existing
swipe-to-PiP path when it is enabled.
