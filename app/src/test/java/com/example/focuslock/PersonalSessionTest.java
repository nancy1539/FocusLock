package com.example.focuslock;

import static com.example.focuslock.PersonalTestSupport.MIN;
import static com.example.focuslock.PersonalTestSupport.pkgs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.focuslock.PersonalTestSupport.FakeClock;

import org.junit.Test;

public class PersonalSessionTest {

    private static PersonalSession start(FakeClock c, long minutes) {
        return PersonalSession.startNew("id-1", "uid-1", pkgs("com.example.a", "com.example.b"),
                minutes * MIN, c);
    }

    @Test
    public void remainingCountsDownWithMonotonicTime() {
        FakeClock c = new FakeClock();
        PersonalSession s = start(c, 25);
        assertEquals(25 * MIN, s.remainingMs(c));
        c.advance(10 * MIN);
        assertEquals(15 * MIN, s.remainingMs(c));
        c.advance(15 * MIN);
        assertEquals(0L, s.remainingMs(c));
        assertFalse(s.isActive(c));
    }

    @Test
    public void wallClockForwardJumpDoesNotEndSessionEarlySameBoot() {
        FakeClock c = new FakeClock();
        PersonalSession s = start(c, 25);
        c.advance(5 * MIN);
        c.wall += 6 * 60 * MIN; // user sets the clock 6 hours ahead
        assertEquals(20 * MIN, s.remainingMs(c));
        assertTrue(s.isActive(c));
    }

    @Test
    public void wallClockBackwardJumpDoesNotExtendSessionSameBoot() {
        FakeClock c = new FakeClock();
        PersonalSession s = start(c, 25);
        c.advance(20 * MIN);
        c.wall -= 24 * 60 * MIN; // clock set back a day
        assertEquals(5 * MIN, s.remainingMs(c));
        c.elapsed += 5 * MIN;
        assertFalse(s.isActive(c));
    }

    @Test
    public void afterRebootWallClockIsUsedAndStillRunning() {
        FakeClock c = new FakeClock();
        PersonalSession s = start(c, 30);
        c.advance(10 * MIN);
        c.reboot(2 * MIN);               // 2 minutes powered off
        assertEquals(30 * MIN - 12 * MIN, s.remainingMs(c));
        assertTrue(s.isActive(c));
    }

    @Test
    public void afterRebootExpiredSessionIsOver() {
        FakeClock c = new FakeClock();
        PersonalSession s = start(c, 15);
        c.reboot(40 * MIN);
        assertEquals(0L, s.remainingMs(c));
    }

    @Test
    public void afterRebootBackwardClockCannotCreateMoreThanChosenDuration() {
        FakeClock c = new FakeClock();
        PersonalSession s = start(c, 15);
        c.reboot(0);
        c.wall -= 3 * 24 * 60 * MIN;
        assertEquals(15 * MIN, s.remainingMs(c));
    }

    @Test
    public void unknownBootCountFallsBackToElapsedComparison() {
        FakeClock c = new FakeClock();
        c.boot = -1L;
        PersonalSession s = start(c, 30);
        c.advance(10 * MIN);
        assertEquals(20 * MIN, s.remainingMs(c));
        c.elapsed = 1_000L;              // monotonic clock restarted -> reboot
        c.wall += 12 * MIN;
        assertEquals(30 * MIN - 22 * MIN, s.remainingMs(c));
    }

    @Test
    public void finishedSessionsHaveNoRemainingTime() {
        FakeClock c = new FakeClock();
        PersonalSession s = start(c, 25).finished(PersonalSession.Status.CANCELLED, c.wall, "cancelled");
        assertEquals(0L, s.remainingMs(c));
        assertFalse(s.isActive(c));
    }

