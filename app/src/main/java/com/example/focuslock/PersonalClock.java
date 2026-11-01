package com.example.focuslock;

/**
 * Time source for personal sessions, injected so the logic is deterministic in unit tests.
 *
 *  wallMs()    System.currentTimeMillis(): can be changed by the user or the network.
 *  elapsedMs() SystemClock.elapsedRealtime(): monotonic, keeps counting in deep sleep,
 *              resets to ~0 on reboot.
 *  bootCount() Settings.Global.BOOT_COUNT, or -1 when the platform does not report it.
 */
interface PersonalClock {
    long wallMs();

    long elapsedMs();

    long bootCount();
}
