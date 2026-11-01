package com.example.focuslock;

import static com.example.focuslock.PersonalTestSupport.MIN;
import static com.example.focuslock.PersonalTestSupport.pkgs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.focuslock.PersonalSessionController.CancelResult;
import com.example.focuslock.PersonalSessionController.StartOutcome;
import com.example.focuslock.PersonalSessionController.StartResult;
import com.example.focuslock.PersonalTestSupport.Rig;

import org.junit.Test;

public class PersonalSessionControllerTest {

    private static final String A = "com.example.alpha";
    private static final String B = "com.example.beta";

    // 1
    @Test
    public void emptySelectionCannotStart() {
        Rig r = new Rig();
        assertEquals(StartResult.NO_APPS, r.controller.start("u1", pkgs(), 25 * MIN).result);
        assertEquals(StartResult.NO_APPS, r.controller.start("u1", null, 25 * MIN).result);
        assertEquals(StartResult.NO_APPS, r.controller.start("u1", pkgs("not a package"), 25 * MIN).result);
        assertNull(r.controller.current());
        assertEquals(0, r.store.writes);
    }

    // 2
    @Test
    public void invalidDurationIsRejected() {
        Rig r = new Rig();
        long[] bad = {0L, -1L, 30_000L, 90_000L, (PersonalRules.MAX_MINUTES + 1) * MIN, Long.MAX_VALUE};
        for (long d : bad) {
            assertEquals("duration " + d, StartResult.BAD_DURATION, r.controller.start("u1", pkgs(A), d).result);
        }
        assertNull(r.controller.current());
        assertEquals(StartResult.STARTED, r.controller.start("u1", pkgs(A), PersonalRules.MIN_MINUTES * MIN).result);
    }

    @Test
    public void maxDurationBoundaryIsAccepted() {
        Rig r = new Rig();
        assertEquals(StartResult.STARTED,
                r.controller.start("u1", pkgs(A), PersonalRules.MAX_MINUTES * MIN).result);
    }

    @Test
    public void tooManyAppsIsRejected() {
        Rig r = new Rig();
        java.util.Set<String> many = new java.util.HashSet<>();
        for (int i = 0; i <= PersonalRules.MAX_APPS; i++) many.add("com.example.app" + i);
        assertEquals(StartResult.TOO_MANY_APPS, r.controller.start("u1", many, 25 * MIN).result);
    }

    @Test
    public void signedOutCannotStart() {
        Rig r = new Rig();
        assertEquals(StartResult.NOT_SIGNED_IN, r.controller.start("", pkgs(A), 25 * MIN).result);
        assertEquals(StartResult.NOT_SIGNED_IN, r.controller.start(null, pkgs(A), 25 * MIN).result);
    }

    // 3 + 4
    @Test
    public void validSessionIsCreatedAndPackagesPersist() {
        Rig r = new Rig();
        StartOutcome o = r.controller.start("u1", pkgs(A, B), 25 * MIN);
        assertEquals(StartResult.STARTED, o.result);
        assertEquals("sess-1", o.session.id);
        assertEquals("u1", o.session.ownerUid);
        assertEquals(pkgs(A, B), o.session.packages);
        assertEquals(25 * MIN, o.session.durationMs);
        assertEquals(PersonalSession.Status.ACTIVE, o.session.status);
        // persisted BEFORE STARTED was reported
        assertNotNull(r.store.data.get("active"));
        PersonalSession loaded = PersonalSession.decode(r.store.data.get("active"));
        assertEquals(pkgs(A, B), loaded.packages);
    }

    @Test
    public void accessibilityMissingBlocksStartAndWritesNothing() {
        Rig r = new Rig();
        r.env.a11y = false;
        StartOutcome o = r.controller.start("u1", pkgs(A), 25 * MIN);
        assertEquals(StartResult.ACCESSIBILITY_OFF, o.result);
        assertNull(o.session);
        assertEquals(0, r.store.writes);
        assertNull(r.controller.current());
    }

    @Test
    public void storageFailureIsNotReportedAsStarted() {
        Rig r = new Rig();
        r.store.failWrites = true;
        assertEquals(StartResult.STORAGE_FAILED, r.controller.start("u1", pkgs(A), 25 * MIN).result);
        assertNull(r.controller.current());
    }

