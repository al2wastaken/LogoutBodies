package io.github.logoutbodies.core;

/** Single-thread-owned state machine; persistence must succeed before commit methods are called. */
public final class Lifecycle {
    private SessionState state = SessionState.PREPARING;

    public SessionState state() {
        return state;
    }

    public void activate() {
        require(SessionState.PREPARING);
        state = SessionState.ACTIVE;
    }

    public boolean beginRemoval() {
        if (state != SessionState.ACTIVE
                && state != SessionState.PREPARING
                && state != SessionState.DYING) return false;
        state = SessionState.SETTLING;
        return true;
    }

    public boolean beginDeath() {
        if (state != SessionState.ACTIVE) return false;
        state = SessionState.DYING;
        return true;
    }

    public void revive() {
        require(SessionState.DYING);
        state = SessionState.ACTIVE;
    }

    public void settled() {
        require(SessionState.SETTLING);
        state = SessionState.AWAITING_OWNER;
    }

    public void complete() {
        require(SessionState.AWAITING_OWNER);
        state = SessionState.COMPLETE;
    }

    private void require(SessionState expected) {
        if (state != expected)
            throw new IllegalStateException("Expected " + expected + ", got " + state);
    }
}
