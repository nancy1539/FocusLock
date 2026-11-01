package com.example.focuslock;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Device-specific packages that must never be blocked: every home launcher, the default
 * dialer and the default keyboard. Shared by BlockerService (enforcement) and the app picker
 * (selection) so both always use the same definition. Moved verbatim from BlockerService.
 */
final class DeviceExemptions {

    private static final String TAG = "FocusLockDebug";

    private DeviceExemptions() {}

    /** Every installed home launcher (a launcher cannot open a blocked app by itself). */
    static Set<String> homePackages(Context context) {
        Set<String> out = new HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> infos = context.getPackageManager().queryIntentActivities(
                    home, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo ri : infos) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    out.add(ri.activityInfo.packageName);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate home launchers", e);
        }
        return out;
    }

    static String defaultDialer(Context context) {
        try {
            TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) {
                return "";
            }
            String dialer = tm.getDefaultDialerPackage();
            return dialer == null ? "" : dialer;
        } catch (Exception e) {
            Log.w(TAG, "Could not determine default dialer", e);
            return "";
        }
    }

    static String defaultKeyboard(Context context) {
        try {
            String inputMethod = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (inputMethod == null) {
                return "";
            }
            int slash = inputMethod.indexOf('/');
            return slash > 0 ? inputMethod.substring(0, slash) : inputMethod;
        } catch (Exception e) {
            Log.w(TAG, "Could not determine default keyboard", e);
            return "";
        }
    }

    /** All three dynamic exemptions as one set (for the picker). */
    static Set<String> all(Context context) {
        Set<String> out = homePackages(context);
        String d = defaultDialer(context);
        if (!d.isEmpty()) {
            out.add(d);
        }
        String k = defaultKeyboard(context);
        if (!k.isEmpty()) {
            out.add(k);
        }
        return out;
    }
}
