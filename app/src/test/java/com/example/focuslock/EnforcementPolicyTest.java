package com.example.focuslock;

import static com.example.focuslock.PersonalTestSupport.MIN;
import static com.example.focuslock.PersonalTestSupport.pkgs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.focuslock.PersonalTestSupport.FakeClock;
import com.example.focuslock.PersonalTestSupport.Rig;

import org.junit.Test;

import java.util.Collections;

/** The shared engine: school wins, personal is a normal LockSession, school logic unchanged. */
public class EnforcementPolicyTest {

    private static final String SELF = "com.example.focuslock";
    private static final String P_APP = "com.example.personalapp";
    private static final String S_APP = "com.example.schoolapp";

    private static LockSession schoolList(long end, String... apps) {
        return new LockSession(true, end, false, pkgs(apps), pkgs("stu1"), "2026-10-23");
    }

    private static LockSession schoolAll(long end) {
        return new LockSession(true, end, true, Collections.<String>emptySet(), pkgs("stu1"), "d");
    }

    private static PersonalSession personal(FakeClock c, String... apps) {
        return PersonalSession.startNew("p1", "uid1", pkgs(apps), 30 * MIN, c);
    }

    // 9
    @Test
    public void staleExpiredPersonalSessionCannotOverrideSchool() {
        FakeClock c = new FakeClock();
        PersonalSession p = personal(c, P_APP);
        c.advance(31 * MIN);                                   // personal is over
        LockSession school = schoolList(c.wall + 10 * MIN, S_APP);
        EnforcementPolicy pol = EnforcementPolicy.resolve(school, "stu1", p, c);
        assertEquals(EnforcementPolicy.Mode.SCHOOL, pol.mode);
        assertTrue(pol.decide(S_APP, SELF, null, c.wall).block);
        assertFalse(pol.decide(P_APP, SELF, null, c.wall).block);   // personal list is NOT applied
    }

    @Test
    public void runningPersonalSessionDoesNotReplaceSchoolSession() {
        FakeClock c = new FakeClock();
        PersonalSession p = personal(c, P_APP);
        LockSession school = schoolList(c.wall + 10 * MIN, S_APP);
        EnforcementPolicy pol = EnforcementPolicy.resolve(school, "stu1", p, c);
        assertEquals(EnforcementPolicy.Mode.SCHOOL, pol.mode);
        assertTrue(pol.decide(S_APP, SELF, null, c.wall).block);
        assertFalse(pol.decide(P_APP, SELF, null, c.wall).block);
    }

    @Test
    public void personalResumesWhenSchoolSessionEnds() {
        FakeClock c = new FakeClock();
        PersonalSession p = personal(c, P_APP);
        LockSession school = schoolList(c.wall + 10 * MIN, S_APP);
        c.advance(10 * MIN);                                    // school expired, personal runs
        EnforcementPolicy pol = EnforcementPolicy.resolve(school, "stu1", p, c);
        assertEquals(EnforcementPolicy.Mode.PERSONAL, pol.mode);
        assertTrue(pol.decide(P_APP, SELF, null, c.wall).block);
    }

    @Test
    public void schoolStoppedOrNotEligibleFallsThroughToPersonal() {
        FakeClock c = new FakeClock();
        PersonalSession p = personal(c, P_APP);
        LockSession stopped = new LockSession(false, c.wall + MIN, false, pkgs(), pkgs(), "d");
        assertEquals(EnforcementPolicy.Mode.PERSONAL, EnforcementPolicy.resolve(stopped, "stu1", p, c).mode);
        LockSession other = new LockSession(true, c.wall + MIN, true, pkgs(), pkgs("someoneElse"), "d");
        assertEquals(EnforcementPolicy.Mode.PERSONAL, EnforcementPolicy.resolve(other, "stu1", p, c).mode);
        assertEquals(EnforcementPolicy.Mode.PERSONAL, EnforcementPolicy.resolve(null, "", p, c).mode);
    }

    // 10
    @Test
    public void personalCancellationCannotClearSchoolSession() {
        Rig r = new Rig();
        PersonalSession p = r.controller.start("uid1", pkgs(P_APP), 30 * MIN).session;
        LockSession school = schoolList(r.clock.wall + 60 * MIN, S_APP);
        assertEquals(PersonalSessionController.CancelResult.CANCELLED, r.controller.cancel("uid1", p.id));
        EnforcementPolicy pol = EnforcementPolicy.resolve(school, "stu1", r.controller.current(), r.clock);
        assertEquals(EnforcementPolicy.Mode.SCHOOL, pol.mode);
        assertTrue(pol.decide(S_APP, SELF, null, r.clock.wall).block);
        // and the school LockSession object itself is untouched
        assertTrue(school.active);
    }

    @Test
    public void personalPolicyBlocksOnlySelectedPackages() {
        FakeClock c = new FakeClock();
        EnforcementPolicy pol = EnforcementPolicy.resolve(null, "", personal(c, P_APP), c);
        assertEquals(EnforcementPolicy.Mode.PERSONAL, pol.mode);
        LockPolicy.Decision d = pol.decide(P_APP, SELF, null, c.wall);
        assertTrue(d.block);
        assertEquals(LockPolicy.Reason.BLOCKED_BY_LIST, d.reason);
        assertEquals(LockPolicy.Reason.NOT_IN_BLOCK_LIST, pol.decide("com.example.other", SELF, null, c.wall).reason);
        assertEquals(LockPolicy.Reason.SELF_PACKAGE, pol.decide(SELF, SELF, null, c.wall).reason);
        assertEquals(LockPolicy.Reason.UNKNOWN_PACKAGE, pol.decide(null, SELF, null, c.wall).reason);
    }

