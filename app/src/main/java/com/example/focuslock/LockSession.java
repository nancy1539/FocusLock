package com.example.focuslock;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of the teacher's lock session as stored in
 * schools/{code}/classes/{classId}/session/current.
 *
 * Firestore schema is unchanged: active, endTime, blockAll, blockedApps,
 * presentIds, date.
 *
 * Pure Java (no Android dependencies) so it can be unit-tested on the JVM.
 */
final class LockSession {

    final boolean active;
    final long endTime;
    final boolean blockAll;
    final Set<String> blockedApps;
    final Set<String> presentIds;
    final String date;

    LockSession(boolean active, long endTime, boolean blockAll,
                Set<String> blockedApps, Set<String> presentIds, String date) {
        this.active = active;
        this.endTime = endTime;
        this.blockAll = blockAll;
        this.blockedApps = Collections.unmodifiableSet(new HashSet<>(blockedApps));
        this.presentIds = Collections.unmodifiableSet(new HashSet<>(presentIds));
        this.date = date == null ? "" : date;
    }

    /** True once the wall-clock time has reached endTime. */
    boolean isExpired(long nowMs) {
        return nowMs >= endTime;
    }

    boolean includesStudent(String studentId) {
        return studentId != null && !studentId.isEmpty() && presentIds.contains(studentId);
    }

    /** Result of parsing a Firestore document. Exactly one of session / error is non-null. */
    static final class Parsed {
        final LockSession session;
        final String error;

        private Parsed(LockSession session, String error) {
            this.session = session;
            this.error = error;
        }
    }

    /**
     * Parses the raw document map.
     *
     * Rules:
     *  - "active" must be a Boolean, otherwise the document is MALFORMED.
     *  - active == false is a valid explicit stop; the other fields are irrelevant.
     *  - active == true requires a numeric endTime > 0, otherwise MALFORMED
     *    (a lock without a valid end time is never enforced, so it can never
     *    keep a student locked indefinitely).
     *  - active == true ALSO requires "blockedApps" and "presentIds" to be
     *    present and to be lists of non-blank strings. ClassActivity.startLock
     *    always writes both (blockedApps may legitimately be an EMPTY list when
     *    "Block ALL" is chosen; presentIds always holds at least one student
     *    because the teacher UI refuses to start a lock otherwise). A MISSING
     *    field is therefore corruption or a partial write, not "nobody
     *    eligible" and not "nothing blocked": interpreting it as an empty set
     *    would silently disable enforcement (empty presentIds) or silently turn
     *    a list lock into a lock that blocks nothing (empty blockedApps).
     *    Such documents are rejected with a reason; the caller logs it and keeps
     *    its last valid state.
     *  - Semantic checks on an active session (also rejected with a reason):
     *      * presentIds must contain at least one student: an active session must
     *        target someone (startLock refuses to start otherwise), so an empty
     *        list is a damaged document, not "nobody locked".
     *      * blockedApps must be non-empty when blockAll is false (or absent):
     *        a list lock that lists nothing would silently block nothing.
     *      * blockedApps MAY be empty when blockAll is true (startLock writes the
     *        checkbox selection, which can be empty, next to blockAll == true).
     *  - blockAll is written by startLock too, but a record without it is read
     *    as a list-mode lock (false), the only meaning that cannot over-block.
     *    A present blockAll of the wrong type is MALFORMED.
     */
    static Parsed parse(Map<String, Object> data) {
        if (data == null) {
            return new Parsed(null, "document has no data");
        }

        Object activeObj = data.get("active");
        if (!(activeObj instanceof Boolean)) {
            return new Parsed(null, "field 'active' missing or not a boolean");
        }
        boolean active = (Boolean) activeObj;

        Object endObj = data.get("endTime");
        long end = 0L;
        if (endObj instanceof Number) {
            end = ((Number) endObj).longValue();
        } else if (endObj != null) {
            return new Parsed(null, "field 'endTime' is not a number");
        }

        if (!active) {
            // Explicit teacher stop. Only the flag matters.
            return new Parsed(new LockSession(false, end, false,
                    Collections.<String>emptySet(), Collections.<String>emptySet(),
                    stringOrEmpty(data.get("date"))), null);
        }

        if (end <= 0L) {
            return new Parsed(null, "active session has no valid endTime");
        }

        Object blockAllObj = data.get("blockAll");
        boolean blockAll = false;
        if (blockAllObj instanceof Boolean) {
            blockAll = (Boolean) blockAllObj;
        } else if (blockAllObj != null) {
            return new Parsed(null, "field 'blockAll' is not a boolean");
        }

        Set<String> apps = new HashSet<>();
        String err = readMandatoryStringList(data, "blockedApps", apps);
        if (err != null) {
            return new Parsed(null, err);
        }

        Set<String> present = new HashSet<>();
        err = readMandatoryStringList(data, "presentIds", present);
        if (err != null) {
            return new Parsed(null, err);
        }

        String problem = activeSessionProblem(blockAll, apps, present);
        if (problem != null) {
            return new Parsed(null, problem);
        }

        return new Parsed(new LockSession(true, end, blockAll, apps, present,
                stringOrEmpty(data.get("date"))), null);
    }

