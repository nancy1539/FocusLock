package com.example.focuslock;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen "Focus Time" screen shown over a blocked app.
 *
 * Task/launch configuration (see AndroidManifest.xml):
 *  - launchMode=singleTask + a dedicated taskAffinity: the service can launch
 *    it as often as it likes; the existing instance is reused and gets
 *    onNewIntent(). It is never stacked or destroyed/recreated (the old
 *    CLEAR_TOP + standard mode recreated it on every launch and pulled the
 *    whole FocusLock task forward).
 *  - excludeFromRecents: the block screen cannot be re-entered from Recents.
 *    This does NOT enforce anything by itself; enforcement comes from
 *    BlockerService re-detecting the blocked app.
 *
 * Navigation:
 *  - Back, the system back gesture and "Go Home" all start the HOME intent and
 *    deliberately do NOT call finish(). Finishing could reveal the blocked
 *    app's task underneath before the launcher is on screen.
 *  - The activity is dismissed (finishAndRemoveTask) only when the session
 *    ends/expires (service-driven via {@link #dismissIfShowing}) or when its
 *    own end time has passed.
 */
public class BlockActivity extends AppCompatActivity {

    static final String EXTRA_END_TIME = "endTime";
    static final String EXTRA_BLOCKED_PKG = "blockedPkg";
    /** true = the Personal User's own focus session; absent/false = School/College. */
    static final String EXTRA_PERSONAL = "personal";

    private static final String TAG = "FocusLockDebug";

    /** Main-thread only. The live instance, if any. */
    private static WeakReference<BlockActivity> current = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expiryCheck = this::finishIfExpired;

    private final Runnable tick = this::updateRemaining;

    private TextView tvInfo;
    private TextView tvRemaining;
    private View btnOpenApp;
    private long endTime;
    private boolean personalMode = false;
    private String blockedLabel = "";
    /** True between onResume and onPause: the block screen is really on screen. */
    private boolean resumed = false;

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block);

        tvInfo = findViewById(R.id.tvInfo);
        tvRemaining = findViewById(R.id.tvRemaining);
        btnOpenApp = findViewById(R.id.btnOpenApp);
        findViewById(R.id.btnGoHome).setOnClickListener(v -> goHome());
        btnOpenApp.setOnClickListener(v -> openFocusLock());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goHome();
            }
        });

        current = new WeakReference<>(this);
        applyIntent(getIntent());
        Log.d(TAG, "BlockActivity onCreate");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
        Log.d(TAG, "BlockActivity onNewIntent (reused instance)");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        current = new WeakReference<>(this);
        Log.d(TAG, "BlockActivity onResume");
        finishIfExpired();
        updateRemaining();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        Log.d(TAG, "BlockActivity onPause");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(expiryCheck);
        handler.removeCallbacks(tick);
        if (current.get() == this) {
            current = new WeakReference<>(null);
        }
        Log.d(TAG, "BlockActivity onDestroy");
        super.onDestroy();
    }

    // ---------------------------------------------------------------- content

    private void applyIntent(Intent intent) {
        long end = intent == null ? 0L : intent.getLongExtra(EXTRA_END_TIME, 0L);
        personalMode = intent != null && intent.getBooleanExtra(EXTRA_PERSONAL, false);
        String pkg = intent == null ? null : intent.getStringExtra(EXTRA_BLOCKED_PKG);
        blockedLabel = labelOf(pkg);
        btnOpenApp.setVisibility(personalMode ? View.VISIBLE : View.GONE);
        setEndTime(end);
    }

    /** The blocked app's display name, or a neutral phrase when it cannot be resolved. */
    private String labelOf(String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            try {
                PackageManager pm = getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                // fall through
            }
        }
        return "This app";
    }

    private void setEndTime(long end) {
        this.endTime = end;
        String until = new SimpleDateFormat("hh:mm a", Locale.getDefault())
                .format(new Date(end));
        if (personalMode) {
            tvInfo.setText("FocusLock is active.\n" + blockedLabel
                    + " is temporarily restricted during your focus session.\nUntil " + until);
        } else {
            tvInfo.setText("Your teacher has locked this app.\nLocked until " + until);
        }
        updateRemaining();

        handler.removeCallbacks(expiryCheck);
        long remaining = end - System.currentTimeMillis();
        if (remaining > 0L) {
            handler.postDelayed(expiryCheck, remaining + 50L);
        }
    }

    /** Shows the time left, recomputed from the end time on every tick (no drifting counter). */
    private void updateRemaining() {
        handler.removeCallbacks(tick);
        long remaining = endTime - System.currentTimeMillis();
        if (remaining <= 0L) {
            tvRemaining.setText("");
            return;
        }
        tvRemaining.setText("Time remaining: " + PersonalRules.formatRemaining(remaining));
        if (resumed) {
            handler.postDelayed(tick, 1000L);
        }
    }

    /** Personal mode only: lets the person reach FocusLock (never an app that is restricted). */
    private void openFocusLock() {
        Intent i = new Intent(this, UserHomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void finishIfExpired() {
        if (System.currentTimeMillis() >= endTime) {
            // Safe: a missing/expired end time must never trap the student here.
            Log.d(TAG, "BlockActivity dismissing itself: endTime reached or missing");
            finishAndRemoveTask();
        }
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        // No finish(): see class comment.
    }

    // ------------------------------------------------- called by BlockerService

    /**
     * True only while the block screen is resumed. BlockerService combines this
     * with "the foreground window belongs to FocusLock" to CONFIRM that launching
     * works (BlockLaunchGate.onProtectionConfirmed). Main thread only.
     */
    static boolean isBlockScreenResumed() {
        BlockActivity a = current.get();
        return a != null && a.resumed && !a.isFinishing() && !a.isDestroyed();
    }

    /** Keeps a visible block screen in sync when the teacher restarts the lock. */
    static void updateEndTime(long end) {
        BlockActivity a = current.get();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
            a.setEndTime(end);
        }
    }

    /** Session ended, expired, or no longer applies to this student. */
    static void dismissIfShowing(String reason) {
        BlockActivity a = current.get();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
            Log.d(TAG, "BlockActivity dismissed by service: " + reason);
            a.finishAndRemoveTask();
        }
    }
}
