package com.example.focuslock;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory fakes shared by the personal-mode tests. */
final class PersonalTestSupport {

    private PersonalTestSupport() {}

    static final class FakeClock implements PersonalClock {
        long wall = 1_700_000_000_000L;
        long elapsed = 10_000_000L;
        long boot = 5L;

        @Override public long wallMs() { return wall; }
        @Override public long elapsedMs() { return elapsed; }
        @Override public long bootCount() { return boot; }

        /** Time passes normally: both clocks advance. */
        void advance(long ms) { wall += ms; elapsed += ms; }

        void reboot(long msOff) { boot++; elapsed = 3_000L; wall += msOff; }
    }

    static final class MemoryStore implements KeyValueStore {
        final Map<String, String> data = new HashMap<>();
        boolean failWrites = false;
        int writes = 0;

        @Override public String get(String key) { return data.get(key); }

        @Override public boolean apply(Map<String, String> puts, Collection<String> removes) {
            if (failWrites) return false;
            writes++;
            data.putAll(puts);
            for (String r : removes) data.remove(r);
            return true;
        }
    }

    static final class FakeEnv implements PersonalSessionController.Environment {
        boolean a11y = true;
        boolean school = false;
        @Override public boolean accessibilityReady() { return a11y; }
        @Override public boolean schoolSessionActive() { return school; }
    }

    static final class Rig {
        final FakeClock clock = new FakeClock();
        final MemoryStore store = new MemoryStore();
        final FakeEnv env = new FakeEnv();
        int idCounter = 0;
        PersonalSessionController controller = build(null);

        PersonalSessionController build(PersonalSessionController.CancelPolicy policy) {
            return new PersonalSessionController(new PersonalSessionRepository(store), clock, env,
                    policy, () -> "sess-" + (++idCounter));
        }

        /** A fresh controller over the same storage = process restart. */
        PersonalSessionController reload() { return build(null); }
    }

    static Set<String> pkgs(String... p) {
        Set<String> s = new HashSet<>();
        for (String x : p) s.add(x);
        return s;
    }

    static final long MIN = 60_000L;
}
