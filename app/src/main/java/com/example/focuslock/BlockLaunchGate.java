package com.example.focuslock;

/**
 * Decides what to do when a blocked package has been detected in the
 * foreground, so that repeated detections neither leave the app exposed nor
 * spawn an unbounded number of Activity launches.
 *
 * There is deliberately NO "ignore everything for N ms" cooldown: every
 * detection of a blocked foreground yields an action that protects the user
 * (a launch, a bounded wait for a launch that is already in flight, or Home).
 *
 * NORMAL mode
 *  - No launch in flight                       -> LAUNCH.
 *  - Launch in flight, younger than
 *    PENDING_WINDOW_MS                         -> WAIT_FOR_PENDING (caller MUST
 *                                                 schedule a re-check).
 *  - Launch older than the window and the
 *    blocked app is still in front             -> that launch failed; LAUNCH again.
 *  - MAX_ATTEMPTS launches failed in a row     -> enter DEGRADED mode, GO_HOME.
 *
 * DEGRADED mode (the block screen demonstrably does not take over)
 *  - The block screen is NOT launched on every detection any more; launching is
 *    pointless while it fails and would only hammer the system. Instead:
 *  - Every detection of a blocked foreground   -> GO_HOME immediately
 *    (performGlobalAction does not depend on starting an Activity from the
 *    background). Nothing is ever skipped or delayed here: the protective
 *    action is NOT subject to back-off.
 *  - Exponential back-off (PROBE_BACKOFF_MIN_MS doubling to PROBE_BACKOFF_MAX_MS)
 *    applies ONLY to the optional launch probes: when a probe is due the
 *    detection yields PROBE (one launch attempt) instead of GO_HOME. A probe
 *    that has not produced a confirmed block screen after PENDING_WINDOW_MS is
 *    a failed probe: the next probe is scheduled further out. The blocked app
 *    can be visible for at most one launch window per probe, exactly as for a
 *    normal launch.
 *
 * Leaving recovery
 *  - onProtectionConfirmed(): the block screen is resumed AND is the foreground
 *    window. This is the only signal that proves launching works again.
 *  - reset(): the session ended / changed / the identity changed.
 *  - An ALLOWED foreground (home screen, another app, FocusLock's own other
 *    screens) deliberately changes nothing: after GO_HOME the launcher is in
 *    front, and treating that as "recovered" would restart a fresh run of
 *    launches every time the student reopens the blocked app.
 *
 * Time is injected (monotonic ms) so the class is deterministic in tests.
 * Pure Java; not thread-safe (used from the main thread only).
 */
final class BlockLaunchGate {

    enum Action {
        /** Start the block screen (normal mode). */
        LAUNCH,
        /** A launch is already in flight; re-check after remainingPendingMs(). */
        WAIT_FOR_PENDING,
        /** Degraded mode, back-off elapsed: one launch attempt to test recovery. */
        PROBE,
        /** performGlobalAction(GLOBAL_ACTION_HOME). */
        GO_HOME
    }

    static final long PENDING_WINDOW_MS = 350L;
    static final int MAX_ATTEMPTS = 3;
    static final long PROBE_BACKOFF_MIN_MS = 2_000L;
    static final long PROBE_BACKOFF_MAX_MS = 60_000L;

    private static final long NONE = Long.MIN_VALUE;

    private long pendingSinceMs = NONE;
    private boolean pendingIsProbe = false;
    private int attempts = 0;

    private boolean degraded = false;
    private long probeBackoffMs = PROBE_BACKOFF_MIN_MS;
    private long nextProbeAtMs = NONE;
    private int probeFailures = 0;
    private int homeActions = 0;
    private int totalLaunches = 0;

    Action onBlockedForeground(long nowMs) {
        if (pendingSinceMs != NONE && nowMs - pendingSinceMs < PENDING_WINDOW_MS) {
            return Action.WAIT_FOR_PENDING;
        }

        if (pendingSinceMs != NONE) {
            // The in-flight launch is older than the window and a blocked app is
            // in front again: it did not take over.
            if (pendingIsProbe) {
                registerProbeFailure(nowMs);
            }
            pendingSinceMs = NONE;
            pendingIsProbe = false;
        }

        if (!degraded) {
            if (attempts < MAX_ATTEMPTS) {
                attempts++;
                totalLaunches++;
                pendingSinceMs = nowMs;
                pendingIsProbe = false;
                return Action.LAUNCH;
            }
            degraded = true;
            probeBackoffMs = PROBE_BACKOFF_MIN_MS;
            nextProbeAtMs = nowMs + probeBackoffMs;
            homeActions++;
            return Action.GO_HOME;
        }

        if (nowMs >= nextProbeAtMs) {
            totalLaunches++;
            pendingSinceMs = nowMs;
            pendingIsProbe = true;
            return Action.PROBE;
        }

        homeActions++;
        return Action.GO_HOME;
    }

    private void registerProbeFailure(long nowMs) {
        probeFailures++;
        probeBackoffMs = Math.min(probeBackoffMs * 2L, PROBE_BACKOFF_MAX_MS);
        nextProbeAtMs = nowMs + probeBackoffMs;
    }

    /** Milliseconds until the in-flight launch should be considered failed (0 if none). */
    long remainingPendingMs(long nowMs) {
        if (pendingSinceMs == NONE) return 0L;
        long remaining = PENDING_WINDOW_MS - (nowMs - pendingSinceMs);
        return Math.max(0L, remaining);
    }

    boolean isLaunchPending(long nowMs) {
        return pendingSinceMs != NONE && nowMs - pendingSinceMs < PENDING_WINDOW_MS;
    }

    /** Milliseconds until the next probe is allowed; 0 when not degraded or already due. */
    long untilNextProbeMs(long nowMs) {
        if (!degraded || nextProbeAtMs == NONE) return 0L;
        return Math.max(0L, nextProbeAtMs - nowMs);
    }

    int attempts() {
        return attempts;
    }

    boolean isDegraded() {
        return degraded;
    }

    int probeFailures() {
        return probeFailures;
    }

    int homeActions() {
        return homeActions;
    }

    int totalLaunches() {
        return totalLaunches;
    }

    /**
     * startActivity threw: nothing is in flight. A normal attempt still counts
     * (it was counted when issued); a failed probe pushes the next probe out.
     */
    void onLaunchFailed(long nowMs) {
        if (pendingSinceMs != NONE && pendingIsProbe) {
            registerProbeFailure(nowMs);
        }
        pendingSinceMs = NONE;
        pendingIsProbe = false;
    }

    /**
     * The block screen is resumed and is the foreground window: launching works.
     * Clears all recovery state.
     */
    void onProtectionConfirmed() {
        reset();
    }

    /** Session ended or changed, identity changed, or enforcement stopped applying. */
    void reset() {
        pendingSinceMs = NONE;
        pendingIsProbe = false;
        attempts = 0;
        degraded = false;
        probeBackoffMs = PROBE_BACKOFF_MIN_MS;
        nextProbeAtMs = NONE;
        probeFailures = 0;
        homeActions = 0;
        totalLaunches = 0;
    }

    /** One-line state for Logcat. */
    String describe(long nowMs) {
        return "gate{mode=" + (degraded ? "DEGRADED" : "NORMAL")
                + " attempts=" + attempts + "/" + MAX_ATTEMPTS
                + " launches=" + totalLaunches
                + " homes=" + homeActions
                + " probeFailures=" + probeFailures
                + (degraded ? " backoff=" + probeBackoffMs + "ms nextProbeIn="
                + untilNextProbeMs(nowMs) + "ms" : "")
                + "}";
    }
}