    // 5
    @Test
    public void expiredSessionIsInactiveAndMovedToHistory() {
        Rig r = new Rig();
        r.controller.start("u1", pkgs(A), 5 * MIN);
        r.clock.advance(5 * MIN - 1);
        assertNotNull(r.controller.current());
        r.clock.advance(1);
        assertNull(r.controller.current());
        assertNull(r.store.data.get("active"));
        PersonalSession last = r.controller.lastSession("u1");
        assertEquals(PersonalSession.Status.COMPLETED, last.status);
        assertEquals("expired", last.endReason);
    }

    // 6
    @Test
    public void activeSessionSurvivesReload() {
        Rig r = new Rig();
        r.controller.start("u1", pkgs(A, B), 30 * MIN);
        r.clock.advance(10 * MIN);
        PersonalSessionController after = r.reload();
        PersonalSession s = after.current();
        assertNotNull(s);
        assertEquals(20 * MIN, s.remainingMs(r.clock));
        assertEquals(pkgs(A, B), s.packages);
    }

    // 7
    @Test
    public void cancelledSessionStopsEnforcing() {
        Rig r = new Rig();
        PersonalSession s = r.controller.start("u1", pkgs(A), 30 * MIN).session;
        assertTrue(EnforcementPolicy.resolve(null, "", r.controller.current(), r.clock).isEnforcing());
        assertEquals(CancelResult.CANCELLED, r.controller.cancel("u1", s.id));
        assertNull(r.controller.current());
        assertFalse(EnforcementPolicy.resolve(null, "", r.controller.current(), r.clock).isEnforcing());
        PersonalSession last = r.controller.lastSession("u1");
        assertEquals(PersonalSession.Status.CANCELLED, last.status);
    }

    @Test
    public void cancelIsIdempotentAndRejectsStaleIds() {
        Rig r = new Rig();
        PersonalSession s = r.controller.start("u1", pkgs(A), 30 * MIN).session;
        assertEquals(CancelResult.STALE_SESSION, r.controller.cancel("u1", "other-id"));
        assertEquals(CancelResult.STALE_SESSION, r.controller.cancel("u1", null));
        assertEquals(CancelResult.CANCELLED, r.controller.cancel("u1", s.id));
        assertEquals(CancelResult.NOT_ACTIVE, r.controller.cancel("u1", s.id));
        assertEquals(1, r.controller.history("u1").size());
    }

    // 8
    @Test
    public void expirationClearsThePersonalPolicy() {
        Rig r = new Rig();
        r.controller.start("u1", pkgs(A), 15 * MIN);
        assertEquals(EnforcementPolicy.Mode.PERSONAL,
                EnforcementPolicy.resolve(null, "", r.controller.current(), r.clock).mode);
        r.clock.advance(15 * MIN);
        assertEquals(EnforcementPolicy.Mode.NONE,
                EnforcementPolicy.resolve(null, "", r.controller.current(), r.clock).mode);
    }

    // 11
    @Test
    public void otherUserCannotCancel() {
        Rig r = new Rig();
        PersonalSession s = r.controller.start("u1", pkgs(A), 30 * MIN).session;
        assertEquals(CancelResult.NOT_OWNER, r.controller.cancel("u2", s.id));
        assertEquals(CancelResult.NOT_OWNER, r.controller.cancel("", s.id));
        assertEquals(CancelResult.NOT_OWNER, r.controller.cancel(null, s.id));
        assertNotNull(r.controller.current());
    }

    // 13
    @Test
    public void rapidRepeatedStartsCreateOneSession() {
        Rig r = new Rig();
        StartOutcome first = r.controller.start("u1", pkgs(A), 25 * MIN);
        StartOutcome second = r.controller.start("u1", pkgs(B), 60 * MIN);
        StartOutcome third = r.controller.start("u1", pkgs(A), 25 * MIN);
        assertEquals(StartResult.STARTED, first.result);
        assertEquals(StartResult.ALREADY_ACTIVE, second.result);
        assertEquals(StartResult.ALREADY_ACTIVE, third.result);
        assertEquals(first.session.id, second.session.id);
        PersonalSession cur = r.controller.current();
        assertEquals(pkgs(A), cur.packages);          // not replaced
        assertEquals(25 * MIN, cur.durationMs);        // not extended
        assertEquals(1, r.store.writes);
    }

    @Test
    public void concurrentStartsFromManyThreadsCreateExactlyOneSession() throws Exception {
        final Rig r = new Rig();
        final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] ts = new Thread[16];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                if (r.controller.start("u1", pkgs(A), 25 * MIN).result == StartResult.STARTED) {
                    started.incrementAndGet();
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        assertEquals(1, started.get());
    }

