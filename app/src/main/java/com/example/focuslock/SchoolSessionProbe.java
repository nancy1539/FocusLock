package com.example.focuslock;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Read-only check "is a School/College lock enforced on this device right now?", based on the
 * persisted session that BlockerService keeps in SessionStore. It never writes or clears
 * School state (SessionStore.load() only discards records that were already invalid).
 */
final class SchoolSessionProbe {

    private SchoolSessionProbe() {}

    static boolean isActive(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences("focus", Context.MODE_PRIVATE);
            String code = sp.getString("schoolCode", "");
            String classId = sp.getString("classId", "");
            String sid = sp.getString("studentId", "");
            if (code.isEmpty() || classId.isEmpty() || sid.isEmpty()) {
                return false;
            }
            long now = System.currentTimeMillis();
            LockSession s = new SessionStore(context).load(code + "/" + classId + "/" + sid, now);
            return LockPolicy.sessionState(s, sid, now) == LockPolicy.SessionState.ENFORCING;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
