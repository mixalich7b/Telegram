package org.telegram.messenger;

/** Desired versus effective policy; callers serialize transitions with session creation. */
final class IpVersionPolicy {
    private boolean requested;
    private boolean enabled;
    private int sessions;

    IpVersionPolicy(boolean enabled) {
        this.requested = this.enabled = enabled;
    }

    boolean isEnabled() { return enabled; }
    boolean isRequested() { return requested; }
    void beginSession() { sessions++; }

    boolean request(boolean value) {
        requested = value;
        return applyIfIdle();
    }

    boolean endSession() {
        if (sessions == 0) throw new IllegalStateException("No active VoIP session");
        sessions--;
        return applyIfIdle();
    }

    private boolean applyIfIdle() {
        if (sessions != 0 || enabled == requested) return false;
        enabled = requested;
        return true;
    }
}
