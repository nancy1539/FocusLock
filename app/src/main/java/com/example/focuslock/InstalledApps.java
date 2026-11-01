package com.example.focuslock;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Enumerates the apps a personal session may block. Uses the launcher-intent query declared in
 * the manifest (no QUERY_ALL_PACKAGES). All methods are blocking: call them off the main thread.
 */
final class InstalledApps {

    private static final String TAG = "FocusLockDebug";

    private InstalledApps() {}

    static final class AppEntry {
        final String packageName;
        final String label;

        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    @SuppressWarnings("deprecation") // the flags overload only exists from API 33
    private static List<ResolveInfo> launchables(PackageManager pm) {
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0L));
        }
        return pm.queryIntentActivities(main, 0);
    }

    /** Selectable packages only (no labels: cheap enough for the dashboard). */
    static Set<String> selectablePackages(Context context) {
        Set<String> out = new TreeSet<>();
        for (AppEntry e : selectable(context, false)) {
            out.add(e.packageName);
        }
        return out;
    }

    /** Selectable apps with labels, sorted by label. */
    static List<AppEntry> selectableWithLabels(Context context) {
        List<AppEntry> list = selectable(context, true);
        final Collator collator = Collator.getInstance();
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int c = collator.compare(a.label, b.label);
                return c != 0 ? c : a.packageName.compareTo(b.packageName);
            }
        });
        return list;
    }

    private static List<AppEntry> selectable(Context context, boolean withLabels) {
        List<AppEntry> out = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Set<String> dynamic = DeviceExemptions.all(context);
            String self = context.getPackageName();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : launchables(pm)) {
                if (ri.activityInfo == null || ri.activityInfo.packageName == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (!seen.add(pkg) || !AppEligibility.isSelectable(pkg, true, self, dynamic)) {
                    continue;
                }
                String label = pkg;
                if (withLabels) {
                    try {
                        CharSequence cs = ri.loadLabel(pm);
                        if (cs != null && cs.toString().trim().length() > 0) {
                            label = cs.toString().trim();
                        }
                    } catch (RuntimeException e) {
                        // keep the package name as label
                    }
                }
                out.add(new AppEntry(pkg, label));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not enumerate installed apps", e);
            return new ArrayList<>(); // callers treat "empty" as "unknown", never as "uninstalled"
        }
        return out;
    }

    /** Display names for specific packages; a package that cannot be resolved maps to null. */
    @SuppressWarnings("deprecation") // getApplicationInfo(String,int) works on every API level
    static Map<String, String> labelsFor(Context context, Set<String> packages) {
        Map<String, String> out = new HashMap<>();
        PackageManager pm = context.getPackageManager();
        for (String pkg : packages) {
            String label = null;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                // not installed (or not visible): reported as unavailable
            }
            out.put(pkg, label);
        }
        return out;
    }
}
