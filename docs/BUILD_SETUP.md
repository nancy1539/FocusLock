# FocusLock - Build setup

**Status:** this project has not yet been built with Gradle by the author of these
instructions (the build environment had no access to the Gradle/Google/Maven servers).
The steps below are the standard ones for this project's configuration; the first real build
may show problems that nobody has seen yet.

## 1. Prerequisites (Windows)

1. **Android Studio** (current stable) with the **Android SDK** and the platform for
   **API 37** (`compileSdk 37`) installed via *Tools -> SDK Manager*.
2. **Internet access on the first build:** Gradle 9.6.0 is downloaded by the wrapper, and a
   **JDK 25** is downloaded automatically by the foojay toolchain resolver
   (`gradle/gradle-daemon-jvm.properties`) unless a JDK 25 is already installed. Dependencies
   come from Google Maven and Maven Central.
3. A phone with USB debugging, or an emulator, for device tests; `adb` from
   `<SDK>\platform-tools`.

## 2. Android SDK location (`local.properties`)

`local.properties` is **not included** in this project, on purpose: it holds the absolute path
of the Android SDK on one particular computer.

Pick one:

* **Android Studio (recommended):** *File -> Open* this folder. Studio creates
  `local.properties` for your machine.
* **Command line, file:** copy `local.properties.example` to `local.properties`, uncomment the
  Windows line and put **your** SDK path in it, e.g.
  `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`
* **Command line, environment variable (PowerShell):**
  `$env:ANDROID_HOME = "C:\Users\<you>\AppData\Local\Android\Sdk"` (current window only), or
  `setx ANDROID_HOME "C:\Users\<you>\AppData\Local\Android\Sdk"` (new windows).

Never commit or share `local.properties` (it is in `.gitignore`).

## 3. Build and run the unit tests (Windows)

Open PowerShell or Command Prompt **in the project folder** (the one containing
`gradlew.bat`) and run:

    gradlew.bat testDebugUnitTest assembleDebug

* Success ends with `BUILD SUCCESSFUL`.
* Unit-test report: `app\build\reports\tests\testDebugUnitTest\index.html`
  (expected: 131 tests, 0 failures: `UserAuthRulesTest` 18, `BlockLaunchGateTest` 16,
  `LockSessionTest` 21, `LockPolicyTest` 15, `ExampleUnitTest` 1, plus 60 personal-locking tests in
  `PersonalSessionTest`, `PersonalSessionControllerTest`, `EnforcementPolicyTest`,
  `PersonalDashboardAndAppsTest`).
* APK: `app\build\outputs\apk\debug\app-debug.apk`

If it asks for SDK licences: `<SDK>\cmdline-tools\latest\bin\sdkmanager.bat --licenses`.
If it fails, keep the **first** error message of the output: it is the one that matters.

macOS / Linux: `./gradlew testDebugUnitTest assembleDebug`
(the wrapper script must be executable: `chmod +x gradlew`).

## 4. Install and test on a device

    adb install -r app\build\outputs\apk\debug\app-debug.apk

Android 13+ (sideloaded APK): *Settings -> Apps -> FocusLock -> menu (three dots) -> **Allow
restricted settings***, then *Settings -> Accessibility -> FocusLock*: switch **OFF and ON**
and accept the permission dialog.

Capture logs while testing:

    adb logcat -c
    adb logcat -v threadtime -s FocusLockDebug ActivityTaskManager:I > focuslock.log

Then work through `docs/TEST_PLAN.md` (22 manual cases) and record the real results.
The intermittent bypass is **not** considered fixed until this has been done.

## 5. `google-services.json` - do not publish

`app/google-services.json` contains the Firebase client configuration (project id, API key).
Do not publish this project (GitHub, shared drives) without reviewing Firebase security:
restrict the API key, replace test-mode Firestore rules with real rules, and restrict read access
to school documents (they contain `teacherPassHash`).