    /**
     * The semantic rules for an ACTIVE session, shared by {@link #parse} and
     * SessionStore.load() so a persisted copy can never be accepted under weaker
     * rules than a Firestore document.
     *
     * @return null if usable, otherwise a complete, log-ready reason
     */
    static String activeSessionProblem(boolean blockAll, Set<String> blockedApps,
                                       Set<String> presentIds) {
        if (presentIds.isEmpty()) {
            return "active session has an empty 'presentIds' list"
                    + " (an active session must target at least one student)";
        }
        if (!blockAll && blockedApps.isEmpty()) {
            return "active session has blockAll=false and an empty 'blockedApps' list"
                    + " (it would block nothing)";
        }
        return null;
    }

    /**
     * Reads a mandatory list of non-blank strings.
     *
     * @return null on success, otherwise a complete, log-ready problem description
     */
    private static String readMandatoryStringList(Map<String, Object> data, String field,
                                                  Set<String> out) {
        Object value = data.get(field);
        if (value == null) {
            return "mandatory field '" + field + "' is "
                    + (data.containsKey(field) ? "null" : "missing")
                    + " in an active session (written by startLock; not treated as an empty set)";
        }
        if (!(value instanceof List<?>)) {
            return "field '" + field + "' is not a list (found "
                    + value.getClass().getSimpleName() + ")";
        }
        int index = 0;
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) {
                return "field '" + field + "' element #" + index + " is "
                        + (item == null ? "null" : "not a string ("
                        + item.getClass().getSimpleName() + ")");
            }
            String s = ((String) item).trim();
            if (s.isEmpty()) {
                return "field '" + field + "' element #" + index + " is blank";
            }
            out.add(s);
            index++;
        }
        return null;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LockSession)) return false;
        LockSession s = (LockSession) o;
        return active == s.active && endTime == s.endTime && blockAll == s.blockAll
                && blockedApps.equals(s.blockedApps) && presentIds.equals(s.presentIds)
                && date.equals(s.date);
    }

    @Override
    public int hashCode() {
        int h = Boolean.hashCode(active);
        h = 31 * h + Long.hashCode(endTime);
        h = 31 * h + Boolean.hashCode(blockAll);
        h = 31 * h + blockedApps.hashCode();
        h = 31 * h + presentIds.hashCode();
        h = 31 * h + date.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "LockSession{active=" + active + ", endTime=" + endTime
                + ", blockAll=" + blockAll + ", blockedApps=" + blockedApps
                + ", presentCount=" + presentIds.size() + ", date=" + date + "}";
    }
}
