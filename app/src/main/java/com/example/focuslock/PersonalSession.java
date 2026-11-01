package com.example.focuslock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable record of one personal focus session.
 *
 * Timekeeping
 * -----------
 *  The session stores BOTH an absolute wall-clock schedule (startWallMs + durationMs) and a
 *  monotonic anchor (startElapsedMs + bootCount).
 *   - Same boot: remaining time comes from the monotonic clock only, so changing the
 *     system date/time (forward or backward) can neither shorten nor extend the session.
 *   - After a reboot the monotonic clock restarted, so the wall clock is the only
 *     reference left: remaining = scheduledEnd - now, capped at the full duration so a
 *     clock set backwards cannot create more time than was chosen. A clock change made
 *     while the device is off cannot be detected; this is a documented limit.
 *
 * Pure Java (no Android dependencies).
 */
final class PersonalSession {

    enum Status { ACTIVE, COMPLETED, CANCELLED }

    static final int CODEC_VERSION = 1;
    private static final int FIELD_COUNT = 11;

    final String id;
    final String ownerUid;
    final Set<String> packages;
    final Status status;
    final long startWallMs;
    final long startElapsedMs;
    final long bootCount;
    final long durationMs;
    /** 0 while ACTIVE. */
    final long endedWallMs;
    /** "", "expired" or "cancelled". */
    final String endReason;

    private PersonalSession(String id, String ownerUid, Set<String> packages, Status status,
                            long startWallMs, long startElapsedMs, long bootCount,
                            long durationMs, long endedWallMs, String endReason) {
        this.id = id;
        this.ownerUid = ownerUid;
        this.packages = Collections.unmodifiableSet(new TreeSet<>(packages));
        this.status = status;
        this.startWallMs = startWallMs;
        this.startElapsedMs = startElapsedMs;
        this.bootCount = bootCount;
        this.durationMs = durationMs;
        this.endedWallMs = endedWallMs;
        this.endReason = endReason == null ? "" : endReason;
    }

    static PersonalSession startNew(String id, String ownerUid, Set<String> packages,
                                    long durationMs, PersonalClock clock) {
        return new PersonalSession(id, ownerUid, packages, Status.ACTIVE,
                clock.wallMs(), clock.elapsedMs(), clock.bootCount(), durationMs, 0L, "");
    }

    long scheduledEndWallMs() {
        return startWallMs + durationMs;
    }

    boolean isOwnedBy(String uid) {
        return uid != null && !uid.isEmpty() && uid.equals(ownerUid);
    }

    /** Milliseconds left; 0 when not ACTIVE or when the time is used up. */
    long remainingMs(PersonalClock clock) {
        if (status != Status.ACTIVE) {
            return 0L;
        }
        long boot = clock.bootCount();
        boolean sameBoot;
        if (bootCount >= 0 && boot >= 0) {
            sameBoot = boot == bootCount;
        } else {
            sameBoot = clock.elapsedMs() >= startElapsedMs;
        }
        long remaining;
        if (sameBoot) {
            long used = Math.max(0L, clock.elapsedMs() - startElapsedMs);
            remaining = durationMs - used;
        } else {
            remaining = Math.min(durationMs, scheduledEndWallMs() - clock.wallMs());
        }
        return Math.max(0L, remaining);
    }

    boolean isActive(PersonalClock clock) {
        return status == Status.ACTIVE && remainingMs(clock) > 0L;
    }

    /** Wall-clock time at which the session ends, as seen from "now" (for display). */
    long effectiveEndWallMs(PersonalClock clock) {
        return clock.wallMs() + remainingMs(clock);
    }

    PersonalSession finished(Status newStatus, long endedWall, String reason) {
        return new PersonalSession(id, ownerUid, packages, newStatus, startWallMs,
                startElapsedMs, bootCount, durationMs, endedWall, reason);
    }

    /**
     * The normalized form consumed by the shared enforcement engine. Only valid for a session
     * that is still running: the end time is "now + remaining" so LockPolicy's wall-clock
     * comparison agrees with the monotonic remaining time computed here.
     */
    LockSession toLockSession(PersonalClock clock) {
        return new LockSession(true, effectiveEndWallMs(clock), false, packages,
                Collections.singleton(ownerUid), "");
    }

    // ------------------------------------------------------------------ codec

    String encode() {
        StringBuilder pk = new StringBuilder();
        for (String p : packages) {
            if (pk.length() > 0) {
                pk.append(',');
            }
            pk.append(p);
        }
        return CODEC_VERSION + "\t" + id + "\t" + ownerUid + "\t" + status.name()
                + "\t" + startWallMs + "\t" + startElapsedMs + "\t" + bootCount
                + "\t" + durationMs + "\t" + endedWallMs + "\t" + endReason + "\t" + pk;
    }

    /** Defensive parse. Returns null for anything that is not a fully valid record. */
    static PersonalSession decode(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String[] f = s.split("\t", -1);
        if (f.length != FIELD_COUNT) {
            return null;
        }
        try {
            if (Integer.parseInt(f[0]) != CODEC_VERSION) {
                return null;
            }
            String id = f[1];
            String uid = f[2];
            if (!isSafeToken(id) || !isSafeToken(uid)) {
                return null;
            }
            Status st = Status.valueOf(f[3]);
            long startWall = Long.parseLong(f[4]);
            long startElapsed = Long.parseLong(f[5]);
            long boot = Long.parseLong(f[6]);
            long duration = Long.parseLong(f[7]);
            long ended = Long.parseLong(f[8]);
            String reason = f[9];
            if (!reason.matches("[a-z_]{0,20}")) {
                return null;
            }
            if (startWall <= 0L || startElapsed < 0L || !PersonalRules.isValidDurationMs(duration)) {
                return null;
            }
            if (st == Status.ACTIVE ? ended != 0L : ended <= 0L) {
                return null;
            }
            List<String> raw = new ArrayList<>();
            if (!f[10].isEmpty()) {
                Collections.addAll(raw, f[10].split(",", -1));
            }
            Set<String> pkgs = PersonalRules.sanitizePackages(raw);
            if (pkgs.isEmpty() || pkgs.size() != raw.size() || pkgs.size() > PersonalRules.MAX_APPS) {
                return null;
            }
            return new PersonalSession(id, uid, pkgs, st, startWall, startElapsed, boot,
                    duration, ended, reason);
        } catch (RuntimeException e) { // NumberFormat / IllegalArgument
            return null;
        }
    }

    private static boolean isSafeToken(String t) {
        if (t == null || t.isEmpty() || t.length() > 128) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= ' ' || c == ',' || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        // No uid / package names: this string may reach Logcat.
        return "PersonalSession{id=" + id + ", status=" + status + ", apps=" + packages.size()
                + ", durationMs=" + durationMs + "}";
    }
}