    @Test
    public void exemptionsStillWinOverPersonalSelection() {
        FakeClock c = new FakeClock();
        EnforcementPolicy pol = EnforcementPolicy.resolve(null, "", personal(c, P_APP), c);
        LockPolicy.Decision d = pol.decide(P_APP, SELF, pkg -> pkg.equals(P_APP), c.wall);
        assertFalse(d.block);
        assertEquals(LockPolicy.Reason.EXEMPT_PACKAGE, d.reason);
    }

    @Test
    public void personalPolicyExpiresByMonotonicTimeNotWallClock() {
        FakeClock c = new FakeClock();
        PersonalSession p = personal(c, P_APP);
        c.elapsed += 29 * MIN;
        c.wall += 5 * 24 * 60 * MIN;                // wall clock jumped forward
        assertEquals(EnforcementPolicy.Mode.PERSONAL, EnforcementPolicy.resolve(null, "", p, c).mode);
        c.elapsed += 2 * MIN;
        assertEquals(EnforcementPolicy.Mode.NONE, EnforcementPolicy.resolve(null, "", p, c).mode);
    }

    @Test
    public void noSessionsMeansNothingIsBlocked() {
        FakeClock c = new FakeClock();
        EnforcementPolicy pol = EnforcementPolicy.resolve(null, "", null, c);
        assertEquals(EnforcementPolicy.Mode.NONE, pol.mode);
        assertFalse(pol.isEnforcing());
        assertEquals(LockPolicy.Reason.NO_SESSION, pol.decide(P_APP, SELF, null, c.wall).reason);
    }

    @Test
    public void samePolicyIdentityOnlyWhenSameSession() {
        FakeClock c = new FakeClock();
        PersonalSession p = personal(c, P_APP);
        EnforcementPolicy a = EnforcementPolicy.resolve(null, "", p, c);
        c.advance(MIN);
        EnforcementPolicy b = EnforcementPolicy.resolve(null, "", p, c);
        assertTrue(a.sameAs(b));
        assertFalse(a.sameAs(EnforcementPolicy.NONE));
        assertFalse(a.sameAs(EnforcementPolicy.resolve(schoolAll(c.wall + MIN), "stu1", p, c)));
    }

    // 17: School/College decision logic is unchanged when routed through the shared engine
    @Test
    public void schoolBlockAllAndListBehaveExactlyAsBefore() {
        FakeClock c = new FakeClock();
        LockSession all = schoolAll(c.wall + 10 * MIN);
        EnforcementPolicy pol = EnforcementPolicy.resolve(all, "stu1", null, c);
        assertEquals(EnforcementPolicy.Mode.SCHOOL, pol.mode);
        for (String pkg : new String[]{"com.a.b", "com.c.d"}) {
            LockPolicy.Decision viaEngine = pol.decide(pkg, SELF, null, c.wall);
            LockPolicy.Decision direct = LockPolicy.decide(all, "stu1", pkg, SELF, null, c.wall);
            assertEquals(direct.block, viaEngine.block);
            assertEquals(direct.reason, viaEngine.reason);
            assertTrue(viaEngine.block);
        }
        assertEquals(LockPolicy.Reason.SELF_PACKAGE, pol.decide(SELF, SELF, null, c.wall).reason);
        assertEquals(c.wall + 10 * MIN, pol.endWallMs);
    }

    @Test
    public void schoolEligibilityStillRequiresPresence() {
        FakeClock c = new FakeClock();
        LockSession s = schoolList(c.wall + 10 * MIN, S_APP);
        assertEquals(EnforcementPolicy.Mode.NONE, EnforcementPolicy.resolve(s, "absentStudent", null, c).mode);
        assertEquals(EnforcementPolicy.Mode.NONE, EnforcementPolicy.resolve(s, "", null, c).mode);
        LockSession expired = schoolList(c.wall - 1, S_APP);
        assertEquals(EnforcementPolicy.Mode.NONE, EnforcementPolicy.resolve(expired, "stu1", null, c).mode);
        assertNull(EnforcementPolicy.NONE.lock);
        assertNotNull(EnforcementPolicy.resolve(s, "stu1", null, c).lock);
    }

    // 14: repeated accessibility events never launch duplicate block screens (personal path)
    @Test
    public void repeatedEventsForAPersonalBlockedAppLaunchOnce() {
        FakeClock c = new FakeClock();
        EnforcementPolicy pol = EnforcementPolicy.resolve(null, "", personal(c, P_APP), c);
        BlockLaunchGate gate = new BlockLaunchGate();
        int launches = 0;
        long t = 1_000;
        for (int i = 0; i < 20; i++) {                      // burst of 20 events within 100 ms
            if (pol.decide(P_APP, SELF, null, c.wall).block) {
                if (gate.onBlockedForeground(t + i * 5L) == BlockLaunchGate.Action.LAUNCH) launches++;
            }
        }
        assertEquals(1, launches);
        gate.onProtectionConfirmed();                       // block screen is in front
        assertEquals(BlockLaunchGate.Action.LAUNCH, gate.onBlockedForeground(t + 200));
        // the block screen itself (FocusLock) never counts as blocked: no recursion
        assertFalse(pol.decide(SELF, SELF, null, c.wall).block);
    }
}
