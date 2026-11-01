package com.example.focuslock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BlockLaunchGateTest {

    private static final long W = BlockLaunchGate.PENDING_WINDOW_MS;
    private static final int MAX = BlockLaunchGate.MAX_ATTEMPTS;

    /** Runs MAX unconfirmed launches, then the detection that degrades the gate. Returns that time. */
    private static long degrade(BlockLaunchGate g, long start) {
        long t = start;
        for (int i = 0; i < MAX; i++) {
            assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(t));
            t += W;
        }
        assertEquals(BlockLaunchGate.Action.GO_HOME, g.onBlockedForeground(t));
        assertTrue(g.isDegraded());
        return t;
    }

    // ------------------------------------------------------------ normal mode

    @Test
    public void firstDetectionLaunchesImmediately() {
        BlockLaunchGate g = new BlockLaunchGate();
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(0));
        assertTrue(g.isLaunchPending(1));
        assertFalse(g.isDegraded());
    }

    @Test
    public void burstWhileLaunchInFlightIsDeduplicatedButNeverForgotten() {
        BlockLaunchGate g = new BlockLaunchGate();
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(1_000));
        assertEquals(BlockLaunchGate.Action.WAIT_FOR_PENDING, g.onBlockedForeground(1_050));
        assertEquals(BlockLaunchGate.Action.WAIT_FOR_PENDING, g.onBlockedForeground(1_200));
        long remaining = g.remainingPendingMs(1_200);
        assertTrue(remaining > 0 && remaining <= W);
        assertEquals(1, g.attempts());
    }

    @Test
    public void unconfirmedLaunchIsRetriedAfterWindow() {
        BlockLaunchGate g = new BlockLaunchGate();
        g.onBlockedForeground(0);
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(W));
        assertEquals(2, g.attempts());
    }

    @Test
    public void confirmedProtectionResetsSoQuickReopenBlocksAgainImmediately() {
        BlockLaunchGate g = new BlockLaunchGate();
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(0));
        g.onProtectionConfirmed();                     // block screen is now in front
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(100));
    }

    @Test
    public void rapidReopenCycleNeverSuppressesAConfirmedEpisode() {
        BlockLaunchGate g = new BlockLaunchGate();
        for (int i = 0; i < 20; i++) {
            long t = i * 120L;
            assertEquals("reopen #" + i, BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(t));
            g.onProtectionConfirmed();
        }
        assertFalse(g.isDegraded());
    }

    @Test
    public void failedLaunchClearsPendingButStillCountsTowardsEscalation() {
        BlockLaunchGate g = new BlockLaunchGate();
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(0));
        g.onLaunchFailed(0);
        assertFalse(g.isLaunchPending(10));
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(10));
        g.onLaunchFailed(10);
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(20));
        g.onLaunchFailed(20);
        assertEquals(BlockLaunchGate.Action.GO_HOME, g.onBlockedForeground(30));
        assertTrue(g.isDegraded());
    }

    // ---------------------------------------------------------- degraded mode

    @Test
    public void escalationDoesNotResetTheRecoveryState() {
        BlockLaunchGate g = new BlockLaunchGate();
        degrade(g, 0);
        // Old behaviour: GO_HOME called reset() and the next detection started a fresh
        // run of launches. Now the state survives.
        assertTrue(g.isDegraded());
        assertEquals(MAX, g.attempts());
        assertEquals(1, g.homeActions());
    }

    @Test
    public void afterGoHomeReopeningTheBlockedAppDoesNotRestartALaunchSequence() {
        BlockLaunchGate g = new BlockLaunchGate();
        long t = degrade(g, 0);
        // Launcher is now in front (an ALLOWED foreground: the gate is not told, and
        // must not need to be). The student reopens the blocked app 100 ms later:
        assertEquals(BlockLaunchGate.Action.GO_HOME, g.onBlockedForeground(t + 100));
        assertEquals(BlockLaunchGate.Action.GO_HOME, g.onBlockedForeground(t + 200));
        assertEquals(0, g.totalLaunches() - MAX);
    }

    @Test
    public void everyDetectionInDegradedModeIsProtectedNothingIsIgnored() {
        BlockLaunchGate g = new BlockLaunchGate();
        long t = degrade(g, 0);
        long probeAt = t + BlockLaunchGate.PROBE_BACKOFF_MIN_MS;
        int homes = 0;
        for (long now = t + 10; now < probeAt; now += 10) {
            // No WAIT_FOR_PENDING, no "ignored": each detection sends the user Home.
            assertEquals("t=" + now, BlockLaunchGate.Action.GO_HOME, g.onBlockedForeground(now));
            homes++;
        }
        assertTrue(homes > 100);
    }

    @Test
    public void probeBackoffDoublesAndOnlyDelaysProbesNeverHome() {
        BlockLaunchGate g = new BlockLaunchGate();
        long t = degrade(g, 0);
        long backoff = BlockLaunchGate.PROBE_BACKOFF_MIN_MS;
        long nextProbe = t + backoff;

        for (int round = 0; round < 8; round++) {
            assertEquals("just before probe, round " + round,
                    BlockLaunchGate.Action.GO_HOME, g.onBlockedForeground(nextProbe - 1));
            assertEquals("probe due, round " + round,
                    BlockLaunchGate.Action.PROBE, g.onBlockedForeground(nextProbe));
            // Probe in flight: bounded wait only.
            assertEquals(BlockLaunchGate.Action.WAIT_FOR_PENDING,
                    g.onBlockedForeground(nextProbe + W - 1));
            // Probe did not take over: the next detection after the window is Home, and the
            // next probe is pushed further out.
            long failAt = nextProbe + W;
            assertEquals(BlockLaunchGate.Action.GO_HOME, g.onBlockedForeground(failAt));
            backoff = Math.min(backoff * 2, BlockLaunchGate.PROBE_BACKOFF_MAX_MS);
            nextProbe = failAt + backoff;
            assertEquals(round + 1, g.probeFailures());
            assertEquals(nextProbe - failAt, g.untilNextProbeMs(failAt));
        }
        assertEquals(BlockLaunchGate.PROBE_BACKOFF_MAX_MS, backoff);
    }

    @Test
    public void failedProbeLaunchPushesTheNextProbeOut() {
        BlockLaunchGate g = new BlockLaunchGate();
        long t = degrade(g, 0);
        long probeAt = t + BlockLaunchGate.PROBE_BACKOFF_MIN_MS;
        assertEquals(BlockLaunchGate.Action.PROBE, g.onBlockedForeground(probeAt));
        g.onLaunchFailed(probeAt);                          // startActivity threw
        assertFalse(g.isLaunchPending(probeAt + 1));
        assertEquals(1, g.probeFailures());
        assertEquals(BlockLaunchGate.Action.GO_HOME, g.onBlockedForeground(probeAt + 1));
        assertEquals(BlockLaunchGate.Action.PROBE,
                g.onBlockedForeground(probeAt + 2L * BlockLaunchGate.PROBE_BACKOFF_MIN_MS));
    }

    @Test
    public void confirmedBlockScreenEndsRecoveryCompletely() {
        BlockLaunchGate g = new BlockLaunchGate();
        long t = degrade(g, 0);
        long probeAt = t + BlockLaunchGate.PROBE_BACKOFF_MIN_MS;
        assertEquals(BlockLaunchGate.Action.PROBE, g.onBlockedForeground(probeAt));
        g.onProtectionConfirmed();                          // probe worked
        assertFalse(g.isDegraded());
        assertEquals(0, g.attempts());
        assertEquals(0, g.probeFailures());
        assertEquals(0, g.homeActions());
        assertEquals(0L, g.untilNextProbeMs(probeAt + 1));
        assertEquals(BlockLaunchGate.Action.LAUNCH, g.onBlockedForeground(probeAt + 10));
    }

    @Test
    public void sessionEndResetsDegradedStateAndBackoff() {
        BlockLaunchGate g = new BlockLaunchGate();
        long t = degrade(g, 0);
        g.onBlockedForeground(t + BlockLaunchGate.PROBE_BACKOFF_MIN_MS);      // probe
        g.onBlockedForeground(t + BlockLaunchGate.PROBE_BACKOFF_MIN_MS + W);  // probe failed
        assertTrue(g.probeFailures() > 0);

        g.reset();                                          // session ended / changed

        assertFalse(g.isDegraded());
        assertEquals(0, g.attempts());
        assertEquals(0, g.probeFailures());
        assertEquals(0, g.homeActions());
        assertEquals(0, g.totalLaunches());
        assertFalse(g.isLaunchPending(t + 10_000));
        // Fresh session: launching is normal again and the first back-off is the minimum
        // (not the doubled value reached before the reset).
        long t2 = degrade(g, t + 20_000);
        assertEquals(BlockLaunchGate.PROBE_BACKOFF_MIN_MS, g.untilNextProbeMs(t2));
    }

    // ------------------------------------------------------- global properties

    @Test
    public void unconfirmedRunForFiveMinutesIsBoundedAndNeverLeavesAGap() {
        BlockLaunchGate g = new BlockLaunchGate();
        long lastLaunchAt = Long.MIN_VALUE;
        int waits = 0, launches = 0, homes = 0;
        final long step = 10;
        for (long now = 0; now < 5 * 60_000L; now += step) {
            BlockLaunchGate.Action a = g.onBlockedForeground(now);
            switch (a) {
                case LAUNCH:
                case PROBE:
                    lastLaunchAt = now;
                    launches++;
                    break;
                case WAIT_FOR_PENDING:
                    // The ONLY allowed pause: a launch that is genuinely in flight.
                    assertTrue("wait without a launch in flight at t=" + now,
                            now - lastLaunchAt < W);
                    waits++;
                    break;
                case GO_HOME:
                    homes++;
                    break;
            }
        }
        // Launch churn is bounded (3 normal + a handful of back-off probes), not one per detection.
        assertTrue("launches=" + launches, launches <= MAX + 10);
        // Exposure from waiting is at most one launch window per launch.
        assertTrue(waits <= launches * (W / step));
        // Everything else was protective.
        assertTrue(homes > 25_000);
    }

    @Test
    public void describeReportsModeAndCounters() {
        BlockLaunchGate g = new BlockLaunchGate();
        assertTrue(g.describe(0).contains("mode=NORMAL"));
        long t = degrade(g, 0);
        String d = g.describe(t);
        assertTrue(d, d.contains("mode=DEGRADED"));
        assertTrue(d, d.contains("attempts=3/3"));
        assertTrue(d, d.contains("homes=1"));
        assertTrue(d, d.contains("nextProbeIn=2000ms"));
    }

    @Test
    public void resetClearsEverything() {
        BlockLaunchGate g = new BlockLaunchGate();
        g.onBlockedForeground(0);
        g.reset();
        assertEquals(0, g.attempts());
        assertFalse(g.isLaunchPending(1));
        assertEquals(0L, g.remainingPendingMs(1));
    }
}
