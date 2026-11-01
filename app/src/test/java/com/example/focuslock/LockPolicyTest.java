package com.example.focuslock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LockPolicyTest {

    private static final String SELF = "com.example.focuslock";
    private static final String ME = "student1";
    private static final long NOW = 1_000L;
    private static final long END = 5_000L;

    private static final LockPolicy.Exemptions EXEMPT =
            pkg -> pkg.equals("com.android.dialer") || pkg.equals("com.launcher")
                    || pkg.equals("com.keyboard");

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    private static LockSession session(boolean active, long end, boolean all,
                                       Set<String> apps, Set<String> present) {
        return new LockSession(active, end, all, apps, present, "2026-10-21");
    }

    private static LockPolicy.Decision decide(LockSession s, String studentId, String pkg, long now) {
        return LockPolicy.decide(s, studentId, pkg, SELF, EXEMPT, now);
    }

    private final LockSession listed =
            session(true, END, false, set("com.instagram.android"), set(ME));

    @Test
    public void blocksListedAppForEligibleStudent() {
        LockPolicy.Decision d = decide(listed, ME, "com.instagram.android", NOW);
        assertTrue(d.block);
        assertEquals(LockPolicy.Reason.BLOCKED_BY_LIST, d.reason);
    }

    @Test
    public void allowsUnlistedApp() {
        LockPolicy.Decision d = decide(listed, ME, "com.example.calculator", NOW);
        assertFalse(d.block);
        assertEquals(LockPolicy.Reason.NOT_IN_BLOCK_LIST, d.reason);
    }

    /** Regression for the 1s cooldown: policy has no time-since-last-block input at all. */
    @Test
    public void sameBlockedAppIsBlockedOnEveryConsecutiveCheck() {
        for (long t = NOW; t < NOW + 50; t++) {
            assertTrue(decide(listed, ME, "com.instagram.android", t).block);
        }
    }

    @Test
    public void switchingBetweenTwoBlockedAppsBlocksBoth() {
        LockSession s = session(true, END, false,
                set("com.instagram.android", "com.whatsapp"), set(ME));
        assertTrue(decide(s, ME, "com.instagram.android", NOW).block);
        assertTrue(decide(s, ME, "com.whatsapp", NOW + 1).block);
        assertTrue(decide(s, ME, "com.instagram.android", NOW + 2).block);
    }

    @Test
    public void blockAllBlocksEverythingExceptExemptSelfAndUnknown() {
        LockSession s = session(true, END, true, set(), set(ME));
        assertTrue(decide(s, ME, "com.random.app", NOW).block);
        assertEquals(LockPolicy.Reason.BLOCKED_ALL, decide(s, ME, "com.random.app", NOW).reason);

        assertFalse(decide(s, ME, SELF, NOW).block);
        assertEquals(LockPolicy.Reason.SELF_PACKAGE, decide(s, ME, SELF, NOW).reason);
        assertFalse(decide(s, ME, "com.android.dialer", NOW).block);
        assertFalse(decide(s, ME, "com.launcher", NOW).block);
        assertFalse(decide(s, ME, "com.keyboard", NOW).block);
        assertEquals(LockPolicy.Reason.UNKNOWN_PACKAGE, decide(s, ME, null, NOW).reason);
        assertEquals(LockPolicy.Reason.UNKNOWN_PACKAGE, decide(s, ME, "", NOW).reason);
    }

    @Test
    public void exemptionsWinEvenIfTeacherListedThePackage() {
        LockSession s = session(true, END, false, set("com.android.dialer"), set(ME));
        LockPolicy.Decision d = decide(s, ME, "com.android.dialer", NOW);
        assertFalse(d.block);
        assertEquals(LockPolicy.Reason.EXEMPT_PACKAGE, d.reason);
    }

    @Test
    public void selfIsNeverBlockedEvenIfListed() {
        LockSession s = session(true, END, false, set(SELF), set(ME));
        assertFalse(decide(s, ME, SELF, NOW).block);
    }

    @Test
    public void exemptionLookupIsOnlyEvaluatedForPackagesThatWouldBeBlocked() {
        final int[] calls = {0};
        LockPolicy.Exemptions counting = pkg -> {
            calls[0]++;
            return false;
        };
        LockPolicy.decide(listed, ME, "com.example.calculator", SELF, counting, NOW);
        assertEquals(0, calls[0]);
        LockPolicy.decide(listed, ME, "com.instagram.android", SELF, counting, NOW);
        assertEquals(1, calls[0]);
    }

    @Test
    public void expiredSessionAllows() {
        LockPolicy.Decision d = decide(listed, ME, "com.instagram.android", END);
        assertFalse(d.block);
        assertEquals(LockPolicy.Reason.SESSION_EXPIRED, d.reason);
        assertTrue(decide(listed, ME, "com.instagram.android", END - 1).block);
    }

    @Test
    public void teacherStoppedSessionAllows() {
        LockSession stopped = session(false, END, false, set("com.instagram.android"), set(ME));
        LockPolicy.Decision d = decide(stopped, ME, "com.instagram.android", NOW);
        assertFalse(d.block);
        assertEquals(LockPolicy.Reason.SESSION_STOPPED, d.reason);
    }

    @Test
    public void unavailableSessionNeverLocks() {
        LockPolicy.Decision d = decide(null, ME, "com.instagram.android", NOW);
        assertFalse(d.block);
        assertEquals(LockPolicy.Reason.NO_SESSION, d.reason);
    }

    @Test
    public void ineligibleStudentIsNotLocked() {
        assertEquals(LockPolicy.Reason.STUDENT_NOT_ELIGIBLE,
                decide(listed, "someoneElse", "com.instagram.android", NOW).reason);
        assertFalse(decide(listed, "", "com.instagram.android", NOW).block);
        assertFalse(decide(listed, null, "com.instagram.android", NOW).block);
    }

    @Test
    public void eligibilityChangeThroughNewSessionUnlocksImmediately() {
        assertTrue(decide(listed, ME, "com.instagram.android", NOW).block);
        LockSession restarted = session(true, END, false,
                set("com.instagram.android"), set("student2"));
        assertFalse(decide(restarted, ME, "com.instagram.android", NOW).block);
    }

    @Test
    public void sessionStateClassification() {
        assertEquals(LockPolicy.SessionState.NONE, LockPolicy.sessionState(null, ME, NOW));
        assertEquals(LockPolicy.SessionState.STOPPED,
                LockPolicy.sessionState(session(false, END, false, set(), set(ME)), ME, NOW));
        assertEquals(LockPolicy.SessionState.EXPIRED, LockPolicy.sessionState(listed, ME, END));
        assertEquals(LockPolicy.SessionState.NOT_ELIGIBLE,
                LockPolicy.sessionState(listed, "x", NOW));
        assertEquals(LockPolicy.SessionState.ENFORCING, LockPolicy.sessionState(listed, ME, NOW));
    }

    @Test
    public void nullExemptionsIsSafe() {
        assertTrue(LockPolicy.decide(listed, ME, "com.instagram.android", SELF, null, NOW).block);
        assertFalse(LockPolicy.decide(listed, ME, "x", SELF, null, NOW).block);
        assertEquals(Collections.emptySet(), new HashSet<String>());
    }
}
