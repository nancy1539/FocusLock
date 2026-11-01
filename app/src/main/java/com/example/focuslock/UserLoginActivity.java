package com.example.focuslock;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import java.util.HashMap;
import java.util.Map;

/**
 * Personal User login / register.
 *
 * <p>Flow: Firebase Authentication (email + password) -> Firestore profile check
 * ({@code users/{uid}} with {@code role == "personal"}) -> {@link UserHomeActivity}. The
 * dashboard is opened ONLY after both steps succeed. Every failure path logs diagnostics under
 * the tag {@value #TAG} (never passwords or tokens) and shows a friendly message.
 */
public class UserLoginActivity extends AppCompatActivity {

    private static final String TAG = "UserLoginActivity";
    private static final String USERS = "users";
    private static final String ROLE_PERSONAL = "personal";
    /** Firestore writes queue forever while offline, so never leave the UI spinning. */
    private static final long REQUEST_TIMEOUT_MS = 20_000L;
    private static final int IDLE = 0;

    private EditText etEmail, etPassword, etParentPhone, etPin;
    private Button btnLogin, btnRegister;
    private ProgressBar progress;
    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutTask = this::onRequestTimeout;
    private int requestCounter = 0;
    /** Token of the request currently in flight, or IDLE. Late/stale callbacks compare to it. */
    private int activeRequest = IDLE;
    private boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_login);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etParentPhone = findViewById(R.id.etParentPhone);
        etPin = findViewById(R.id.etPin);
        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);
        progress = findViewById(R.id.progress);

        try {
            auth = FirebaseAuth.getInstance();
            db = FirebaseFirestore.getInstance();
        } catch (RuntimeException e) {
            // e.g. FirebaseApp not initialised because google-services.json is wrong/missing.
            Log.e(TAG, "Firebase is not available", e);
            btnLogin.setEnabled(false);
            btnRegister.setEnabled(false);
            toast("Sign-in is unavailable right now. Please try again later");
            return;
        }

        btnRegister.setOnClickListener(v -> register());
        btnLogin.setOnClickListener(v -> login());

        resumeExistingSession();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(timeoutTask);
        super.onDestroy();
    }

    // ------------------------------------------------------------ existing session

    /** If a Firebase user is already signed in, verify the profile and skip the form. */
    private void resumeExistingSession() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Log.d(TAG, "No existing session; showing login form");
            return;
        }
        Log.i(TAG, "Existing Firebase session found; verifying personal profile");
        verifyPersonalProfile(user.getUid(), beginRequest(), true);
    }

    // -------------------------------------------------------------------- register

    private void register() {
        if (isBusy()) {
            return;
        }
        String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();
        String parent = etParentPhone.getText().toString().trim();
        String pin = etPin.getText().toString().trim();

        clearErrors();
        if (!check(etEmail, UserAuthRules.emailError(email))
                || !check(etPassword, UserAuthRules.registrationPasswordError(pass))
                || !check(etParentPhone, UserAuthRules.parentPhoneError(parent))
                || !check(etPin, UserAuthRules.pinError(pin))) {
            return;
        }
        String pinHash = UserAuthRules.sha256Hex(pin);
        if (pinHash == null) {
            Log.e(TAG, "SHA-256 unavailable; registration aborted");
            toast("Could not secure your PIN on this device");
            return;
        }

        Log.i(TAG, "Registration started");
        final int token = beginRequest();
        auth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) {
                        Log.e(TAG, "Registration returned no user");
                        if (isActive(token)) {
                            endRequest();
                            toast(UserAuthRules.GENERIC_MESSAGE);
                        }
                        return;
                    }
                    Log.i(TAG, "Auth account created; saving profile");
                    saveProfile(user, email, parent, pinHash, token);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Registration failed: " + describe(e), e);
                    if (isActive(token)) {
                        endRequest();
                        toast(authFailureMessage(e));
                    }
                });
    }

    /** Same document shape as before: {email, role, parentPhone, pinHash}. */
    private void saveProfile(FirebaseUser user, String email, String parent, String pinHash,
                             int token) {
        Map<String, Object> data = new HashMap<>();
        data.put("email", email);
        data.put("role", ROLE_PERSONAL);
        data.put("parentPhone", parent);
        data.put("pinHash", pinHash);

        db.collection(USERS).document(user.getUid()).set(data)
                .addOnSuccessListener(u -> {
                    Log.i(TAG, "Profile saved");
                    if (isActive(token)) {
                        endRequest();
                        goHome();
                    } else {
                        Log.w(TAG, "Profile saved but request no longer active; not navigating");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Profile save failed: " + describe(e), e);
                    // Without a profile the account could never log in, so undo it and let the
                    // user retry cleanly instead of leaving an unusable half-registered account.
                    rollbackRegistration(user);
                    if (isActive(token)) {
                        endRequest();
                        toast(UserAuthRules.firestoreErrorMessage(firestoreCode(e)));
                    }
                });
    }

    private void rollbackRegistration(FirebaseUser user) {
        final String uid = user.getUid();
        user.delete().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.i(TAG, "Rolled back partially created account");
            } else {
                Log.e(TAG, "Could not roll back partially created account", task.getException());
            }
            signOutIfCurrent(uid);
        });
    }

    // ----------------------------------------------------------------------- login

    private void login() {
        if (isBusy()) {
            return;
        }
        String email = etEmail.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();

        clearErrors();
        if (!check(etEmail, UserAuthRules.emailError(email))
                || !check(etPassword, UserAuthRules.loginPasswordError(pass))) {
            return;
        }

        Log.i(TAG, "Login started");
        final int token = beginRequest();
        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) {
                        Log.e(TAG, "Login returned no user");
                        if (isActive(token)) {
                            endRequest();
                            toast(UserAuthRules.GENERIC_MESSAGE);
                        }
                        return;
                    }
                    Log.i(TAG, "Login succeeded; verifying personal profile");
                    verifyPersonalProfile(user.getUid(), token, false);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Login failed: " + describe(e), e);
                    if (isActive(token)) {
                        endRequest();
                        toast(authFailureMessage(e));
                    }
                });
    }

    /**
     * Opens the dashboard only if {@code users/{uid}.role == "personal"}.
     *
     * @param resumingSession true when called for an already signed-in user (app relaunch); a
     *                        transient Firestore failure then keeps the session instead of
     *                        signing the user out.
     */
    private void verifyPersonalProfile(String uid, int token, boolean resumingSession) {
        db.collection(USERS).document(uid).get()
                .addOnSuccessListener(doc -> {
                    boolean personal = doc != null && doc.exists()
                            && ROLE_PERSONAL.equals(doc.getString("role"));
                    if (!personal) {
                        Log.w(TAG, "Signed-in account has no personal profile; signing out");
                        signOutIfCurrent(uid);
                        if (isActive(token)) {
                            endRequest();
                            toast(resumingSession
                                    ? "Your saved session is no longer valid. Please log in again"
                                    : "This is not a personal account");
                        }
                        return;
                    }
                    if (isActive(token)) {
                        endRequest();
                        goHome();
                    } else {
                        Log.w(TAG, "Profile verified but request no longer active; not navigating");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Profile lookup failed: " + describe(e), e);
                    if (!resumingSession) {
                        // Do not leave a half-completed login behind.
                        signOutIfCurrent(uid);
                    }
                    if (isActive(token)) {
                        endRequest();
                        toast(UserAuthRules.firestoreErrorMessage(firestoreCode(e)));
                    }
                });
    }

    // ------------------------------------------------------------------ navigation

    private void goHome() {
        if (navigated) {
            Log.w(TAG, "Duplicate navigation to dashboard ignored");
            return;
        }
        navigated = true;
        Log.i(TAG, "Opening Personal User dashboard");
        startActivity(new Intent(this, UserHomeActivity.class));
        finish();
    }

    // ------------------------------------------------------------- request / UI state

    private boolean isBusy() {
        return activeRequest != IDLE;
    }

    private int beginRequest() {
        activeRequest = ++requestCounter;
        setBusy(true);
        handler.removeCallbacks(timeoutTask);
        handler.postDelayed(timeoutTask, REQUEST_TIMEOUT_MS);
        return activeRequest;
    }

    private void endRequest() {
        activeRequest = IDLE;
        handler.removeCallbacks(timeoutTask);
        setBusy(false);
    }

    /** True only if this is still the current request and the screen is still alive. */
    private boolean isActive(int token) {
        return token == activeRequest && !isFinishing() && !isDestroyed();
    }

    private void onRequestTimeout() {
        if (activeRequest == IDLE) {
            return;
        }
        Log.w(TAG, "Request timed out after " + REQUEST_TIMEOUT_MS + " ms");
        endRequest(); // stale token: a late result can no longer navigate
        toast("The request timed out. Check your connection and try again");
    }

    private void setBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        btnRegister.setEnabled(!busy);
        etEmail.setEnabled(!busy);
        etPassword.setEnabled(!busy);
        etParentPhone.setEnabled(!busy);
        etPin.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    // --------------------------------------------------------------------- helpers

    private void clearErrors() {
        etEmail.setError(null);
        etPassword.setError(null);
        etParentPhone.setError(null);
        etPin.setError(null);
    }

    /** @return true if {@code error} is null (field OK); otherwise shows it on the field. */
    private boolean check(EditText field, String error) {
        if (error == null) {
            return true;
        }
        field.setError(error);
        field.requestFocus();
        return false;
    }

    private void signOutIfCurrent(String uid) {
        FirebaseUser current = auth.getCurrentUser();
        if (current != null && uid.equals(current.getUid())) {
            auth.signOut();
            Log.i(TAG, "Signed out");
        }
    }

    private String authFailureMessage(Exception e) {
        if (e instanceof FirebaseNetworkException) {
            return UserAuthRules.authErrorMessage(null, true);
        }
        return UserAuthRules.authErrorMessage(authCode(e), false);
    }

    private static String authCode(Exception e) {
        if (e instanceof FirebaseAuthException) {
            return ((FirebaseAuthException) e).getErrorCode();
        }
        if (e instanceof FirebaseTooManyRequestsException) {
            return "ERROR_TOO_MANY_REQUESTS";
        }
        return null;
    }

    private static String firestoreCode(Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            return ((FirebaseFirestoreException) e).getCode().name();
        }
        return null;
    }

    /** Developer-facing summary for logs. Contains no credentials. */
    private static String describe(Exception e) {
        String code = authCode(e);
        if (code == null) {
            code = firestoreCode(e);
        }
        return e.getClass().getSimpleName() + (code != null ? " code=" + code : "");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
