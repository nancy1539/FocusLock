package com.example.focuslock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforces the effective lock policy: the teacher's School/College session or, when none applies,
 * the Personal User's own focus session (see EnforcementPolicy: School always wins).
 *
 * Responsibilities (see docs/ENFORCEMENT_DESIGN.md):
 *  1. Session validation / eligibility / package classification -> LockPolicy
 *  2. Foreground detection                                      -> detectForeground()
 *  3. Blocking decision                                         -> evaluate()
 *  4. Block-screen launch + de-duplication                      -> enforce() + BlockLaunchGate
 *  5. Persistence + Firestore                                   -> SessionStore, ensureListener()
 *  6. Recovery / lifecycle                                      -> poll, verify, screen receiver
 *
 * Everything runs on the main thread (accessibility callbacks, Firestore
 * callbacks and the Handler all use the main looper), so no locking is needed.
 */
public class BlockerService extends AccessibilityService {

    private static final String TAG = "FocusLockDebug";

    /** Safety-net re-check interval while a session is enforced and the screen is on. */
    static final long POLL_INTERVAL_MS = 1000L;
    /** Collapses bursts of TYPE_WINDOWS_CHANGED into one evaluation. */
    private static final long WINDOWS_DEBOUNCE_MS = 60L;
    /** Re-check delay when the event package and the window snapshot disagree. */
    private static final long MISMATCH_VERIFY_MS = 150L;
    private static final long REATTACH_MIN_MS = 2_000L;
    private static final long REATTACH_MAX_MS = 60_000L;
    private static final long MAX_TIMER_MS = 24L * 60L * 60L * 1000L;

    /**
     * Packages that must never be blocked live in EssentialPackages.STATIC_EXEMPT (shared with
     * the app picker). Dynamic exemptions (every installed home launcher, the default dialer
     * and the default keyboard) come from DeviceExemptions, see {@link #isExempt}.
     */

    /** The live, connected service instance (main thread), used by the UI to nudge/inspect it. */
    private static volatile WeakReference<BlockerService> instanceRef = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BlockLaunchGate gate = new BlockLaunchGate();
    private SessionStore store;

    // Firestore listener state
    private ListenerRegistration listener;
    private int listenerGeneration = 0;
    private String attachedKey = "";
    private String studentId = "";
    private long reattachBackoffMs = REATTACH_MIN_MS;
    private long nextAttachAllowedAt = 0L; // SystemClock.elapsedRealtime()

    /** Last valid School session (null = none / unavailable). */
    private LockSession session;

    // Personal mode. The policy is re-resolved at the start of every evaluate().
    private PersonalSessionController personal;
    private PersonalClock personalClock;
    private EnforcementPolicy policy = EnforcementPolicy.NONE;

    private boolean windowsEvalPending = false;
    /** False when the service was not granted window access; polling is pointless then. */
    private boolean windowInfoAvailable = true;
    private BroadcastReceiver screenReceiver;

    // Log de-duplication so a steady state does not flood Logcat.
    private String lastAllowKey = "";
    private String lastIdentityProblem = "";
    private String lastDeferredLog = "";
    private String lastUnknownFgLog = "";
    private String lastMultiWindowLog = "";

    private final Runnable pollRunnable = () -> evaluate("poll", null);
    private final Runnable verifyRunnable = () -> evaluate("verify", null);
    private final Runnable windowsRunnable = () -> {
        windowsEvalPending = false;
        evaluate("windows_changed", null);
    };
    private final Runnable reattachRunnable = this::ensureListener;
    private final Runnable expiryRunnable = () -> {
        evaluate("expiry_timer", null);
        scheduleExpiryTimer(); // no-op if the session just ended; re-arms if capped/early
    };

