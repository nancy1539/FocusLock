package com.example.focuslock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistence rules for the Personal User feature (separate from School's SessionStore, so the
 * two modes can never overwrite each other's records).
 *
 *  active          the single running personal session on this device (encoded record)
 *  history         finished sessions, newest first, capped at MAX_HISTORY
 *  sel:&lt;uid&gt;       the selected packages of ONE account; other accounts never read it
 *
 * Starting and finishing a session are single atomic writes. Not thread-safe by itself:
 * PersonalSessionController serialises access.
 */
final class PersonalSessionRepository {

    static final int MAX_HISTORY = 20;
    private static final String K_ACTIVE = "active";
    private static final String K_HISTORY = "history";
    private static final String P_SELECTION = "sel:";

    private final KeyValueStore kv;

    PersonalSessionRepository(KeyValueStore kv) {
        this.kv = kv;
    }

    // -------------------------------------------------------------- selection

    Set<String> loadSelection(String uid) {
        if (uid == null || uid.isEmpty()) {
            return Collections.emptySet();
        }
        String raw = kv.get(P_SELECTION + uid);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = PersonalRules.sanitizePackages(java.util.Arrays.asList(raw.split(",", -1)));
        return out;
    }

    boolean saveSelection(String uid, Collection<String> packages) {
        if (uid == null || uid.isEmpty()) {
            return false;
        }
        Set<String> clean = PersonalRules.sanitizePackages(packages);
        if (clean.size() > PersonalRules.MAX_APPS) {
            return false;
        }
        Map<String, String> puts = new HashMap<>();
        List<String> removes = new ArrayList<>();
        if (clean.isEmpty()) {
            removes.add(P_SELECTION + uid);
        } else {
            puts.put(P_SELECTION + uid, join(clean));
        }
        return kv.apply(puts, removes);
    }

    // ----------------------------------------------------------------- active

    /** The stored active record, or null. An undecodable record is removed, never trusted. */
    PersonalSession loadActive() {
        String raw = kv.get(K_ACTIVE);
        if (raw == null) {
            return null;
        }
        PersonalSession s = PersonalSession.decode(raw);
        if (s == null || s.status != PersonalSession.Status.ACTIVE) {
            kv.apply(Collections.<String, String>emptyMap(), Collections.singletonList(K_ACTIVE));
            return null;
        }
        return s;
    }

    boolean commitStart(PersonalSession s) {
        return kv.apply(Collections.singletonMap(K_ACTIVE, s.encode()), Collections.<String>emptyList());
    }

    /** Atomically removes the active record and prepends the finished one to the history. */
    boolean commitFinish(PersonalSession finished) {
        List<String> lines = new ArrayList<>();
        lines.add(finished.encode());
        for (PersonalSession h : loadHistoryAll()) {
            if (lines.size() >= MAX_HISTORY) {
                break;
            }
            if (!h.id.equals(finished.id)) {
                lines.add(h.encode());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(l);
        }
        return kv.apply(Collections.singletonMap(K_HISTORY, sb.toString()),
                Collections.singletonList(K_ACTIVE));
    }

    // ---------------------------------------------------------------- history

    private List<PersonalSession> loadHistoryAll() {
        List<PersonalSession> out = new ArrayList<>();
        String raw = kv.get(K_HISTORY);
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n", -1)) {
            PersonalSession s = PersonalSession.decode(line);
            if (s != null && s.status != PersonalSession.Status.ACTIVE) {
                out.add(s);
            }
        }
        return out;
    }

    /** Finished sessions of this account only, newest first. */
    List<PersonalSession> history(String uid) {
        List<PersonalSession> out = new ArrayList<>();
        for (PersonalSession s : loadHistoryAll()) {
            if (s.isOwnedBy(uid)) {
                out.add(s);
            }
        }
        return out;
    }

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String p : set) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
