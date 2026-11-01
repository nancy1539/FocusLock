package com.example.focuslock;

/**
 * The ONE normalized "what is enforced right now" value consumed by BlockerService.
 *
 * Resolution order (see resolve()):
 *   1. an enforcing SCHOOL session for the logged-in student always wins;
 *   2. otherwise a running PERSONAL session;
 *   3. otherwise NONE.
 * A personal session therefore can neither replace nor outlive-override a school session,
 * and an inactive/expired personal session can never override anything: it is simply not
 * a candidate. The block decision itself is still LockPolicy.decide (same code, same
 * exemptions, same reasons) for both modes.
 *
 * Pure Java.
 */
final class EnforcementPolicy {

    enum Mode { NONE, SCHOOL, PERSONAL }

    static final EnforcementPolicy NONE =
            new EnforcementPolicy(Mode.NONE, "", "", null, 0L);

    final Mode mode;
    /** Identity of the session being enforced; changes when the policy changes. */
    final String sessionId;
    /** The subject LockPolicy checks eligibility against (studentId or owner uid). */
    final String subjectId;
    final LockSession lock;
    /** Wall-clock end as of resolution time (display + block-screen self-dismiss). */
    final long endWallMs;

    private EnforcementPolicy(Mode mode, String sessionId, String subjectId,
                              LockSession lock, long endWallMs) {
        this.mode = mode;
        this.sessionId = sessionId;
        this.subjectId = subjectId;
        this.lock = lock;
        this.endWallMs = endWallMs;
    }

    static EnforcementPolicy resolve(LockSession school, String studentId,
                                     PersonalSession personal, PersonalClock clock) {
        long now = clock.wallMs();
        if (LockPolicy.sessionState(school, studentId, now) == LockPolicy.SessionState.ENFORCING) {
            return new EnforcementPolicy(Mode.SCHOOL, "school:" + studentId + ":" + school.endTime,
                    studentId, school, school.endTime);
        }
        if (personal != null && personal.isActive(clock)) {
            LockSession ls = personal.toLockSession(clock);
            return new EnforcementPolicy(Mode.PERSONAL, "personal:" + personal.id,
                    personal.ownerUid, ls, ls.endTime);
        }
        return NONE;
    }

    boolean isEnforcing() {
        return mode != Mode.NONE;
    }

    LockPolicy.Decision decide(String pkg, String selfPackage, LockPolicy.Exemptions exemptions,
                               long nowMs) {
        if (mode == Mode.NONE) {
            return new LockPolicy.Decision(false, LockPolicy.Reason.NO_SESSION);
        }
        return LockPolicy.decide(lock, subjectId, pkg, selfPackage, exemptions, nowMs);
    }

    boolean sameAs(EnforcementPolicy other) {
        return other != null && mode == other.mode && sessionId.equals(other.sessionId);
    }

    @Override
    public String toString() {
        return "EnforcementPolicy{" + mode + (mode == Mode.NONE ? "" : ", id=" + sessionId)
                + ", packages=" + (lock == null ? 0 : lock.blockedApps.size())
                + (lock != null && lock.blockAll ? ", blockAll" : "") + "}";
    }

}
