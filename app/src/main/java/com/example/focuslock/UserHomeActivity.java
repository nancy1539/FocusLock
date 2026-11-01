package com.example.focuslock;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.chip.ChipGroup;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Personal User dashboard: pick apps, choose a length, start/end a focus session and watch it.
 *
 * Everything shown is derived from persisted state (PersonalSessionController), never from a
 * counter held by this Activity: the remaining time is recomputed from the session record on
 * every tick, so rotation, process death and returning from the background are all safe. One
 * Handler callback drives the display and it is stopped in onStop(). Blocking itself is done
 * by BlockerService, not by this screen.
 */
public class UserHomeActivity extends AppCompatActivity {

    private static final String TAG = "UserHomeActivity";
    private static final long TICK_MS = 1000L;
    private static final int MAX_NAMES_SHOWN = 12;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView tvEmail, tvAccessStatus, tvAccessExplain, tvSelectedCount, tvSelectedApps,
            tvAppsEmpty, tvAppsNote, tvSessionStatus, tvOtherNotice, tvRemaining, tvActiveTimes,
            tvActiveApps, tvEnforcementWarning, tvLastSession, tvHistory;
    private Button btnOpenAccessibility, btnSelectApps, btnStart, btnEnd;
    private View layoutSetup, layoutActive, progressApps, progressBusy;
    private ChipGroup chipDuration;
    private EditText etCustom;

