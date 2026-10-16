package com.tapbros.simtap;

/** Network decisions only. The service supplies elapsed time and performs returned actions. */
final class NetworkJob {
    enum Action { WAIT, CLICK, CLOSE, LEAVE }

    static final long SEEK_MS = 8000;
    static final long OBSERVE_MS = 15000;
    static final long CONNECT_MS = 10000;
    static final long RECHECK_MS = 250;
    static final long SETTLE_MS = 1500;

    private boolean active;
    private boolean wifi;
    private int target;
    private boolean clicked;
    private int seen;
    private long seenAt;
    private boolean reached;
    private long deadline;
    private long clickedAt, confirmationAt;
    private int confirmationWindow;
    private boolean confirmationMatched, confirmationClosed, confirmed;

    void start(boolean wifi, int target, long now) {
        this.wifi = wifi;
        this.target = target;
        active = true;
        clicked = false;
        seen = -2;
        reached = false;
        confirmationWindow = -1;
        confirmationMatched = confirmationClosed = confirmed = false;
        deadline = now + SEEK_MS;
    }

    boolean active() { return active; }
    boolean wifi() { return wifi; }
    long deadline() { return deadline; }

    void clear() {
        active = false;
        clicked = false;
        seen = -2;
        reached = false;
        confirmationWindow = -1;
        confirmationMatched = confirmationClosed = confirmed = false;
        deadline = 0;
    }

    /** Only the first matching mobile-off dialog, seen twice within five seconds, can be confirmed. */
    boolean confirm(long now, int window, boolean matches) {
        if (!active || !clicked || wifi || target != 0 || confirmed || confirmationClosed
                || window < 0 || now - clickedAt > 5000) return false;
        if (confirmationWindow == -1) confirmationWindow = window;
        if (window != confirmationWindow) { confirmationClosed = true; return false; }
        if (!matches) { confirmationMatched = false; return false; }
        if (!confirmationMatched) {
            confirmationMatched = true;
            confirmationAt = now;
            return false;
        }
        if (now - confirmationAt < RECHECK_MS) return false;
        confirmed = true;
        return true;
    }

    Action read(long now, boolean internetPanel, int state, int setting, boolean connected) {
        if (!active) return Action.WAIT;
        if (now >= deadline) return Action.LEAVE;
        if (!internetPanel) {
            seen = -2;
            return Action.WAIT;
        }
        if (confirmationWindow != -1) confirmationClosed = true;
        if (state != seen) { seen = state; seenAt = now; return Action.WAIT; }
        if (now - seenAt < (state < 0 ? SETTLE_MS : RECHECK_MS)) return Action.WAIT;
        if (!clicked) {
            if (state < 0) return Action.LEAVE;
            if (state != setting) return Action.WAIT;
            if (state == target) return Action.CLOSE;
            // Commit before returning CLICK so reentrant events cannot issue it twice.
            clicked = true;
            clickedAt = now;
            seen = -2;
            deadline = now + OBSERVE_MS;
            return Action.CLICK;
        }
        if (state != target || (setting >= 0 && setting != target)) return Action.WAIT;
        if (!wifi || target == 0) return Action.CLOSE;
        if (!reached) { reached = true; deadline = now + CONNECT_MS; }
        return connected ? Action.CLOSE : Action.WAIT;
    }
}
