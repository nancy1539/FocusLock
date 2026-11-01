package com.example.focuslock;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/** Real clocks for PersonalClock. */
final class AndroidPersonalClock implements PersonalClock {

    private final Context appContext;

    AndroidPersonalClock(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public long wallMs() {
        return System.currentTimeMillis();
    }

    @Override
    public long elapsedMs() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public long bootCount() {
        try {
            // Settings.Global.BOOT_COUNT exists since API 24 (our minSdk); -1 if unavailable.
            return Settings.Global.getInt(appContext.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}
