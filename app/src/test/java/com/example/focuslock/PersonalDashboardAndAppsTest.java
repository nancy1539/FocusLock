package com.example.focuslock;

import static com.example.focuslock.PersonalTestSupport.MIN;
import static com.example.focuslock.PersonalTestSupport.pkgs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.focuslock.PersonalDashboardState.Accessibility;
import com.example.focuslock.PersonalDashboardState.Phase;
import com.example.focuslock.PersonalDashboardState.StartBlocker;
import com.example.focuslock.PersonalTestSupport.FakeClock;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class PersonalDashboardAndAppsTest {

    private static PersonalSession running(FakeClock c, String uid) {
        return PersonalSession.startNew("s1", uid, pkgs("com.example.a"), 25 * MIN, c);
    }

    // 16
    @Test
    public void missingAccessibilityPermissionBlocksStartAndSaysSo() {
        PersonalDashboardState s = PersonalDashboardState.compute("u1", null, null, false, false, 2, false);
        assertEquals(Accessibility.DISABLED, s.accessibility);
        assertEquals(StartBlocker.ACCESSIBILITY, s.startBlocker);
        assertEquals(Phase.NOT_STARTED, s.phase);
        assertFalse(s.canEnd);
    }

    @Test
    public void enabledButNotYetConnectedIsReportedAsStarting() {
        PersonalDashboardState s = PersonalDashboardState.compute("u1", null, null, true, false, 2, false);
        assertEquals(Accessibility.STARTING, s.accessibility);
        assertEquals(StartBlocker.ACCESSIBILITY, s.startBlocker);
    }

    @Test
    public void readyStateAllowsStart() {
        PersonalDashboardState s = PersonalDashboardState.compute("u1", null, null, true, true, 1, false);
        assertEquals(Accessibility.READY, s.accessibility);
        assertEquals(StartBlocker.NONE, s.startBlocker);
        assertTrue(s.canSelectApps);
    }

    @Test
    public void blockerPrecedence() {
        FakeClock c = new FakeClock();
        assertEquals(StartBlocker.NOT_SIGNED_IN,
                PersonalDashboardState.compute("", null, null, true, true, 3, false).startBlocker);
        assertEquals(StartBlocker.OTHER_ACCOUNT_SESSION,
                PersonalDashboardState.compute("u2", running(c, "u1"), null, true, true, 3, false).startBlocker);
        assertEquals(StartBlocker.SCHOOL_SESSION,
                PersonalDashboardState.compute("u1", null, null, true, true, 3, true).startBlocker);
        assertEquals(StartBlocker.NO_APPS,
                PersonalDashboardState.compute("u1", null, null, true, true, 0, false).startBlocker);
    }

    @Test
    public void activeOwnSessionShowsEndAndWarnsWhenEnforcementIsLost() {
        FakeClock c = new FakeClock();
        PersonalDashboardState ok = PersonalDashboardState.compute("u1", running(c, "u1"), null, true, true, 1, false);
        assertEquals(Phase.ACTIVE, ok.phase);
        assertTrue(ok.canEnd);
        assertFalse(ok.canSelectApps);
        assertFalse(ok.enforcementLost);
        PersonalDashboardState lost = PersonalDashboardState.compute("u1", running(c, "u1"), null, false, false, 1, false);
        assertTrue(lost.enforcementLost);
        assertTrue(lost.canEnd);
    }

    @Test
    public void otherAccountsSessionCannotBeEnded() {
        FakeClock c = new FakeClock();
        PersonalDashboardState s = PersonalDashboardState.compute("u2", running(c, "u1"), null, true, true, 1, false);
        assertEquals(Phase.ACTIVE_OTHER_ACCOUNT, s.phase);
        assertFalse(s.canEnd);
        assertFalse(s.canSelectApps);
    }

    @Test
    public void completedAfterSessionEnds() {
        FakeClock c = new FakeClock();
        PersonalSession done = running(c, "u1").finished(PersonalSession.Status.COMPLETED, c.wall + 1, "expired");
        assertEquals(Phase.COMPLETED,
                PersonalDashboardState.compute("u1", null, done, true, true, 1, false).phase);
    }

    // 12: uninstalled apps / eligibility
    @Test
    public void reconcileDropsUninstalledAndKeepsInstalled() {
        AppEligibility.Reconciled r = AppEligibility.reconcile(
                pkgs("com.a.one", "com.a.two", "com.a.gone"), pkgs("com.a.one", "com.a.two", "com.b.new"));
        assertEquals(pkgs("com.a.one", "com.a.two"), r.kept);
        assertEquals(pkgs("com.a.gone"), r.removed);
    }

    @Test
    public void reconcileNeverWipesSelectionWhenEnumerationFailed() {
        AppEligibility.Reconciled r = AppEligibility.reconcile(pkgs("com.a.one"), Collections.<String>emptySet());
        assertEquals(pkgs("com.a.one"), r.kept);
        assertTrue(r.removed.isEmpty());
        assertTrue(AppEligibility.reconcile(null, pkgs("com.a.one")).kept.isEmpty());
    }

    @Test
    public void selfEssentialAndDynamicExemptAppsAreNotSelectable() {
        String self = "com.example.focuslock";
        java.util.Set<String> dyn = pkgs("com.vendor.launcher", "com.vendor.keyboard");
        assertTrue(AppEligibility.isSelectable("com.instagram.android", true, self, dyn));
        assertFalse(AppEligibility.isSelectable(self, true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.android.settings", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.android.systemui", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.android.phone", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.google.android.dialer", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.android.emergency", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.android.packageinstaller", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.android.settings.intelligence", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.vendor.launcher", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.vendor.keyboard", true, self, dyn));
        assertFalse(AppEligibility.isSelectable("com.instagram.android", false, self, dyn)); // no launcher entry
        assertFalse(AppEligibility.isSelectable("android", true, self, dyn));
        assertFalse(AppEligibility.isSelectable(null, true, self, dyn));
        // a similarly-named third-party app is NOT caught by a prefix of a core package
        assertTrue(AppEligibility.isSelectable("com.android.settingsfake", true, self, dyn));
    }

    @Test
    public void schoolExemptionSetIsUnchanged() {
        // Guards the School/College behavior: the pre-existing set must keep these entries.
        for (String p : Arrays.asList("android", "com.android.systemui", "com.android.phone",
                "com.android.server.telecom", "com.android.incallui", "com.android.dialer",
                "com.google.android.dialer", "com.samsung.android.dialer",
                "com.samsung.android.incallui", "com.android.emergency", "com.miui.home")) {
            assertTrue(p, EssentialPackages.STATIC_EXEMPT.contains(p));
        }
        assertEquals(11, EssentialPackages.STATIC_EXEMPT.size());
        // Settings is exempt for PERSONAL only; School keeps blocking it under blockAll as before.
        assertFalse(EssentialPackages.STATIC_EXEMPT.contains("com.android.settings"));
        assertTrue(EssentialPackages.PERSONAL_EXTRA.contains("com.android.settings"));
    }
}
