package com.example.focuslock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class LockSessionTest {

    private static Map<String, Object> valid() {
        Map<String, Object> m = new HashMap<>();
        m.put("active", true);
        m.put("endTime", 2_000L);
        m.put("blockAll", false);
        m.put("blockedApps", Arrays.asList("com.instagram.android", "com.whatsapp"));
        m.put("presentIds", Arrays.asList("s1", "s2"));
        m.put("date", "2026-10-21");
        return m;
    }

    @Test
    public void parsesValidActiveSession() {
        LockSession.Parsed p = LockSession.parse(valid());
        assertNull(p.error);
        LockSession s = p.session;
        assertNotNull(s);
        assertTrue(s.active);
        assertEquals(2_000L, s.endTime);
        assertTrue(s.blockedApps.contains("com.whatsapp"));
        assertTrue(s.includesStudent("s2"));
        assertFalse(s.includesStudent("s3"));
        assertFalse(s.includesStudent(""));
        assertFalse(s.includesStudent(null));
    }

    @Test
    public void endTimeAsDoubleIsAccepted() {
        Map<String, Object> m = valid();
        m.put("endTime", 2_000.0d);
        assertEquals(2_000L, LockSession.parse(m).session.endTime);
    }

    @Test
    public void explicitStopIsValidEvenWithoutOtherFields() {
        Map<String, Object> m = new HashMap<>();
        m.put("active", false);
        LockSession.Parsed p = LockSession.parse(m);
        assertNotNull(p.session);
        assertFalse(p.session.active);
    }

    @Test
    public void malformedDocumentsAreRejectedNotTreatedAsStop() {
        assertNotNull(LockSession.parse(null).error);

        Map<String, Object> m = valid();
        m.remove("active");
        assertNotNull(LockSession.parse(m).error);

        m = valid();
        m.put("active", "true"); // wrong type
        assertNotNull(LockSession.parse(m).error);

        m = valid();
        m.remove("endTime"); // active but no end => never enforced
        assertNotNull(LockSession.parse(m).error);

        m = valid();
        m.put("endTime", "tomorrow");
        assertNotNull(LockSession.parse(m).error);

        m = valid();
        m.put("endTime", 0L);
        assertNotNull(LockSession.parse(m).error);

        m = valid();
        m.put("blockedApps", "com.instagram.android"); // not a list
        assertNotNull(LockSession.parse(m).error);

        m = valid();
        m.put("presentIds", 5L);
        assertNotNull(LockSession.parse(m).error);

        m = valid();
        m.put("blockAll", "yes");
        assertNotNull(LockSession.parse(m).error);
    }

    /** Exactly the shape ClassActivity.startLock writes (ArrayList values, Long endTime). */
    private static Map<String, Object> startLockShape(boolean blockAll, java.util.List<String> apps) {
        Map<String, Object> m = new HashMap<>();
        m.put("active", true);
        m.put("endTime", Long.valueOf(System.currentTimeMillis() + 15 * 60000L));
        m.put("blockAll", blockAll);
        m.put("blockedApps", new java.util.ArrayList<>(apps));
        m.put("presentIds", new java.util.ArrayList<>(Arrays.asList("s1")));
        m.put("date", "2026-10-22");
        return m;
    }

    @Test
    public void existingStartLockRecordsRemainValid() {
        // List lock.
        LockSession a = LockSession.parse(startLockShape(false,
                Arrays.asList("com.instagram.android"))).session;
        assertNotNull(a);
        assertTrue(a.blockedApps.contains("com.instagram.android"));
        // "Block ALL" lock: blockedApps is an EMPTY list in the existing schema.
        LockSession b = LockSession.parse(startLockShape(true,
                java.util.Collections.<String>emptyList())).session;
        assertNotNull(b);
        assertTrue(b.blockAll);
        assertTrue(b.blockedApps.isEmpty());
        assertTrue(b.includesStudent("s1"));
    }

    @Test
    public void activeSessionWithoutBlockedAppsIsRejectedWithReason() {
        Map<String, Object> m = valid();
        m.remove("blockedApps");
        LockSession.Parsed p = LockSession.parse(m);
        assertNull(p.session);
        assertNotNull(p.error);
        assertTrue(p.error, p.error.contains("blockedApps") && p.error.contains("missing"));
    }

    @Test
    public void activeSessionWithoutPresentIdsIsRejectedWithReason() {
        Map<String, Object> m = valid();
        m.remove("presentIds");
        LockSession.Parsed p = LockSession.parse(m);
        assertNull("a missing eligibility list must not become 'nobody is eligible'", p.session);
        assertTrue(p.error, p.error.contains("presentIds") && p.error.contains("missing"));
    }

    @Test
    public void explicitNullListsAreRejectedAsNullNotMissing() {
        Map<String, Object> m = valid();
        m.put("presentIds", null);
        LockSession.Parsed p = LockSession.parse(m);
        assertNull(p.session);
        assertTrue(p.error, p.error.contains("presentIds") && p.error.contains("null"));

        m = valid();
        m.put("blockedApps", null);
        p = LockSession.parse(m);
        assertNull(p.session);
        assertTrue(p.error, p.error.contains("blockedApps") && p.error.contains("null"));
    }

    @Test
    public void badListElementsAreRejectedWithTheirIndex() {
        Map<String, Object> m = valid();
        m.put("presentIds", Arrays.asList("s1", 7L));
        LockSession.Parsed p = LockSession.parse(m);
        assertNull(p.session);
        assertTrue(p.error, p.error.contains("presentIds") && p.error.contains("#1"));

        m = valid();
        m.put("blockedApps", Arrays.asList("com.a", null));
        p = LockSession.parse(m);
        assertNull(p.session);
        assertTrue(p.error, p.error.contains("blockedApps") && p.error.contains("null"));

        m = valid();
        m.put("blockedApps", Arrays.asList("com.a", "   "));
        p = LockSession.parse(m);
        assertNull(p.session);
        assertTrue(p.error, p.error.contains("blank"));
    }

    @Test
    public void activeSessionWithEmptyPresentIdsIsRejected() {
        Map<String, Object> m = valid();
        m.put("presentIds", java.util.Collections.<String>emptyList());
        LockSession.Parsed p = LockSession.parse(m);
        assertNull(p.session);
        assertTrue(p.error, p.error.contains("presentIds") && p.error.contains("empty"));

        // Also rejected in Block ALL mode: eligibility is mandatory either way.
        m.put("blockAll", true);
        assertNull(LockSession.parse(m).session);
    }

    @Test
    public void emptyBlockedAppsWithBlockAllFalseIsRejected() {
        Map<String, Object> m = valid();
        m.put("blockAll", false);
        m.put("blockedApps", java.util.Collections.<String>emptyList());
        LockSession.Parsed p = LockSession.parse(m);
        assertNull(p.session);
        assertTrue(p.error, p.error.contains("blockedApps") && p.error.contains("blockAll=false"));
    }

    @Test
    public void emptyBlockedAppsWithBlockAllAbsentIsRejected() {
        Map<String, Object> m = valid();
        m.remove("blockAll");                       // absent = list mode
        m.put("blockedApps", java.util.Collections.<String>emptyList());
        assertNull(LockSession.parse(m).session);
    }

    @Test
    public void emptyBlockedAppsWithBlockAllTrueIsAccepted() {
        Map<String, Object> m = valid();
        m.put("blockAll", true);
        m.put("blockedApps", java.util.Collections.<String>emptyList());
        LockSession s = LockSession.parse(m).session;
        assertNotNull(s);
        assertTrue(s.blockAll);
        assertTrue(s.blockedApps.isEmpty());
        assertTrue(s.includesStudent("s1"));
    }

    @Test
    public void blockAllTrueWithAlsoCheckedAppsIsAccepted() {
        // startLock writes the checkbox selection next to blockAll == true.
        LockSession s = LockSession.parse(startLockShape(true,
                Arrays.asList("com.instagram.android"))).session;
        assertNotNull(s);
        assertTrue(s.blockAll);
        assertEquals(1, s.blockedApps.size());
    }

    @Test
    public void multiStudentTeacherGeneratedSessionParses() {
        Map<String, Object> m = startLockShape(false,
                Arrays.asList("com.instagram.android", "com.google.android.youtube"));
        m.put("presentIds", new java.util.ArrayList<>(Arrays.asList("s1", "s2", "s3")));
        LockSession s = LockSession.parse(m).session;
        assertNotNull(s);
        assertEquals(3, s.presentIds.size());
        assertEquals(2, s.blockedApps.size());
        assertFalse(s.blockAll);
    }

    @Test
    public void sharedActiveSessionRuleMatchesParserAndIsUsableByPersistence() {
        java.util.Set<String> none = java.util.Collections.<String>emptySet();
        java.util.Set<String> apps = new java.util.HashSet<>(Arrays.asList("com.a"));
        java.util.Set<String> students = new java.util.HashSet<>(Arrays.asList("s1"));

        assertNull(LockSession.activeSessionProblem(false, apps, students));
        assertNull(LockSession.activeSessionProblem(true, apps, students));
        assertNull(LockSession.activeSessionProblem(true, none, students));   // Block ALL, no apps
        assertNotNull(LockSession.activeSessionProblem(true, apps, none));     // nobody targeted
        assertNotNull(LockSession.activeSessionProblem(false, none, students)); // blocks nothing
        assertNotNull(LockSession.activeSessionProblem(false, none, none));
    }

    @Test
    public void stopDocumentStaysValidWithoutLists() {
        Map<String, Object> m = new HashMap<>();
        m.put("active", false);
        m.put("endTime", 5L);
        assertNotNull(LockSession.parse(m).session);
    }

    @Test
    public void blockAllAbsentStillMeansListMode() {
        Map<String, Object> m = valid();
        m.remove("blockAll");
        LockSession s = LockSession.parse(m).session;
        assertNotNull(s);
        assertFalse(s.blockAll);
        assertFalse(s.blockedApps.isEmpty());
    }

    @Test
    public void missingDateIsNotAnEligibilityFieldAndStaysAccepted() {
        Map<String, Object> m = valid();
        m.remove("date");
        assertNotNull(LockSession.parse(m).session);
    }

    @Test
    public void expiryBoundaryIsInclusive() {
        LockSession s = LockSession.parse(valid()).session;
        assertFalse(s.isExpired(1_999L));
        assertTrue(s.isExpired(2_000L));
        assertTrue(s.isExpired(9_999L));
    }

    @Test
    public void equalsIgnoresListOrder() {
        Map<String, Object> a = valid();
        Map<String, Object> b = valid();
        b.put("presentIds", Arrays.asList("s2", "s1"));
        assertEquals(LockSession.parse(a).session, LockSession.parse(b).session);
    }
}
