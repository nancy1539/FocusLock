package com.example.focuslock;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Pure validation helpers and limits for personal focus sessions. */
final class PersonalRules {

    private PersonalRules() {}

    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 240;
    /** Upper bound for apps in one session / saved selection (keeps the picker and policy small). */
    static final int MAX_APPS = 50;
    static final int[] PRESET_MINUTES = {5, 15, 25, 30, 45, 60};

    /** Android package names have at least two dot-separated segments. */
    private static final Pattern PACKAGE =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");

    static boolean isValidPackage(String pkg) {
        return pkg != null && pkg.length() <= 255 && PACKAGE.matcher(pkg).matches();
    }

    /** Keeps only syntactically valid package names, sorted, never null. */
    static Set<String> sanitizePackages(Collection<String> in) {
        if (in == null) {
            return Collections.emptySet();
        }
        Set<String> out = new TreeSet<>();
        for (String p : in) {
            if (isValidPackage(p)) {
                out.add(p);
            }
        }
        return out;
    }

    static boolean isValidDurationMinutes(long minutes) {
        return minutes >= MIN_MINUTES && minutes <= MAX_MINUTES;
    }

    static long minutesToMs(long minutes) {
        return minutes * 60_000L;
    }

    static boolean isValidDurationMs(long ms) {
        return ms > 0L && ms % 60_000L == 0L && isValidDurationMinutes(ms / 60_000L);
    }

    /** Digits only, at most 3 characters. Returns the minutes or -1 when unusable/out of range. */
    static int parseCustomMinutes(String text) {
        if (text == null) {
            return -1;
        }
        String t = text.trim();
        if (t.isEmpty() || t.length() > 3) {
            return -1;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) < '0' || t.charAt(i) > '9') {
                return -1;
            }
        }
        int v = Integer.parseInt(t);
        return isValidDurationMinutes(v) ? v : -1;
    }

    /** "m:ss" below one hour, "h:mm:ss" otherwise. Negative input is shown as 0:00. */
    static String formatRemaining(long ms) {
        long totalSec = Math.max(0L, ms) / 1000L;
        // Round up so "0:00" is only shown when the session is really over.
        if (ms > 0L && ms % 1000L != 0L) {
            totalSec += 1L;
        }
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