    private final LockPolicy.Exemptions exemptions = this::isExempt;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        boolean interactiveWindows = info != null
                && (info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0;
        boolean contentCapability = info != null
                && (info.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0;

        Log.i(TAG, "SERVICE CONNECTED instance=" + System.identityHashCode(this)
                + " sdk=" + Build.VERSION.SDK_INT
                + " interactiveWindows=" + interactiveWindows
                + " windowContentCapability=" + contentCapability);
        // If the service info is not readable yet, assume the XML config applies.
        windowInfoAvailable = info == null || (interactiveWindows && contentCapability);
        windowsEvalPending = false;
        if (!windowInfoAvailable) {
            Log.w(TAG, "Window information is NOT available; foreground detection falls back"
                    + " to window-state events only (polling disabled: it could not help).");
        }

        store = new SessionStore(this);
        personal = PersonalSessions.controller(this);
        personalClock = personal.clock();
        policy = EnforcementPolicy.NONE;
        instanceRef = new WeakReference<>(this);
        registerScreenReceiver();
        ensureListener();      // restores any persisted session, then attaches Firestore
        scheduleExpiryTimer();
        evaluate("service_connected", null);
    }

    /** True while this process has a connected service instance (enabled AND running). */
    static boolean isConnected() {
        return instanceRef.get() != null;
    }

    /**
     * Called by the UI right after a personal session was started or ended, so enforcement is
     * applied immediately instead of at the next accessibility event. No-op when the service is
     * not connected (the policy is then applied when it connects).
     */
    static void notifyPolicyChanged() {
        final BlockerService svc = instanceRef.get();
        if (svc != null) {
            svc.handler.post(new Runnable() {
                @Override
                public void run() {
                    svc.scheduleExpiryTimer();
                    svc.evaluate("personal_policy_changed", null);
                }
            });
        }
    }

    private void releaseInstance() {
        if (instanceRef.get() == this) {
            instanceRef = new WeakReference<>(null);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.w(TAG, "SERVICE UNBOUND instance=" + System.identityHashCode(this)
                + " (disabled in Settings, force-stopped, or being rebound)");
        releaseInstance();
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
        // The system asks the service to stop feedback. This does not mean the
        // service is dead; enforcement state is kept.
        Log.w(TAG, "SERVICE INTERRUPTED instance=" + System.identityHashCode(this));
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "SERVICE DESTROYED instance=" + System.identityHashCode(this)
                + " (persisted session is kept so it can be restored)");

        releaseInstance();
        handler.removeCallbacksAndMessages(null);
        detachListener();
        attachedKey = "";

        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
                // not registered
            }
            screenReceiver = null;
        }
        super.onDestroy();
    }

    private void registerScreenReceiver() {
        if (screenReceiver != null) {
            return;
        }
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    handler.removeCallbacks(pollRunnable);
                    Log.d(TAG, "Screen off: polling paused");
                } else if (action != null) {
                    evaluate("screen:" + action, null);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, filter); // system-only broadcasts: no export flag needed
    }

    // ------------------------------------------------------------ Firestore/session

    /**
     * Idempotent. Makes sure exactly one listener is attached for the current
     * student identity, restoring persisted state when the identity is new to
     * this service instance.
     */
    private void ensureListener() {
        if (store == null) {
            store = new SessionStore(this);
        }

        SharedPreferences sp = getSharedPreferences("focus", MODE_PRIVATE);
        String code = sp.getString("schoolCode", "");
        String classId = sp.getString("classId", "");
        String sid = sp.getString("studentId", "");

        if (isEmpty(code) || isEmpty(classId) || isEmpty(sid)) {
            String problem = "missing identity (schoolCode/classId/studentId); "
                    + "service idle until a student logs in on this device";
            if (!problem.equals(lastIdentityProblem)) {
                lastIdentityProblem = problem;
                Log.w(TAG, problem);
            }
            return;
        }
        lastIdentityProblem = "";

        String key = code + "/" + classId + "/" + sid;

        if (!key.equals(attachedKey)) {
            // First run of this service instance, or a different student/class logged in.
            detachListener();
            attachedKey = key;
            studentId = sid;
            gate.reset();
            reattachBackoffMs = REATTACH_MIN_MS;
            nextAttachAllowedAt = 0L;

            session = store.load(key, System.currentTimeMillis());
            if (session != null) {
                Log.i(TAG, "RESTORED persisted session: " + session
                        + " eligible=" + session.includesStudent(studentId));
                scheduleExpiryTimer();
            } else {
                Log.i(TAG, "No valid persisted session to restore");
            }
        }

        if (listener != null) {
            return;
        }

        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed < nextAttachAllowedAt) {
            String msg = "listener re-attach deferred " + (nextAttachAllowedAt - nowElapsed)
                    + "ms (backoff after error); keeping last session state";
            if (!msg.equals(lastDeferredLog)) {
                lastDeferredLog = msg;
                Log.d(TAG, msg);
            }
            return;
        }
        lastDeferredLog = "";

        attachListener(code, classId);
    }

    private void attachListener(String code, String classId) {
        final int generation = ++listenerGeneration;
        Log.i(TAG, "Attaching Firestore listener (generation=" + generation
                + ", backoff=" + reattachBackoffMs + "ms)");
        try {
            listener = FirebaseFirestore.getInstance()
                    .collection("schools").document(code)
                    .collection("classes").document(classId)
                    .collection("session").document("current")
                    .addSnapshotListener(MetadataChanges.INCLUDE, (doc, error) -> {
                        if (generation != listenerGeneration) {
                            return; // callback from a listener we already replaced
                        }
                        if (error != null) {
                            onListenerError(error);
                        } else if (doc != null) {
                            onSessionSnapshot(doc);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Could not attach Firestore listener", e);
            listener = null;
            scheduleReattach();
        }
    }

    private void detachListener() {
        listenerGeneration++; // invalidates callbacks of the old listener
        if (listener != null) {
            listener.remove();
            listener = null;
        }
    }

    /**
     * A Firestore snapshot listener is TERMINATED by the SDK after an error
     * (e.g. PERMISSION_DENIED). The old code kept the dead registration and
     * never attached a new one. Here the last valid session is kept (a network
     * error is not a stop command) and a fresh listener is attached with
     * exponential backoff.
     */
    private void onListenerError(FirebaseFirestoreException error) {
        Log.e(TAG, "FIRESTORE LISTENER ERROR code=" + error.getCode()
                + " message=" + error.getMessage()
                + " -> keeping last valid session " + describeSession()
                + "; re-attaching in " + reattachBackoffMs + "ms");
        detachListener();
        scheduleReattach();
    }

    private void scheduleReattach() {
        nextAttachAllowedAt = SystemClock.elapsedRealtime() + reattachBackoffMs;
        handler.removeCallbacks(reattachRunnable);
        handler.postDelayed(reattachRunnable, reattachBackoffMs);
        reattachBackoffMs = Math.min(reattachBackoffMs * 2, REATTACH_MAX_MS);
    }

    private void onSessionSnapshot(DocumentSnapshot doc) {
        reattachBackoffMs = REATTACH_MIN_MS; // listener is healthy again

        boolean fromCache = doc.getMetadata().isFromCache();
        String source = fromCache ? "cache" : "server";

        if (!doc.exists()) {
            if (fromCache) {
                // Cache miss (typical right after a process restart or while offline).
                // It says nothing about the server, so it must not wipe a valid lock.
                Log.w(TAG, "Session doc absent in local cache; NOT authoritative, "
                        + "keeping state " + describeSession());
                return;
            }
            Log.i(TAG, "Server confirms: no session document");
            if (session != null) {
                endSession("session document does not exist on server");
            }
            return;
        }

        LockSession.Parsed parsed = LockSession.parse(doc.getData());
        if (parsed.session == null) {
            Log.w(TAG, "Malformed session document (" + source + "): " + parsed.error
                    + " -> update ignored, keeping state " + describeSession());
            return;
        }
        applySession(parsed.session, source);
    }

    private void applySession(LockSession incoming, String source) {
        long now = System.currentTimeMillis();

        if (!incoming.active) {
            endSession("teacher stopped the session (" + source + ")");
            return;
        }
        if (incoming.isExpired(now)) {
            endSession("session already expired (" + source + ", endTime=" + incoming.endTime + ")");
            return;
        }

        boolean changed = !incoming.equals(session);
        session = incoming;

        if (changed) {
            store.save(attachedKey, incoming);
            gate.reset();
            Log.i(TAG, "SESSION UPDATED (" + source + "): " + incoming
                    + " eligible=" + incoming.includesStudent(studentId));
            BlockActivity.updateEndTime(incoming.endTime);
        } else {
            Log.d(TAG, "Session snapshot unchanged (" + source + ")");
        }

        scheduleExpiryTimer();
        evaluate("session_update", null);
    }

    private void endSession(String reason) {
        Log.i(TAG, "SESSION ENDED: " + reason + "; was " + describeSession());
        session = null;
        store.clear(reason);
        gate.reset();
        lastAllowKey = "";
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(verifyRunnable);
        handler.removeCallbacks(expiryRunnable);
        BlockActivity.dismissIfShowing(reason);
        // A personal session may still be running underneath the School session that just
        // ended: re-arm its timer and re-evaluate (posted, so this never recurses).
        scheduleExpiryTimer();
        handler.post(new Runnable() {
            @Override
            public void run() {
                evaluate("session_ended", null);
            }
        });
    }

    private void scheduleExpiryTimer() {
        handler.removeCallbacks(expiryRunnable);
        long now = System.currentTimeMillis();
        long remaining = Long.MAX_VALUE;
        LockSession s = session;
        if (s != null) {
            remaining = Math.min(remaining, s.endTime - now);
        }
        // A running personal session also needs a prompt wake-up. Only a POSITIVE remaining
        // time is scheduled: an already-over personal session is finalised by the next
        // evaluate() (PersonalSessionController.current()), so this can never spin.
        long personalRemaining = personal == null ? -1L : personal.peekRemainingMs();
        if (personalRemaining > 0L) {
            remaining = Math.min(remaining, personalRemaining);
        }
        if (remaining == Long.MAX_VALUE) {
            return;
        }
        if (remaining <= 0L) {
            handler.post(expiryRunnable); // evaluate() will end the session
            return;
        }
        // Timers are only for prompt UI dismissal. Correctness never depends on
        // them: expiry is re-checked against the clocks on every decision.
        handler.postDelayed(expiryRunnable, Math.min(remaining + 50L, MAX_TIMER_MS));
    }

    private String describeSession() {
        return session == null ? "none" : session.toString();
    }

    // --------------------------------------------------------------- events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                // Copy what we need; never keep the event after returning.
                CharSequence p = event.getPackageName();
                evaluate("window_state", p == null ? null : p.toString());
                break;
            }
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED: {
                if ((session == null && !policy.isEnforcing()) || !isRelevantWindowsChange(event)) {
                    return;
                }
                if (!windowsEvalPending) {
                    windowsEvalPending = true;
                    handler.postDelayed(windowsRunnable, WINDOWS_DEBOUNCE_MS);
                }
                break;
            }
            default:
                // TYPE_WINDOW_CONTENT_CHANGED etc. are intentionally not subscribed:
                // very noisy and not needed to detect a foreground change.
                break;
        }
    }

    /** Only window additions/removals and active/focus changes can change the foreground. */
    private boolean isRelevantWindowsChange(AccessibilityEvent event) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return true; // getWindowChanges() is API 28+; rely on debounce below that
        }
        int changes = event.getWindowChanges();
        int relevant = AccessibilityEvent.WINDOWS_CHANGE_ADDED
                | AccessibilityEvent.WINDOWS_CHANGE_REMOVED
                | AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
                | AccessibilityEvent.WINDOWS_CHANGE_FOCUSED;
        return (changes & relevant) != 0;
    }

    // ------------------------------------------------------------ evaluation

    /**
     * The one place where "is a blocked app in front right now?" is answered.
     * Every trigger (events, windows-changed, poll, verify, screen-on, expiry
     * timer, session update, service connect) comes through here.
     *
     * @param trigger  diagnostic label
     * @param eventPkg package reported by a window-state event, or null
     */
    private void evaluate(String trigger, String eventPkg) {
        ensureListener();

        long now = System.currentTimeMillis();
        LockPolicy.SessionState state = LockPolicy.sessionState(session, studentId, now);

        if (state == LockPolicy.SessionState.EXPIRED) {
            endSession("endTime reached (" + trigger + ")");
            state = LockPolicy.SessionState.NONE;
        }

        // One normalized policy for both modes: an enforcing School session wins, otherwise a
        // running personal session, otherwise nothing.
        EnforcementPolicy previous = policy;
        policy = resolvePolicy();
        if (!previous.sameAs(policy)) {
            Log.i(TAG, "POLICY CHANGED " + previous + " -> " + policy + " trigger=" + trigger);
            gate.reset();
            lastAllowKey = "";
        }

        if (!policy.isEnforcing()) {
            // Cheap exit: no IPC while nothing is being enforced.
            gate.reset();
            handler.removeCallbacks(pollRunnable);
            handler.removeCallbacks(verifyRunnable);
            BlockActivity.dismissIfShowing("session state " + state);
            logAllow("state:" + state, "No enforcement: session state=" + state
                    + " trigger=" + trigger);
            return;
        }

        Foreground fg = detectForeground();
        String pkg = fg.pkg != null ? fg.pkg : eventPkg;
        String source = fg.pkg != null ? fg.source : (eventPkg != null ? "event-only" : "none");

        if (pkg == null) {
            String msg = "SKIP: foreground unknown (no window info and no event package),"
                    + " trigger=" + trigger + "; poll will retry";
            if (!msg.equals(lastUnknownFgLog)) {
                lastUnknownFgLog = msg;
                Log.d(TAG, msg);
            }
            schedulePoll();
            return;
        }
        lastUnknownFgLog = "";

        // The event and the window snapshot disagree: one of them is stale or
        // transient. Do not guess; look again shortly (bounded: verify runs
        // with eventPkg == null, so it can never schedule another verify).
        if (eventPkg != null && fg.pkg != null && !eventPkg.equals(fg.pkg)
                && decide(eventPkg).block) {
            Log.d(TAG, "MISMATCH event=" + eventPkg + " window=" + fg.pkg
                    + " -> re-verifying in " + MISMATCH_VERIFY_MS + "ms");
            scheduleVerify(MISMATCH_VERIFY_MS);
        }

        LockPolicy.Decision decision = decide(pkg);

        reportPassiveBlockedWindows(fg, pkg);

        if (!decision.block) {
            if (decision.reason == LockPolicy.Reason.SELF_PACKAGE
                    && BlockActivity.isBlockScreenResumed()) {
                // Proof that launching works: the block screen is resumed AND in front.
                confirmProtection(source, trigger);
            }
            // Any other allowed foreground (launcher after GO_HOME, another app,
            // FocusLock's own screens) is NOT proof and does not end recovery.
            logAllow(pkg + "|" + decision.reason,
                    "ALLOW pkg=" + pkg + " reason=" + decision.reason
                            + " src=" + source + " trigger=" + trigger
                            + (gate.isDegraded() ? " (recovery still DEGRADED)" : ""));
        } else {
            enforce(pkg, decision, source, trigger);
        }

        schedulePoll();
    }

    private LockPolicy.Decision decide(String pkg) {
        return policy.decide(pkg, getPackageName(), exemptions, System.currentTimeMillis());
    }

    private EnforcementPolicy resolvePolicy() {
        PersonalSession running = null;
        try {
            running = personal == null ? null : personal.current();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not read the personal session; treating it as none", e);
        }
        return EnforcementPolicy.resolve(session, studentId, running,
                personalClock != null ? personalClock : new AndroidPersonalClock(this));
    }

    private void logAllow(String key, String msg) {
        if (!key.equals(lastAllowKey)) {
            lastAllowKey = key;
            Log.d(TAG, msg);
        }
    }

    // ------------------------------------------------------ foreground detection

    private static final class Foreground {
        String pkg;
        String source = "none";
        /** Visible application windows that are NOT the one chosen above (diagnostics only). */
        final List<PassiveWindow> passive = new ArrayList<>();
    }

    private static final class PassiveWindow {
        final String pkg;
        final boolean pip;

        PassiveWindow(String pkg, boolean pip) {
            this.pkg = pkg;
            this.pip = pip;
        }
    }

    /**
     * Resolves the package of the window the user is interacting with, using
     * the window list (needs flagRetrieveInteractiveWindows +
     * canRetrieveWindowContent). Only the package name of a window root is
     * read; no text or view content is ever accessed.
     *
     * Order: active application window -> focused application window ->
     * active window root. Returns pkg == null when nothing is available, in
     * which case the caller falls back to the event package.
     */
    @SuppressWarnings("deprecation") // recycle() is required below API 33
    private Foreground detectForeground() {
        Foreground result = new Foreground();

        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (Exception e) {
            Log.w(TAG, "getWindows() failed", e);
        }

        if (windows != null && !windows.isEmpty()) {
            AccessibilityWindowInfo active = null;
            AccessibilityWindowInfo focused = null;
            List<AccessibilityWindowInfo> appWindows = new ArrayList<>();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue; // skip IME, system bars, overlays, split-screen divider
                }
                appWindows.add(w);
                if (active == null && w.isActive()) {
                    active = w;
                }
                if (focused == null && w.isFocused()) {
                    focused = w;
                }
            }
            AccessibilityWindowInfo chosen = active != null ? active : focused;
            if (chosen != null) {
                result.pkg = packageOfWindow(chosen);
                if (result.pkg != null) {
                    result.source = active != null ? "window.active" : "window.focused";
                }
            }
            // Diagnostics only: which OTHER application windows are visible
            // (split-screen pane, freeform window, picture-in-picture)? Package
            // names are read only when there is more than one, to keep IPC low.
            if (appWindows.size() > 1) {
                for (AccessibilityWindowInfo w : appWindows) {
                    if (w == chosen) {
                        continue;
                    }
                    String other = packageOfWindow(w);
                    if (other != null) {
                        boolean pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                && w.isInPictureInPictureMode();
                        result.passive.add(new PassiveWindow(other, pip));
                    }
                }
            }
            for (AccessibilityWindowInfo w : windows) {
                w.recycle();
            }
            if (result.pkg != null) {
                return result;
            }
        }

        // No active/focused application window (e.g. notification shade or keyguard
        // is on top): ask for whatever window is active.
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            Log.w(TAG, "getRootInActiveWindow() failed", e);
        }
        if (root != null) {
            CharSequence cs = root.getPackageName();
            root.recycle();
            if (cs != null) {
                result.pkg = cs.toString();
                result.source = "activeRoot";
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private String packageOfWindow(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) {
            return null;
        }
        CharSequence cs = root.getPackageName();
        root.recycle();
        return cs == null ? null : cs.toString();
    }

    /**
     * A blocked app that is visible but is NOT the active window (the passive
     * split-screen pane, a freeform window, picture-in-picture) is not enforced:
     * see docs/ENFORCEMENT_DESIGN.md section 7. This only makes it visible in
     * Logcat, so that a device test can show exactly what happened. De-duplicated.
     */
    private void reportPassiveBlockedWindows(Foreground fg, String chosenPkg) {
        StringBuilder sb = new StringBuilder();
        for (PassiveWindow pw : fg.passive) {
            if (!pw.pkg.equals(chosenPkg) && decide(pw.pkg).block) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(pw.pkg).append(pw.pip ? " [picture-in-picture]" : " [passive window]");
            }
        }
        String msg = sb.length() == 0 ? "" : "MULTIWINDOW blocked app visible but NOT enforced"
                + " (only the active window is): " + sb + "; active=" + chosenPkg;
        if (!msg.equals(lastMultiWindowLog)) {
            lastMultiWindowLog = msg;
            if (!msg.isEmpty()) {
                Log.w(TAG, msg);
            }
        }
    }

    // ----------------------------------------------------------- enforcement

    private void enforce(String pkg, LockPolicy.Decision decision, String source, String trigger) {
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean wasDegraded = gate.isDegraded();
        BlockLaunchGate.Action action = gate.onBlockedForeground(nowElapsed);

        switch (action) {
            case LAUNCH: {
                Log.i(TAG, "BLOCK pkg=" + pkg + " reason=" + decision.reason
                        + " src=" + source + " trigger=" + trigger
                        + " attempt=" + gate.attempts() + "/" + BlockLaunchGate.MAX_ATTEMPTS
                        + " " + gate.describe(nowElapsed));
                boolean launched = launchBlockScreen(pkg, nowElapsed);
                // Always re-check afterwards: this either confirms the block screen is
                // in front, retries a failed launch, or escalates.
                scheduleVerify(launched ? BlockLaunchGate.PENDING_WINDOW_MS + 30L : 120L);
                break;
            }
            case WAIT_FOR_PENDING: {
                long wait = gate.remainingPendingMs(nowElapsed) + 30L;
                Log.d(TAG, "SKIP duplicate launch for " + pkg
                        + ": block screen launch already in flight; re-check in " + wait + "ms"
                        + " trigger=" + trigger);
                scheduleVerify(wait);
                break;
            }
            case PROBE: {
                Log.w(TAG, "PROBE pkg=" + pkg + " trigger=" + trigger
                        + ": testing whether the block screen can take over again "
                        + gate.describe(nowElapsed));
                boolean launched = launchBlockScreen(pkg, nowElapsed);
                scheduleVerify(launched ? BlockLaunchGate.PENDING_WINDOW_MS + 30L : 120L);
                break;
            }
            case GO_HOME: {
                if (!wasDegraded && gate.isDegraded()) {
                    Log.e(TAG, "RECOVERY DEGRADED: block screen did not take over after "
                            + BlockLaunchGate.MAX_ATTEMPTS + " launches (pkg=" + pkg
                            + "). From now on every blocked detection sends the user Home;"
                            + " launch probes use back-off " + BlockLaunchGate.PROBE_BACKOFF_MIN_MS
                            + ".." + BlockLaunchGate.PROBE_BACKOFF_MAX_MS + "ms. Check"
                            + " background-activity-launch restrictions / OEM battery settings.");
                }
                boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
                Log.w(TAG, "ENFORCE HOME pkg=" + pkg + " dispatched=" + ok
                        + " trigger=" + trigger + " " + gate.describe(nowElapsed));
                scheduleVerify(300L);
                break;
            }
        }
    }

    /** The block screen is resumed and in front: launching works. Clears recovery state. */
    private void confirmProtection(String source, String trigger) {
        long now = SystemClock.elapsedRealtime();
        boolean notable = gate.isDegraded() || gate.attempts() > 1;
        String before = gate.describe(now);
        gate.onProtectionConfirmed();
        if (notable) {
            Log.i(TAG, "RECOVERY CLEARED: block screen confirmed in front (src=" + source
                    + " trigger=" + trigger + "); was " + before);
        } else {
            Log.d(TAG, "PROTECTED: block screen confirmed in front (src=" + source + ")");
        }
    }

    private boolean launchBlockScreen(String blockedPkg, long nowElapsed) {
        EnforcementPolicy p = policy;
        Intent intent = new Intent(this, BlockActivity.class);
        // BlockActivity is singleTask in its own task (see manifest), so repeated
        // launches deliver onNewIntent() to the existing instance instead of
        // stacking or recreating it.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(BlockActivity.EXTRA_END_TIME, p.isEnforcing() ? p.endWallMs : 0L);
        intent.putExtra(BlockActivity.EXTRA_PERSONAL, p.mode == EnforcementPolicy.Mode.PERSONAL);
        intent.putExtra(BlockActivity.EXTRA_BLOCKED_PKG, blockedPkg);

        try {
            startActivity(intent);
            Log.d(TAG, "BlockActivity launch requested for " + blockedPkg);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "BlockActivity launch FAILED for " + blockedPkg, e);
            gate.onLaunchFailed(nowElapsed);
            return false;
        }
    }

    private void scheduleVerify(long delayMs) {
        handler.removeCallbacks(verifyRunnable);
        handler.postDelayed(verifyRunnable, delayMs);
    }

    /**
     * Safety net for missed events. One callback at most is ever pending (it is
     * removed before being re-posted), it only exists while a session is being
     * enforced and the screen is interactive, and it goes through the same
     * evaluate()/gate path as every other trigger, so it cannot cause
     * duplicate launches.
     */
    private void schedulePoll() {
        handler.removeCallbacks(pollRunnable);
        if (!windowInfoAvailable) {
            return; // cannot observe the foreground without window access
        }
        if (!isScreenInteractive()) {
            return; // ACTION_SCREEN_ON / USER_PRESENT will trigger a fresh evaluate()
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private boolean isScreenInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    // ------------------------------------------------------------ exemptions

    /** Called by LockPolicy only for packages that would otherwise be blocked. */
    private boolean isExempt(String pkg) {
        return EssentialPackages.STATIC_EXEMPT.contains(pkg)
                || (policy.mode == EnforcementPolicy.Mode.PERSONAL
                        && EssentialPackages.PERSONAL_EXTRA.contains(pkg))
                || DeviceExemptions.homePackages(this).contains(pkg)
                || pkg.equals(DeviceExemptions.defaultDialer(this))
                || pkg.equals(DeviceExemptions.defaultKeyboard(this));
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
