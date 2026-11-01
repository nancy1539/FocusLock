# Personal User login/register white screen - root cause and repair

## Root cause (verified in the source)

`app/src/main/res/layout/activity_user_home.xml` was an **empty** `ConstraintLayout`
(456 bytes, root `@+id/main`, **zero children**). `UserHomeActivity` called `setContentView()`
on it correctly and only installed a window-insets listener, so nothing crashed; the activity
simply rendered nothing but the theme background - the "white screen".

`UserLoginActivity.goHome()` is what opens `UserHomeActivity`. It runs after a **successful**
registration or login, so the blank screen *is* the successful-auth destination: authentication
was most likely working, and the app navigated to a dashboard that had no content.

Other causes were checked and ruled out: the manifest declares both activities, the Firebase
dependencies and `google-services.json` (package `com.example.focuslock`) are present,
`INTERNET` permission is declared, `MainActivity` navigation is correct, and the theme is a valid
Material3 theme. There is no crash path in the original dashboard.

### Additional defects found in `UserLoginActivity` (fixed)

| Defect | Effect |
|--------|--------|
| Login's Firestore `get()` had **no failure listener** | Auth succeeded, profile lookup failed (offline / rules) -> nothing happened, no message, user left half signed-in |
| Register: profile write failure left the Auth account behind | Email "in use" yet login rejected it ("not a personal account") forever |
| No busy state / double-click guard | Repeated taps fired duplicate requests |
| Raw `e.getMessage()` shown to users | Internal Firebase text in toasts |
| No email-format validation, parent phone not checked for digits | Poor feedback |
| No handling of an already signed-in user | Relaunch always showed the form |
| `sha256()` fell back to **returning the plain text** on error | Would have stored the PIN unhashed (unreachable in practice, now removed) |
| No timeout | Firestore writes queue forever offline -> endless wait |

## What changed

| File | Change |
|------|--------|
| `res/layout/activity_user_home.xml` | **Rewritten** (was empty): FocusLock title, "Personal User Dashboard", signed-in email, honest "personal locking not available yet" status, Log out button. Root id `main` kept. |
| `UserHomeActivity.java` | Safe view lookup (null-checked), shows email or placeholder, redirects to `MainActivity` if signed out, Logout = `signOut()` + clear-task navigation to `MainActivity`, duplicate-action guard, logging (`UserHomeActivity`). Still uses `EdgeToEdge` + insets. |
| `UserLoginActivity.java` | Rewritten around the same flow (Auth -> `users/{uid}` role check -> dashboard): field validation, progress + disabled controls, double-tap guard, 20 s timeout, friendly errors, failure listeners everywhere, rollback of orphaned account, existing-session resume, single-navigation guard, logging (`UserLoginActivity`, no passwords/PIN/email). |
| `UserAuthRules.java` | **New**, pure Java: validation, Firebase-error -> friendly message mapping, SHA-256 (output identical to what is already stored). |
| `res/layout/activity_user_login.xml` | Added the `progress` ProgressBar (hidden by default). Nothing else changed. |
| `res/values/strings.xml` | Added 6 `user_home_*` strings. |
| `AndroidManifest.xml` | `UserLoginActivity` gets `android:configChanges` (rotation no longer recreates it mid-request). Only manifest change. |
| `app/src/test/.../UserAuthRulesTest.java` | **New**, 18 JUnit tests. |
| `docs/BUILD_SETUP.md`, `docs/TEST_PLAN.md` | Test count updated; Personal User manual cases P1-P12 added. |

**Not touched:** every School/College class (`SchoolHome/Register`, `TeacherLogin/Dashboard`,
`ClassActivity`, `StudentLogin/Home`), `BlockerService`, `BlockActivity`, `LockSession`,
`LockPolicy`, `BlockLaunchGate`, `SessionStore`, accessibility config, Gradle files,
`google-services.json`. **Firestore schema unchanged** (`users/{uid}`: `email`, `role`,
`parentPhone`, `pinHash`); no rules changed.

## Personal app locking (superseded)

