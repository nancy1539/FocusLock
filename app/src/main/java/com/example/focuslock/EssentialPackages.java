package com.example.focuslock;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Packages FocusLock must never block (or offer for blocking).
 *
 * STATIC_EXEMPT is the pre-existing School/College set, moved here unchanged so the
 * service and the app picker cannot disagree. PERSONAL_EXTRA adds packages that a person
 * could use to lock themselves out of fixing their own phone (Settings, permission and
 * installer screens); it applies to PERSONAL sessions only, so School behavior is unchanged.
 *
 * Pure Java.
 */
final class EssentialPackages {

    private EssentialPackages() {}

    static final Set<String> STATIC_EXEMPT = new HashSet<>(Arrays.asList(
            "android",                          // system dialogs, resolver/chooser
            "com.android.systemui",             // status bar, shade, recents on some builds, keyguard
            "com.android.phone",                // telephony UI incl. emergency dialer
            "com.android.server.telecom",       // telecom framework UI
            "com.android.incallui",             // in-call screen (AOSP)
            "com.android.dialer",               // AOSP dialer
            "com.google.android.dialer",        // Google dialer
            "com.samsung.android.dialer",       // Samsung dialer
            "com.samsung.android.incallui",     // Samsung in-call screen
            "com.android.emergency",            // emergency information
            "com.miui.home"                     // MIUI launcher (also found dynamically; kept for safety)
    ));

    static final Set<String> PERSONAL_EXTRA = new HashSet<>(Arrays.asList(
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.google.android.settings.intelligence",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.emergency",
            "com.google.android.apps.safetyhub"
    ));

    /**
     * System packages that are never offered in the picker even though they have a launcher
     * entry: any {@code com.android.<these>} component of the core platform.
     */
    private static final String[] SYSTEM_PREFIXES = {
            "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.server", "com.android.providers", "com.android.emergency",
            "com.android.packageinstaller", "com.android.permissioncontroller",
            "com.android.incallui", "com.android.dialer", "com.android.bluetooth",
            "com.android.nfc", "com.android.inputmethod", "com.android.shell",
            "com.android.location", "com.android.keychain", "com.android.certinstaller",
            "com.android.stk", "com.android.cellbroadcastreceiver", "com.android.managedprovisioning",
            "com.android.se", "com.android.carrierconfig", "com.android.launcher",
            "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
            "com.google.android.dialer", "com.google.android.gms", "com.google.android.setupwizard",
            "com.samsung.android.dialer", "com.samsung.android.incallui", "com.sec.android.app.launcher"
    };

    static boolean isCoreSystemPackage(String pkg) {
        if (pkg == null) {
            return true;
        }
        if (STATIC_EXEMPT.contains(pkg) || PERSONAL_EXTRA.contains(pkg)) {
            return true;
        }
        for (String prefix : SYSTEM_PREFIXES) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }
}
