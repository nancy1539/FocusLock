package com.example.focuslock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Persists the LAST VALID, ACTIVE lock session so enforcement can resume
 * immediately after the accessibility service or the whole process is
 * recreated, before (or without) the first Firestore snapshot.
 *
 * Rules
 *  - Only active, unexpired sessions are ever written.
 *  - The record is tagged with the owner key (school/class/student); a record
 *    for a different owner is ignored and removed.
 *  - The record carries a schema version; unknown versions are discarded.
 *  - load() re-validates endTime, so an expired record can never be enforced, and
 *    applies the same semantic rules as LockSession.parse()
 *    (LockSession.activeSessionProblem): non-empty presentIds; non-empty
 *    blockedApps unless blockAll.
 *  - Stored in its own preferences file; the existing "focus" login prefs are
 *    never touched.
 *
 * commit() (synchronous) is used on purpose: writes are rare (only when the
 * session changes) and apply() could lose the write if the process is killed
 * right afterwards.
 */
final class SessionStore {

    private static final String TAG = "FocusLockDebug";

    static final String PREFS = "focuslock_session";
    static final int SCHEMA_VERSION = 1;

    private static final String K_VERSION = "v";
    private static final String K_OWNER = "owner";
    private static final String K_END = "end";
    private static final String K_ALL = "all";
    private static final String K_APPS = "apps";
    private static final String K_PRESENT = "present";
    private static final String K_DATE = "date";

    private final SharedPreferences prefs;

    SessionStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void save(String ownerKey, LockSession s) {
        if (s == null || !s.active) {
            clear("not an active session");
            return;
        }
        boolean ok = prefs.edit()
                .putInt(K_VERSION, SCHEMA_VERSION)
                .putString(K_OWNER, ownerKey)
                .putLong(K_END, s.endTime)
                .putBoolean(K_ALL, s.blockAll)
                .putStringSet(K_APPS, new HashSet<>(s.blockedApps))
                .putStringSet(K_PRESENT, new HashSet<>(s.presentIds))
                .putString(K_DATE, s.date)
                .commit();
        if (!ok) {
            Log.w(TAG, "SessionStore: commit failed (state kept in memory only)");
        }
    }

    /**
     * @return the persisted session if it belongs to ownerKey, has a known
     *         schema version and has not expired; otherwise null (and the
     *         stale record is removed).
     */
    LockSession load(String ownerKey, long nowMs) {
        if (!prefs.contains(K_VERSION)) {
            return null;
        }
        int version = prefs.getInt(K_VERSION, -1);
        if (version != SCHEMA_VERSION) {
            clear("unknown schema version " + version);
            return null;
        }
        String owner = prefs.getString(K_OWNER, "");
        if (ownerKey == null || !ownerKey.equals(owner)) {
            clear("owner mismatch");
            return null;
        }
        long end = prefs.getLong(K_END, 0L);
        if (end <= 0L || nowMs >= end) {
            clear("persisted session expired or invalid");
            return null;
        }
        Set<String> apps = prefs.getStringSet(K_APPS, null);
        Set<String> present = prefs.getStringSet(K_PRESENT, null);
        if (apps == null || present == null) {
            // save() always writes both sets (possibly empty). A missing one means the
            // record is damaged; do not invent an empty eligibility/block set from it.
            clear("persisted session lacks " + (present == null ? "presentIds" : "blockedApps"));
            return null;
        }

        boolean all = prefs.getBoolean(K_ALL, false);
        String problem = LockSession.activeSessionProblem(all, apps, present);
        if (problem != null) {
            // Same rules as LockSession.parse(): e.g. a record written by an older build
            // with empty presentIds, or a list lock without apps, is not restored.
            clear("persisted session rejected: " + problem);
            return null;
        }

        return new LockSession(true, end, all, apps, present, prefs.getString(K_DATE, ""));
    }

    void clear(String why) {
        if (prefs.contains(K_VERSION) || !prefs.getAll().isEmpty()) {
            prefs.edit().clear().commit();
            Log.d(TAG, "SessionStore: cleared (" + why + ")");
        }
    }
}