    @Test
    public void startWhileOtherAccountSessionRunsIsRefused() {
        Rig r = new Rig();
        r.controller.start("u1", pkgs(A), 25 * MIN);
        StartOutcome o = r.controller.start("u2", pkgs(B), 25 * MIN);
        assertEquals(StartResult.ALREADY_ACTIVE, o.result);
        assertEquals("u1", r.controller.current().ownerUid);
    }

    // school conflict at start
    @Test
    public void personalStartIsRefusedWhileSchoolSessionIsActive() {
        Rig r = new Rig();
        r.env.school = true;
        assertEquals(StartResult.SCHOOL_ACTIVE, r.controller.start("u1", pkgs(A), 25 * MIN).result);
        assertNull(r.controller.current());
        assertEquals(0, r.store.writes);
    }

    // 15
    @Test
    public void selectionsAreScopedPerAccount() {
        Rig r = new Rig();
        assertTrue(r.controller.saveSelection("u1", pkgs(A, B)));
        assertEquals(pkgs(A, B), r.controller.loadSelection("u1"));
        assertTrue(r.controller.loadSelection("u2").isEmpty());   // next user sees nothing
        assertTrue(r.controller.loadSelection("").isEmpty());
        assertTrue(r.controller.loadSelection(null).isEmpty());
        assertTrue(r.controller.saveSelection("u2", pkgs(B)));
        assertEquals(pkgs(A, B), r.reload().loadSelection("u1"));
        assertEquals(pkgs(B), r.reload().loadSelection("u2"));
        assertTrue(r.controller.saveSelection("u1", pkgs()));
        assertTrue(r.controller.loadSelection("u1").isEmpty());
        assertEquals(pkgs(B), r.controller.loadSelection("u2"));
    }

    @Test
    public void historyIsPerAccountAndCapped() {
        Rig r = new Rig();
        for (int i = 0; i < PersonalSessionRepository.MAX_HISTORY + 5; i++) {
            PersonalSession s = r.controller.start("u1", pkgs(A), 5 * MIN).session;
            r.controller.cancel("u1", s.id);
        }
        assertEquals(PersonalSessionRepository.MAX_HISTORY, r.controller.history("u1").size());
        PersonalSession s2 = r.controller.start("u2", pkgs(B), 5 * MIN).session;
        r.controller.cancel("u2", s2.id);
        assertEquals(1, r.controller.history("u2").size());                  // own entry only
        assertEquals(PersonalSessionRepository.MAX_HISTORY - 1, r.controller.history("u1").size());
        for (PersonalSession s : r.controller.history("u1")) assertEquals("u1", s.ownerUid);
        assertTrue(r.controller.history("u3").isEmpty());
    }

    // future strict mode hook
    @Test
    public void cancelPolicyHookCanRefuseEarlyTermination() {
        Rig r = new Rig();
        PersonalSessionController strict = r.build(s -> false);
        PersonalSession s = strict.start("u1", pkgs(A), 25 * MIN).session;
        assertEquals(CancelResult.NOT_ALLOWED, strict.cancel("u1", s.id));
        assertNotNull(strict.current());
    }

    // 12 (session side): uninstalled packages never break an active session
    @Test
    public void sessionWithUninstalledPackageStillWorks() {
        Rig r = new Rig();
        r.controller.start("u1", pkgs(A, "com.example.gone"), 25 * MIN);
        EnforcementPolicy p = EnforcementPolicy.resolve(null, "", r.controller.current(), r.clock);
        assertTrue(p.decide(A, "com.example.focuslock", null, r.clock.wall).block);
        assertFalse(p.decide(B, "com.example.focuslock", null, r.clock.wall).block);
    }

    @Test
    public void corruptActiveRecordIsDroppedNotTrusted() {
        Rig r = new Rig();
        r.store.data.put("active", "garbage\tdata");
        assertNull(r.controller.current());
        assertNull(r.store.data.get("active"));
    }

    @Test
    public void failedFinalizeStillReportsSessionOver() {
        Rig r = new Rig();
        r.controller.start("u1", pkgs(A), 5 * MIN);
        r.clock.advance(6 * MIN);
        r.store.failWrites = true;
        assertNull(r.controller.current());   // not enforced even though the write failed
        r.store.failWrites = false;
        assertNull(r.controller.current());   // retried and finalised
        assertEquals(PersonalSession.Status.COMPLETED, r.controller.lastSession("u1").status);
    }
}