    @Test
    public void codecRoundTrips() {
        FakeClock c = new FakeClock();
        PersonalSession s = start(c, 45);
        PersonalSession d = PersonalSession.decode(s.encode());
        assertNotNull(d);
        assertEquals(s.id, d.id);
        assertEquals(s.ownerUid, d.ownerUid);
        assertEquals(s.packages, d.packages);
        assertEquals(s.durationMs, d.durationMs);
        assertEquals(s.startWallMs, d.startWallMs);
        assertEquals(s.startElapsedMs, d.startElapsedMs);
        assertEquals(s.bootCount, d.bootCount);
        assertEquals(PersonalSession.Status.ACTIVE, d.status);

        PersonalSession f = s.finished(PersonalSession.Status.COMPLETED, c.wall + 5, "expired");
        PersonalSession fd = PersonalSession.decode(f.encode());
        assertEquals(PersonalSession.Status.COMPLETED, fd.status);
        assertEquals("expired", fd.endReason);
        assertEquals(c.wall + 5, fd.endedWallMs);
    }

    @Test
    public void decodeRejectsDamagedRecords() {
        FakeClock c = new FakeClock();
        String good = start(c, 25).encode();
        assertNull(PersonalSession.decode(null));
        assertNull(PersonalSession.decode(""));
        assertNull(PersonalSession.decode("not a record"));
        assertNull(PersonalSession.decode(good.replace("1\tid-1", "2\tid-1")));            // version
        assertNull(PersonalSession.decode(good.replace("ACTIVE", "RUNNING")));             // status
        assertNull(PersonalSession.decode(good.replace("\t1500000\t", "\tabc\t")));        // number
        assertNull(PersonalSession.decode(good.substring(0, good.lastIndexOf('\t') + 1))); // no apps
        assertNull(PersonalSession.decode(good + ",bad name"));                            // bad pkg
        assertNull(PersonalSession.decode(good + "\textra"));                              // field count
        String zeroDuration = "1\tid\tuid\tACTIVE\t1700000000000\t5\t1\t0\t0\t\tcom.example.a";
        assertNull(PersonalSession.decode(zeroDuration));
        String activeWithEnd = "1\tid\tuid\tACTIVE\t1700000000000\t5\t1\t60000\t99\t\tcom.example.a";
        assertNull(PersonalSession.decode(activeWithEnd));
        String doneWithoutEnd = "1\tid\tuid\tCOMPLETED\t1700000000000\t5\t1\t60000\t0\t\tcom.example.a";
        assertNull(PersonalSession.decode(doneWithoutEnd));
    }

    @Test
    public void toStringDoesNotLeakUidOrPackages() {
        String t = start(new FakeClock(), 25).toString();
        assertFalse(t.contains("uid-1"));
        assertFalse(t.contains("com.example.a"));
    }

    @Test
    public void rulesParseAndFormat() {
        assertEquals(42, PersonalRules.parseCustomMinutes(" 42 "));
        assertEquals(-1, PersonalRules.parseCustomMinutes(""));
        assertEquals(-1, PersonalRules.parseCustomMinutes("0"));
        assertEquals(-1, PersonalRules.parseCustomMinutes("241"));
        assertEquals(-1, PersonalRules.parseCustomMinutes("12a"));
        assertEquals(-1, PersonalRules.parseCustomMinutes("-5"));
        assertEquals(-1, PersonalRules.parseCustomMinutes("1234"));
        assertEquals(240, PersonalRules.parseCustomMinutes("240"));
        assertEquals("0:00", PersonalRules.formatRemaining(0));
        assertEquals("0:00", PersonalRules.formatRemaining(-5));
        assertEquals("0:01", PersonalRules.formatRemaining(1));      // rounds up while running
        assertEquals("25:00", PersonalRules.formatRemaining(25 * MIN));
        assertEquals("1:00:00", PersonalRules.formatRemaining(60 * MIN));
        assertEquals("1:01:05", PersonalRules.formatRemaining(61 * MIN + 5_000));
        assertTrue(PersonalRules.isValidPackage("com.example.app_1"));
        assertFalse(PersonalRules.isValidPackage("android"));
        assertFalse(PersonalRules.isValidPackage("com..x"));
        assertFalse(PersonalRules.isValidPackage("com.exa mple"));
        assertFalse(PersonalRules.isValidPackage(null));
    }
}
