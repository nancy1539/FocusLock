package com.example.focuslock;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which installed apps may be chosen for a personal session, and how a saved selection is
 * reconciled with what is installed now. Pure Java.
 */
final class AppEligibility {

    private AppEligibility() {}

    /**
     * @param launchable      the package has a launcher activity (caller queries only those)
     * @param dynamicExempt   current home launchers, default dialer and keyboard
     */
    static boolean isSelectable(String pkg, boolean launchable, String selfPackage,
                                Set<String> dynamicExempt) {
        if (!launchable || !PersonalRules.isValidPackage(pkg)) {
            return false;
        }
        if (pkg.equals(selfPackage)) {
            return false;
        }
        if (EssentialPackages.isCoreSystemPackage(pkg)) {
            return false;
        }
        return dynamicExempt == null || !dynamicExempt.contains(pkg);
    }

    static final class Reconciled {
        final Set<String> kept;
        final Set<String> removed;

        Reconciled(Set<String> kept, Set<String> removed) {
            this.kept = kept;
            this.removed = removed;
        }
    }

    /**
     * Drops saved packages that are no longer selectable (uninstalled, no launcher entry, or
     * now classified essential). An EMPTY selectable set means "could not enumerate", not
     * "nothing installed", so the saved selection is kept untouched in that case.
     */
    static Reconciled reconcile(Collection<String> saved, Set<String> selectableNow) {
        Set<String> kept = new TreeSet<>();
        Set<String> removed = new TreeSet<>();
        if (saved == null) {
            return new Reconciled(kept, removed);
        }
        for (String p : saved) {
            if (selectableNow == null || selectableNow.isEmpty() || selectableNow.contains(p)) {
                kept.add(p);
            } else {
                removed.add(p);
            }
        }
        return new Reconciled(kept, removed);
    }
}
