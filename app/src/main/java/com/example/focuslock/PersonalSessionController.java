package com.example.focuslock;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single entry point for starting, ending and reading the personal session.
 *
 * All operations run under ONE process-wide lock, so rapid taps, the dashboard and the
 * accessibility service (same process) can never interleave a start/cancel/expire.
 *
 *  - start() is idempotent: while a session is running, further starts change nothing.
 *  - cancel() only ever touches the personal record; School state lives elsewhere.
 *  - Expiry is applied lazily by current(): the first reader after the end time finalises the
 *    record (status COMPLETED, history entry, active record removed). Correctness never
 *    depends on a timer firing.
 *
 * Pure Java; Android specifics are injected (PersonalClock, PersonalEnvironment, storage).
 */
final class PersonalSessionController {

    private static final Object LOCK = new Object();

    enum StartResult {
        STARTED, NOT_SIGNED_IN, NO_APPS, TOO_MANY_APPS, BAD_DURATION,
        ALREADY_ACTIVE, SCHOOL_ACTIVE, ACCESSIBILITY_OFF, STORAGE_FAILED
    }

    enum CancelResult { CANCELLED, NOT_ACTIVE, NOT_OWNER, STALE_SESSION, NOT_ALLOWED, STORAGE_FAILED }

    static final class StartOutcome {
        final StartResult result;
        /** The new session (STARTED) or the one already running (ALREADY_ACTIVE); else null. */
        final PersonalSession session;

        StartOutcome(StartResult result, PersonalSession session) {
            this.result = result;
            this.session = session;
        }
    }

    /** What the surrounding app knows that this class must not guess. */
    interface Environment {
        /** The accessibility service is enabled AND connected (enforcement can actually run). */
        boolean accessibilityReady();

        /** A School/College session is currently enforced on this device. */
        boolean schoolSessionActive();
    }

    /** Hook for a future strict-focus mode: return false to refuse early termination. */
    interface CancelPolicy {
        boolean allowEarlyCancel(PersonalSession session);
    }

    static final CancelPolicy ALWAYS_ALLOW_CANCEL = s -> true;

    private final PersonalSessionRepository repo;
    private final PersonalClock clock;
    private final Environment env;
    private final CancelPolicy cancelPolicy;
    private final Supplier<String> ids;

    PersonalSessionController(PersonalSessionRepository repo, PersonalClock clock,
                              Environment env, CancelPolicy cancelPolicy, Supplier<String> ids) {
        this.repo = repo;
        this.clock = clock;
        this.env = env;
        this.cancelPolicy = cancelPolicy == null ? ALWAYS_ALLOW_CANCEL : cancelPolicy;
        this.ids = ids == null ? () -> UUID.randomUUID().toString() : ids;
    }

    PersonalClock clock() {
        return clock;
    }

    // ------------------------------------------------------------------ reads

    /** The running personal session of ANY account on this device, or null. Applies expiry. */
    PersonalSession current() {
        synchronized (LOCK) {
            return currentLocked();
        }
    }

    /**
     * Milliseconds until the running session ends, or -1 when none is running.
     * Does not finalise anything (cheap; used to schedule a wake-up).
     */
    long peekRemainingMs() {
        synchronized (LOCK) {
            PersonalSession s = repo.loadActive();
            return s == null ? -1L : s.remainingMs(clock);
        }
    }

    List<PersonalSession> history(String uid) {
        synchronized (LOCK) {
            currentLocked(); // make sure an expired session is already in the history
            return repo.history(uid);
        }
    }

    PersonalSession lastSession(String uid) {
        List<PersonalSession> h = history(uid);
        return h.isEmpty() ? null : h.get(0);
    }

    Set<String> loadSelection(String uid) {
        synchronized (LOCK) {
            return repo.loadSelection(uid);
        }
    }

    boolean saveSelection(String uid, Set<String> packages) {
        synchronized (LOCK) {
            return repo.saveSelection(uid, packages);
        }
    }

    // ------------------------------------------------------------------ start

    StartOutcome start(String uid, Set<String> selectedPackages, long durationMs) {
        synchronized (LOCK) {
            if (uid == null || uid.isEmpty()) {
                return new StartOutcome(StartResult.NOT_SIGNED_IN, null);
            }
            Set<String> pkgs = PersonalRules.sanitizePackages(selectedPackages);
            if (pkgs.isEmpty()) {
                return new StartOutcome(StartResult.NO_APPS, null);
            }
            if (pkgs.size() > PersonalRules.MAX_APPS) {
                return new StartOutcome(StartResult.TOO_MANY_APPS, null);
            }
            if (!PersonalRules.isValidDurationMs(durationMs)) {
                return new StartOutcome(StartResult.BAD_DURATION, null);
            }
            PersonalSession running = currentLocked();
            if (running != null) {
                // Idempotent: a double tap or a second start never replaces or extends it.
                return new StartOutcome(StartResult.ALREADY_ACTIVE, running);
            }
            if (env.schoolSessionActive()) {
                // A personal session never replaces or competes with a school session.
                return new StartOutcome(StartResult.SCHOOL_ACTIVE, null);
            }
            if (!env.accessibilityReady()) {
                return new StartOutcome(StartResult.ACCESSIBILITY_OFF, null);
            }
            PersonalSession s = PersonalSession.startNew(ids.get(), uid, pkgs, durationMs, clock);
            if (!repo.commitStart(s)) {
                return new StartOutcome(StartResult.STORAGE_FAILED, null);
            }
            return new StartOutcome(StartResult.STARTED, s);
        }
    }

    // ----------------------------------------------------------------- cancel

    CancelResult cancel(String uid, String sessionId) {
        synchronized (LOCK) {
            PersonalSession s = currentLocked();
            if (s == null) {
                return CancelResult.NOT_ACTIVE;
            }
            if (!s.isOwnedBy(uid)) {
                return CancelResult.NOT_OWNER;
            }
            if (sessionId == null || !sessionId.equals(s.id)) {
                return CancelResult.STALE_SESSION;
            }
            if (!cancelPolicy.allowEarlyCancel(s)) {
                return CancelResult.NOT_ALLOWED;
            }
            PersonalSession done = s.finished(PersonalSession.Status.CANCELLED,
                    clock.wallMs(), "cancelled");
            return repo.commitFinish(done) ? CancelResult.CANCELLED : CancelResult.STORAGE_FAILED;
        }
    }

    // --------------------------------------------------------------- internal

    private PersonalSession currentLocked() {
        PersonalSession s = repo.loadActive();
        if (s == null) {
            return null;
        }
        if (s.remainingMs(clock) > 0L) {
            return s;
        }
        // Expired: finalise. If the write fails the session is still reported as over, and the
        // next call retries; enforcement never depends on this write succeeding.
        long endedAt = Math.min(clock.wallMs(), Math.max(s.scheduledEndWallMs(), s.startWallMs + 1L));
        repo.commitFinish(s.finished(PersonalSession.Status.COMPLETED, endedAt, "expired"));
        return null;
    }
}
