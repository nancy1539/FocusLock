package com.example.focuslock;

/**
 * Pure description of what the Personal dashboard must show. UserHomeActivity only renders it,
 * so the rules (e.g. missing accessibility permission) are unit-testable.
 */
final class PersonalDashboardState {

    enum Phase { NOT_STARTED, ACTIVE, COMPLETED, ACTIVE_OTHER_ACCOUNT }

    enum Accessibility { READY, DISABLED, STARTING }

    enum StartBlocker { NONE, NOT_SIGNED_IN, OTHER_ACCOUNT_SESSION, SCHOOL_SESSION, NO_APPS, ACCESSIBILITY }

    final Phase phase;
    final Accessibility accessibility;
    final StartBlocker startBlocker;
    final boolean canEnd;
    final boolean canSelectApps;
    /** A session of this user is running but nothing can enforce it right now. */
    final boolean enforcementLost;

    private PersonalDashboardState(Phase phase, Accessibility accessibility, StartBlocker blocker,
                                   boolean canEnd, boolean canSelectApps, boolean enforcementLost) {
        this.phase = phase;
        this.accessibility = accessibility;
        this.startBlocker = blocker;
        this.canEnd = canEnd;
        this.canSelectApps = canSelectApps;
        this.enforcementLost = enforcementLost;
    }

    static PersonalDashboardState compute(String uid, PersonalSession running,
                                          PersonalSession lastFinished,
                                          boolean a11yEnabled, boolean a11yConnected,
                                          int selectedCount, boolean schoolActive) {
        Accessibility a = !a11yEnabled ? Accessibility.DISABLED
                : (a11yConnected ? Accessibility.READY : Accessibility.STARTING);

        Phase phase;
        if (running != null) {
            phase = running.isOwnedBy(uid) ? Phase.ACTIVE : Phase.ACTIVE_OTHER_ACCOUNT;
        } else if (lastFinished != null) {
            phase = Phase.COMPLETED;
        } else {
            phase = Phase.NOT_STARTED;
        }

        StartBlocker blocker;
        if (uid == null || uid.isEmpty()) {
            blocker = StartBlocker.NOT_SIGNED_IN;
        } else if (phase == Phase.ACTIVE_OTHER_ACCOUNT) {
            blocker = StartBlocker.OTHER_ACCOUNT_SESSION;
        } else if (schoolActive) {
            blocker = StartBlocker.SCHOOL_SESSION;
        } else if (selectedCount <= 0) {
            blocker = StartBlocker.NO_APPS;
        } else if (a != Accessibility.READY) {
            blocker = StartBlocker.ACCESSIBILITY;
        } else {
            blocker = StartBlocker.NONE;
        }

        boolean active = phase == Phase.ACTIVE;
        return new PersonalDashboardState(phase, a, blocker, active,
                phase != Phase.ACTIVE && phase != Phase.ACTIVE_OTHER_ACCOUNT,
                active && a != Accessibility.READY);
    }
}
