package com.example.focuslock;

/**
 * Single source of truth for "should this package be blocked right now?".
 *
 * Every enforcement path (accessibility events, window-change rechecks, the
 * polling safety net, screen-on rechecks, retry verification) calls
 * {@link #decide}, so they cannot disagree.
 *
 * Definitions
 * -----------
 *  Valid active session : session != null, active == true, now < endTime.
 *  Teacher-stopped      : active == false (explicit stop command).
 *  Expired              : now >= endTime.
 *  Eligible student     : studentId is non-empty and listed in presentIds.
 *  Blocked app          : (blockAll and not exempt) or (listed in blockedApps
 *                         and not exempt).
 *  Allowed app          : anything else, always including FocusLock itself and
 *                         the exempt set (home, dialer, keyboard, emergency).
 *  Unavailable session  : session == null (never received, malformed, or
 *                         cleared). Treated as "not locked": the service never
 *                         keeps a student locked on the strength of missing data.
 *
 * Pure Java (no Android dependencies) so it can be unit-tested on the JVM.
 */
final class LockPolicy {

    private LockPolicy() {}

    enum SessionState {
        /** No usable session. */
        NONE,
        /** Teacher sent an explicit stop. */
        STOPPED,
        /** endTime reached. */
        EXPIRED,
        /** Session is running but this student is not in presentIds. */
        NOT_ELIGIBLE,
        /** Session is running and applies to this student. */
        ENFORCING
    }

    enum Reason {
        NO_SESSION,
        SESSION_STOPPED,
        SESSION_EXPIRED,
        STUDENT_NOT_ELIGIBLE,
        UNKNOWN_PACKAGE,
        SELF_PACKAGE,
        EXEMPT_PACKAGE,
        NOT_IN_BLOCK_LIST,
        BLOCKED_BY_LIST,
        BLOCKED_ALL
    }

    static final class Decision {
        final boolean block;
        final Reason reason;

        Decision(boolean block, Reason reason) {
            this.block = block;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return (block ? "BLOCK" : "ALLOW") + "(" + reason + ")";
        }
    }

    /** Lets the caller defer expensive lookups (default home/dialer/IME) until needed. */
    interface Exemptions {
        boolean isExempt(String pkg);
    }

    static SessionState sessionState(LockSession s, String studentId, long nowMs) {
        if (s == null) return SessionState.NONE;
        if (!s.active) return SessionState.STOPPED;
        if (s.isExpired(nowMs)) return SessionState.EXPIRED;
        if (!s.includesStudent(studentId)) return SessionState.NOT_ELIGIBLE;
        return SessionState.ENFORCING;
    }

    static Decision decide(LockSession s, String studentId, String pkg,
                           String selfPackage, Exemptions exemptions, long nowMs) {

        switch (sessionState(s, studentId, nowMs)) {
            case NONE:         return new Decision(false, Reason.NO_SESSION);
            case STOPPED:      return new Decision(false, Reason.SESSION_STOPPED);
            case EXPIRED:      return new Decision(false, Reason.SESSION_EXPIRED);
            case NOT_ELIGIBLE: return new Decision(false, Reason.STUDENT_NOT_ELIGIBLE);
            default:           break;
        }

        if (pkg == null || pkg.isEmpty()) {
            return new Decision(false, Reason.UNKNOWN_PACKAGE);
        }
        if (pkg.equals(selfPackage)) {
            return new Decision(false, Reason.SELF_PACKAGE);
        }

        boolean wouldBlock = s.blockAll || s.blockedApps.contains(pkg);
        if (!wouldBlock) {
            return new Decision(false, Reason.NOT_IN_BLOCK_LIST);
        }

        // Safety exemptions win over BOTH blockAll and an explicit blockedApps
        // entry, so a teacher can never accidentally block calls or the launcher.
        if (exemptions != null && exemptions.isExempt(pkg)) {
            return new Decision(false, Reason.EXEMPT_PACKAGE);
        }

        return new Decision(true, s.blockAll ? Reason.BLOCKED_ALL : Reason.BLOCKED_BY_LIST);
    }
}