    private PersonalSessionController controller;
    private String uid;
    private Set<String> selection = new TreeSet<>();
    private Map<String, String> labels = new HashMap<>();
    private int removedNote = 0;
    private int selectionGeneration = 0;
    private PersonalDashboardState lastState;
    private boolean busy = false;
    private boolean leaving = false;
    private boolean ticking = false;
    private AlertDialog dialog;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) {
                return;
            }
            render();
            ui.postDelayed(this, TICK_MS);
        }
    };

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_user_home);
        Log.d(TAG, "onCreate");

        View root = findViewById(R.id.main);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        tvEmail = findViewById(R.id.tvUserEmail);
        tvAccessStatus = findViewById(R.id.tvAccessStatus);
        tvAccessExplain = findViewById(R.id.tvAccessExplain);
        tvSelectedCount = findViewById(R.id.tvSelectedCount);
        tvSelectedApps = findViewById(R.id.tvSelectedApps);
        tvAppsEmpty = findViewById(R.id.tvAppsEmpty);
        tvAppsNote = findViewById(R.id.tvAppsNote);
        tvSessionStatus = findViewById(R.id.tvSessionStatus);
        tvOtherNotice = findViewById(R.id.tvOtherNotice);
        tvRemaining = findViewById(R.id.tvRemaining);
        tvActiveTimes = findViewById(R.id.tvActiveTimes);
        tvActiveApps = findViewById(R.id.tvActiveApps);
        tvEnforcementWarning = findViewById(R.id.tvEnforcementWarning);
        tvLastSession = findViewById(R.id.tvLastSession);
        tvHistory = findViewById(R.id.tvHistory);
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility);
        btnSelectApps = findViewById(R.id.btnSelectApps);
        btnStart = findViewById(R.id.btnStartSession);
        btnEnd = findViewById(R.id.btnEndSession);
        layoutSetup = findViewById(R.id.layoutSetup);
        layoutActive = findViewById(R.id.layoutActive);
        progressApps = findViewById(R.id.progressApps);
        progressBusy = findViewById(R.id.progressBusy);
        chipDuration = findViewById(R.id.chipDuration);
        etCustom = findViewById(R.id.etCustomMinutes);

        etCustom.setHint(getString(R.string.user_home_custom_hint,
                PersonalRules.MIN_MINUTES, PersonalRules.MAX_MINUTES));
        chipDuration.setOnCheckedStateChangeListener((group, ids) -> updateCustomVisibility());
        btnOpenAccessibility.setOnClickListener(v -> showAccessibilityDialog());
        btnSelectApps.setOnClickListener(v -> openPicker());
        btnStart.setOnClickListener(v -> onStartClicked());
        btnEnd.setOnClickListener(v -> confirmEnd());
        findViewById(R.id.btnLogout).setOnClickListener(v -> logout());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!bindUser()) {
            return;
        }
        controller = PersonalSessions.controller(this);
        loadSelectionAsync();
        ticking = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        ticking = false;
        ui.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ticking = false;
        ui.removeCallbacksAndMessages(null);
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------- user

    /** Shows the signed-in email, or sends a signed-out visitor back to the entry screen. */
    private boolean bindUser() {
        if (leaving) {
            return false;
        }
        FirebaseUser user = currentUser();
        if (user == null) {
            Log.w(TAG, "No signed-in user; returning to entry screen");
            toast(getString(R.string.user_home_msg_signed_out));
            goToEntry();
            return false;
        }
        String newUid = user.getUid();
        if (uid != null && !uid.equals(newUid)) {
            selection = new TreeSet<>(); // never carry another account's data over
            labels = new HashMap<>();
        }
        uid = newUid;
        String email = user.getEmail();
        boolean hasEmail = email != null && !email.trim().isEmpty();
        tvEmail.setText(hasEmail ? email : getString(R.string.user_home_email_unknown));
        return true;
    }

    private void logout() {
        if (leaving || busy) {
            return;
        }
        PersonalSession running = controller == null ? null : controller.current();
        long endMs = running != null && running.isOwnedBy(uid)
                ? running.effectiveEndWallMs(controller.clock()) : 0L;
        try {
            FirebaseAuth.getInstance().signOut();
        } catch (RuntimeException e) {
            Log.e(TAG, "Sign-out failed", e);
            toast("Could not log out. Please try again");
            return;
        }
        Log.i(TAG, "User logged out");
        if (endMs > 0L) {
            // Enforcement is local and keeps running; logging out is not an exit.
            toast(getString(R.string.user_home_msg_logout_active, timeOf(endMs)));
        }
        goToEntry();
    }

    private FirebaseUser currentUser() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException e) {
            Log.e(TAG, "FirebaseAuth is not available", e);
            return null;
        }
    }

    /** Back to the role-selection screen, clearing the back stack so Back cannot return here. */
    private void goToEntry() {
        if (leaving) {
            return;
        }
        leaving = true;
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // -------------------------------------------------------------- selection

    /** Loads this account's saved selection and drops apps that are no longer available. */
    private void loadSelectionAsync() {
        final String forUid = uid;
        final int gen = ++selectionGeneration;
        final PersonalSessionController c = controller;
        progressApps.setVisibility(View.VISIBLE);
        try {
            io.execute(() -> {
                Set<String> saved = c.loadSelection(forUid);
                Set<String> selectable = InstalledApps.selectablePackages(getApplicationContext());
                AppEligibility.Reconciled r = AppEligibility.reconcile(saved, selectable);
                if (!r.removed.isEmpty()) {
                    c.saveSelection(forUid, r.kept);
                }
                Set<String> wanted = new HashSet<>(r.kept);
                PersonalSession running = c.current();
                if (running != null && running.isOwnedBy(forUid)) {
                    wanted.addAll(running.packages);
                }
                Map<String, String> names = InstalledApps.labelsFor(getApplicationContext(), wanted);
                ui.post(() -> {
                    if (isDestroyed() || gen != selectionGeneration || !forUid.equals(uid)) {
                        return;
                    }
                    selection = new TreeSet<>(r.kept);
                    labels = names;
                    removedNote = r.removed.size();
                    progressApps.setVisibility(View.GONE);
                    render();
                });
            });
        } catch (RuntimeException e) { // executor already shut down
            progressApps.setVisibility(View.GONE);
        }
    }

    private void openPicker() {
        if (busy || lastState == null || !lastState.canSelectApps) {
            return;
        }
        startActivity(new Intent(this, AppPickerActivity.class));
    }

    private String nameOf(String pkg) {
        if (!labels.containsKey(pkg)) {
            return pkg;
        }
        String l = labels.get(pkg);
        return l == null ? getString(R.string.user_home_app_unavailable, pkg) : l;
    }

    private String namesOf(Set<String> packages, int max) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String p : packages) {
            if (shown >= max) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(nameOf(p));
            shown++;
        }
        if (packages.size() > shown) {
            sb.append(", ").append(getString(R.string.user_home_apps_more, packages.size() - shown));
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- render

    private void render() {
        if (controller == null || uid == null || isDestroyed()) {
            return;
        }
        PersonalSession running = controller.current();
        PersonalSession last = controller.lastSession(uid);
        boolean school = SchoolSessionProbe.isActive(this);
        PersonalDashboardState st = PersonalDashboardState.compute(uid, running, last,
                AccessibilityStatus.isEnabled(this), BlockerService.isConnected(),
                selection.size(), school);
        lastState = st;

        renderAccessibility(st);
        renderApps(st);
        renderSession(st, running, last, school);
        renderHistory();
    }

    private void renderAccessibility(PersonalDashboardState st) {
        switch (st.accessibility) {
            case READY:
                tvAccessStatus.setText(R.string.user_home_access_ready);
                break;
            case STARTING:
                tvAccessStatus.setText(R.string.user_home_access_starting);
                break;
            default:
                tvAccessStatus.setText(R.string.user_home_access_off);
                break;
        }
        boolean ready = st.accessibility == PersonalDashboardState.Accessibility.READY;
        tvAccessExplain.setVisibility(ready ? View.GONE : View.VISIBLE);
        btnOpenAccessibility.setVisibility(
                st.accessibility == PersonalDashboardState.Accessibility.DISABLED
                        ? View.VISIBLE : View.GONE);
    }

    private void renderApps(PersonalDashboardState st) {
        tvSelectedCount.setText(getString(R.string.user_home_apps_count, selection.size()));
        boolean none = selection.isEmpty();
        tvAppsEmpty.setVisibility(none ? View.VISIBLE : View.GONE);
        tvSelectedApps.setVisibility(none ? View.GONE : View.VISIBLE);
        if (!none) {
            tvSelectedApps.setText(namesOf(selection, MAX_NAMES_SHOWN));
        }
        if (removedNote > 0) {
            tvAppsNote.setText(getString(R.string.user_home_apps_changed, removedNote));
            tvAppsNote.setVisibility(View.VISIBLE);
        } else {
            tvAppsNote.setVisibility(View.GONE);
        }
        btnSelectApps.setEnabled(st.canSelectApps && !busy);
    }

    private void renderSession(PersonalDashboardState st, PersonalSession running,
                               PersonalSession last, boolean school) {
        switch (st.phase) {
            case ACTIVE:
                tvSessionStatus.setText(R.string.user_home_status_active);
                break;
            case ACTIVE_OTHER_ACCOUNT:
                tvSessionStatus.setText(R.string.user_home_status_other);
                break;
            case COMPLETED:
                tvSessionStatus.setText(R.string.user_home_status_completed);
                break;
            default:
                tvSessionStatus.setText(R.string.user_home_status_not_started);
                break;
        }

        boolean active = st.phase == PersonalDashboardState.Phase.ACTIVE;
        layoutActive.setVisibility(active ? View.VISIBLE : View.GONE);
        layoutSetup.setVisibility(active ? View.GONE : View.VISIBLE);

        if (active && running != null) {
            long remaining = running.remainingMs(controller.clock());
            tvRemaining.setText(getString(R.string.user_home_remaining,
                    PersonalRules.formatRemaining(remaining)));
            tvActiveTimes.setText(getString(R.string.user_home_times,
                    dateTimeOf(running.startWallMs),
                    timeOf(running.effectiveEndWallMs(controller.clock()))));
            tvActiveApps.setText(getString(R.string.user_home_active_apps,
                    running.packages.size(), namesOf(running.packages, MAX_NAMES_SHOWN)));
            tvEnforcementWarning.setVisibility(st.enforcementLost ? View.VISIBLE : View.GONE);
            btnEnd.setEnabled(st.canEnd && !busy);
        } else {
            if (st.phase == PersonalDashboardState.Phase.ACTIVE_OTHER_ACCOUNT && running != null) {
                tvOtherNotice.setText(getString(R.string.user_home_notice_other,
                        timeOf(running.effectiveEndWallMs(controller.clock()))));
                tvOtherNotice.setVisibility(View.VISIBLE);
            } else if (school) {
                tvOtherNotice.setText(R.string.user_home_notice_school);
                tvOtherNotice.setVisibility(View.VISIBLE);
            } else {
                tvOtherNotice.setVisibility(View.GONE);
            }
            boolean startBlockedByOther =
                    st.startBlocker == PersonalDashboardState.StartBlocker.OTHER_ACCOUNT_SESSION
                            || st.startBlocker == PersonalDashboardState.StartBlocker.SCHOOL_SESSION;
            btnStart.setEnabled(!busy && !startBlockedByOther);
            updateCustomVisibility();
        }

        if (last != null && !active) {
            boolean cancelled = last.status == PersonalSession.Status.CANCELLED;
            tvLastSession.setText(getString(
                    cancelled ? R.string.user_home_last_cancelled : R.string.user_home_last_completed,
                    dateTimeOf(last.startWallMs), dateTimeOf(last.endedWallMs),
                    last.durationMs / 60_000L, last.packages.size()));
            tvLastSession.setVisibility(View.VISIBLE);
        } else {
            tvLastSession.setVisibility(View.GONE);
        }
    }

    private void renderHistory() {
        List<PersonalSession> history = controller.history(uid);
        if (history.isEmpty()) {
            tvHistory.setText(R.string.user_home_history_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(5, history.size());
        for (int i = 0; i < n; i++) {
            PersonalSession h = history.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(getString(R.string.user_home_history_line, dateTimeOf(h.startWallMs),
                    getString(h.status == PersonalSession.Status.CANCELLED
                            ? R.string.user_home_history_cancelled
                            : R.string.user_home_history_completed),
                    h.durationMs / 60_000L, h.packages.size()));
        }
        tvHistory.setText(sb.toString());
    }

    private void updateCustomVisibility() {
        boolean custom = chipDuration.getCheckedChipId() == R.id.chipCustom;
        etCustom.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private void setBusy(boolean b) {
        busy = b;
        progressBusy.setVisibility(b ? View.VISIBLE : View.GONE);
        btnStart.setEnabled(!b);
        btnEnd.setEnabled(!b);
        btnSelectApps.setEnabled(!b);
    }

    // ------------------------------------------------------------------ start

    /** Minutes chosen in the UI, or -1 after showing a validation error. */
    private int readMinutes() {
        int id = chipDuration.getCheckedChipId();
        if (id == R.id.chipCustom) {
            int m = PersonalRules.parseCustomMinutes(etCustom.getText().toString());
            if (m < 0) {
                etCustom.setError(getString(R.string.user_home_custom_error,
                        PersonalRules.MIN_MINUTES, PersonalRules.MAX_MINUTES));
                etCustom.requestFocus();
            }
            return m;
        }
        View chip = id == View.NO_ID ? null : findViewById(id);
        Object tag = chip == null ? null : chip.getTag();
        if (tag != null) {
            try {
                int m = Integer.parseInt(tag.toString());
                if (PersonalRules.isValidDurationMinutes(m)) {
                    return m;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        toast(getString(R.string.user_home_msg_bad_duration,
                PersonalRules.MIN_MINUTES, PersonalRules.MAX_MINUTES));
        return -1;
    }

    private void onStartClicked() {
        if (busy || lastState == null || controller == null) {
            return;
        }
        int minutes = readMinutes();
        if (minutes < 0) {
            return;
        }
        switch (lastState.startBlocker) {
            case NO_APPS:
                toast(getString(R.string.user_home_msg_no_apps));
                return;
            case ACCESSIBILITY:
                if (lastState.accessibility == PersonalDashboardState.Accessibility.STARTING) {
                    toast(getString(R.string.user_home_msg_a11y_starting));
                } else {
                    showAccessibilityDialog();
                }
                return;
            case SCHOOL_SESSION:
                toast(getString(R.string.user_home_notice_school));
                return;
            case OTHER_ACCOUNT_SESSION:
                render();
                return;
            case NOT_SIGNED_IN:
                bindUser();
                return;
            default:
                break;
        }

        setBusy(true);
        final String forUid = uid;
        final Set<String> chosen = new HashSet<>(selection);
        final long durationMs = PersonalRules.minutesToMs(minutes);
        final PersonalSessionController c = controller;
        try {
            io.execute(() -> {
                PersonalSessionController.StartOutcome outcome =
                        c.start(forUid, chosen, durationMs);
                ui.post(() -> {
                    if (isDestroyed()) {
                        // Still tell the service: the session may have been committed.
                        BlockerService.notifyPolicyChanged();
                        return;
                    }
                    setBusy(false);
                    onStartResult(outcome);
                });
            });
        } catch (RuntimeException e) {
            setBusy(false);
        }
    }

    private void onStartResult(PersonalSessionController.StartOutcome o) {
        switch (o.result) {
            case STARTED:
                // Committed to disk and the service was connected when it was checked.
                BlockerService.notifyPolicyChanged();
                toast(getString(R.string.user_home_msg_started,
                        timeOf(o.session.effectiveEndWallMs(controller.clock()))));
                break;
            case ALREADY_ACTIVE:
                BlockerService.notifyPolicyChanged();
                toast(getString(R.string.user_home_msg_already));
                break;
            case SCHOOL_ACTIVE:
                toast(getString(R.string.user_home_notice_school));
                break;
            case ACCESSIBILITY_OFF:
                if (AccessibilityStatus.isEnabled(this)) {
                    toast(getString(R.string.user_home_msg_a11y_starting));
                } else {
                    showAccessibilityDialog();
                }
                break;
            case NO_APPS:
                toast(getString(R.string.user_home_msg_no_apps));
                break;
            case TOO_MANY_APPS:
                toast(getString(R.string.user_home_msg_too_many, PersonalRules.MAX_APPS));
                break;
            case BAD_DURATION:
                toast(getString(R.string.user_home_msg_bad_duration,
                        PersonalRules.MIN_MINUTES, PersonalRules.MAX_MINUTES));
                break;
            case NOT_SIGNED_IN:
                bindUser();
                break;
            default:
                toast(getString(R.string.user_home_msg_storage));
                break;
        }
        render();
    }

    // -------------------------------------------------------------------- end

    private void confirmEnd() {
        if (busy || lastState == null || !lastState.canEnd || controller == null) {
            return;
        }
        PersonalSession running = controller.current();
        if (running == null || !running.isOwnedBy(uid)) {
            render();
            return;
        }
        final String sessionId = running.id;
        if (dialog != null) {
            dialog.dismiss();
        }
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.user_home_end_confirm_title)
                .setMessage(R.string.user_home_end_confirm_body)
                .setPositiveButton(R.string.user_home_end_confirm_yes, (d, w) -> endSession(sessionId))
                .setNegativeButton(R.string.user_home_end_confirm_no, null)
                .show();
    }

    private void endSession(final String sessionId) {
        if (busy || controller == null) {
            return;
        }
        setBusy(true);
        final String forUid = uid;
        final PersonalSessionController c = controller;
        try {
            io.execute(() -> {
                PersonalSessionController.CancelResult r = c.cancel(forUid, sessionId);
                ui.post(() -> {
                    BlockerService.notifyPolicyChanged(); // release restrictions promptly
                    if (isDestroyed()) {
                        return;
                    }
                    setBusy(false);
                    onEndResult(r);
                });
            });
        } catch (RuntimeException e) {
            setBusy(false);
        }
    }

    private void onEndResult(PersonalSessionController.CancelResult r) {
        switch (r) {
            case CANCELLED:
                toast(getString(R.string.user_home_msg_ended));
                break;
            case NOT_ACTIVE:
                toast(getString(R.string.user_home_msg_not_active));
                break;
            case NOT_OWNER:
                toast(getString(R.string.user_home_msg_not_owner));
                break;
            case STALE_SESSION:
                toast(getString(R.string.user_home_msg_stale));
                break;
            case NOT_ALLOWED:
                toast(getString(R.string.user_home_msg_not_allowed));
                break;
            default:
                toast(getString(R.string.user_home_msg_end_failed));
                break;
        }
        render();
    }

    // ---------------------------------------------------------- accessibility

    /** Explains why the permission is needed BEFORE sending the person to Android settings. */
    private void showAccessibilityDialog() {
        if (dialog != null) {
            dialog.dismiss();
        }
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.user_home_access_dialog_title)
                .setMessage(R.string.user_home_access_dialog_body)
                .setPositiveButton(R.string.user_home_open_settings, (d, w) -> openAccessibilitySettings())
                .setNegativeButton(R.string.user_home_cancel, null)
                .show();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.user_home_settings_missing));
        }
    }

    // ---------------------------------------------------------------- helpers

    private String timeOf(long ms) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ms));
    }

    private String dateTimeOf(long ms) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
