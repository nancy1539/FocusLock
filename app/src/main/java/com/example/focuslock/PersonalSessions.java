package com.example.focuslock;

import android.content.Context;

/** Wires the pure personal-session logic to Android (storage, clocks, accessibility, School). */
final class PersonalSessions {

    private PersonalSessions() {}

    static PersonalSessionController controller(Context context) {
        final Context app = context.getApplicationContext();
        PersonalSessionController.Environment env = new PersonalSessionController.Environment() {
            @Override
            public boolean accessibilityReady() {
                return AccessibilityStatus.isReady(app);
            }

            @Override
            public boolean schoolSessionActive() {
                return SchoolSessionProbe.isActive(app);
            }
        };
        return new PersonalSessionController(
                new PersonalSessionRepository(new PrefsKeyValueStore(app)),
                new AndroidPersonalClock(app), env,
                PersonalSessionController.ALWAYS_ALLOW_CANCEL, null);
    }
}