> Personal locking has since been implemented: see `PERSONAL_LOCKING.md`. The text below describes the state at the time of the white-screen fix.

The project contains **no** personal app-locking feature (`role == "personal"` is only used for
account identification). The dashboard says so explicitly. Building that feature is separate
work and was not attempted.

## Verification status (be precise)

Sandbox used for the repair had **no Android SDK, no Gradle distribution, and no network**, so
`gradlew.bat testDebugUnitTest assembleDebug` and emulator testing could **not** be run. No APK
was produced.

| Check | Executed? | Result |
|-------|-----------|--------|
| 18 new `UserAuthRulesTest` + the 53 existing tests, JDK-compiled and run with a minimal JUnit shim and reflective runner (not Gradle/JUnit proper) | Yes | 71 passed, 0 failed |
| Mutation checks on `UserAuthRules` (5 deliberate breaks) | Yes | all detected |
| `UserLoginActivity` / `UserHomeActivity` / `MainActivity` / `SchoolHomeActivity` compiled against hand-written Android/Firebase stubs and an `R` generated from the real resources | Yes | 0 errors; deliberate typo in `R.id` is rejected (negative control) |
| Behavioural simulation: the **real activity code** driven through 50 checks with fake Firebase tasks (registration, login, bad credentials, network failure, profile failure + rollback, timeout, duplicate clicks, session resume, logout, signed-out dashboard, log contents) | Yes | 50 passed, 0 failed |
| Mutation checks on the activity (4 deliberate breaks: no busy guard, navigate on failure, no rollback, timeout not clearing busy) | Yes | all detected |
| XML well-formedness, `@string` references, duplicate ids | Yes | OK |
| Diff vs. uploaded ZIP | Yes | only the files listed above differ |
| **Real Gradle build, real aapt/R8/lint, APK install** | **No** | unverified |
| **Anything on an emulator/device** (real Firebase auth, real Firestore, actual rendering, rotation, relaunch) | **No** | unverified |
| School/College flows on a device (register, teacher login, class, student, Start/Stop Lock) | **No** | code unmodified (diff-verified), but not re-run |

The simulation uses my own fakes of Firebase and Android, so it proves the logic of this
app's code, not the behaviour of the real SDKs.

## Things only you can confirm (Firebase console)

1. **Authentication -> Sign-in method -> Email/Password must be enabled.** Otherwise registration
   now shows "Email sign-in is not enabled for this app yet" (logcat code `ERROR_OPERATION_NOT_ALLOWED`).
2. **Firestore rules must allow the signed-in user to read/write `users/{uid}`.** If they do not,
   the app now shows "Could not access your account profile..." (logcat `PERMISSION_DENIED`) and
   undoes the half-created account. Suggested rule (not applied, rules were not changed):
   `match /users/{uid} { allow read, write: if request.auth != null && request.auth.uid == uid; }`
3. Existing accounts that were left half-registered by the old code (Auth user without a
   `users/{uid}` document) must be deleted in the console (Authentication -> Users) and
   re-registered.

## Rebuild and install (Windows)

    cd "<folder containing gradlew.bat>"
    gradlew.bat testDebugUnitTest assembleDebug
    gradlew.bat installDebug

Expect `BUILD SUCCESSFUL` and 71 passing unit tests. APK: `app\build\outputs\apk\debug\app-debug.apk`.
Alternative with an emulator running: `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
If your SDK is not found, open the folder once in Android Studio (creates `local.properties`)
or see `BUILD_SETUP.md`.

Live logs while testing:

    adb logcat -c
    adb logcat -v threadtime -s UserLoginActivity UserHomeActivity

## Known limits / not changed

* The PIN is stored as an unsalted SHA-256 hash (existing design; a 4-digit PIN is brute-forceable
  by anyone who can read the document). Changing it would alter the stored format, so it was left.
* A very short window exists where backgrounding the app *during* registration, between account
  creation and profile write, followed by an immediate relaunch, could sign out the new session
  (the profile write itself still completes). Rotation is handled via `configChanges`.
