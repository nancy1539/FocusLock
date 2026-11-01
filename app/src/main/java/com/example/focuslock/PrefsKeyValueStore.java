package com.example.focuslock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Map;

/**
 * SharedPreferences-backed KeyValueStore in its own file (never the "focus" login prefs or
 * School's "focuslock_session"). apply() uses commit(): writes are rare, must be durable
 * before a session is reported as started, and the result is needed.
 */
final class PrefsKeyValueStore implements KeyValueStore {

    static final String PREFS = "focuslock_personal";

    private final SharedPreferences prefs;

    PrefsKeyValueStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public String get(String key) {
        try {
            return prefs.getString(key, null);
        } catch (ClassCastException e) {
            return null; // a key of a different type is treated as absent
        }
    }

    @Override
    public boolean apply(Map<String, String> puts, Collection<String> removes) {
        SharedPreferences.Editor e = prefs.edit();
        for (String r : removes) {
            e.remove(r);
        }
        for (Map.Entry<String, String> p : puts.entrySet()) {
            e.putString(p.getKey(), p.getValue());
        }
        return e.commit();
    }
}
