package com.example.focuslock;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

/** Whether FocusLock's accessibility service is switched on, and whether it is running. */
final class AccessibilityStatus {

    private AccessibilityStatus() {}

    /** Enabled in Android settings (the user's switch). Says nothing about the process. */
    static boolean isEnabled(Context context) {
        try {
            int on = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (on != 1) {
                return false;
            }
            String list = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(list)) {
                return false;
            }
            ComponentName mine = new ComponentName(context, BlockerService.class);
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(list);
            while (splitter.hasNext()) {
                ComponentName c = ComponentName.unflattenFromString(splitter.next());
                if (mine.equals(c)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // treated as "not enabled"
        }
        return false;
    }

    /** Enabled AND the service instance is connected in this process: enforcement can run. */
    static boolean isReady(Context context) {
        return isEnabled(context) && BlockerService.isConnected();
    }
}
