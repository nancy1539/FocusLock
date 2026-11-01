# FocusLock - Final audit and pre-build verification

> **The intermittent lock bypass remains UNCONFIRMED.** It stays unconfirmed until the
> project has been built with Gradle **and** tested on an Android emulator or physical
> device. The Gradle build has **not** run successfully anywhere in this audit, no APK
> exists, and no scenario has been tested on a device. Nothing in this report claims the
> bypass is fixed.

Basis: this report was written from the files inside `FocusLock_Project_final_audit_v2.zip`
(extracted fresh and compared with the working tree: identical before this round's edits),
not from earlier reports. Earlier reports contained outdated statements (for example that
empty lists are accepted, and test counts of 47/52); they are superseded by this file.

## 1. Results at a glance

| Category | Result |
|----------|--------|
| 1. Tests **executed and passed** | **53 JUnit tests, 0 failures**, run directly on the JVM (not through Gradle) - details in section 3 |
| 2. Tests that **exist but were not executed** | `ExampleInstrumentedTest` (1 `androidTest`, needs a device/emulator); the 22 manual cases in `docs/TEST_PLAN.md` |
| 3. Builds | **`gradlew testDebugUnitTest assembleDebug`: could not start** (details in section 4). **No build succeeded. No APK exists.** |
| 4. Device scenarios | **All untested** (section 6) |

## 2. LockSession behaviour (verified in the source and by tests)

| Requirement | Verified | Evidence (test in `LockSessionTest`) |
|-------------|----------|--------------------------------------|
| Active session with empty `presentIds` rejected | Yes, also when `blockAll` is true | `activeSessionWithEmptyPresentIdsIsRejected` |
| Active session, `blockAll` false and empty `blockedApps` rejected | Yes | `emptyBlockedAppsWithBlockAllFalseIsRejected` |
| Same when `blockAll` is **absent** (read as false) | Yes | `emptyBlockedAppsWithBlockAllAbsentIsRejected` |
| Empty `blockedApps` accepted when `blockAll` is true | Yes | `emptyBlockedAppsWithBlockAllTrueIsAccepted`, `existingStartLockRecordsRemainValid` |
| `blockAll` true plus checked apps accepted | Yes | `blockAllTrueWithAlsoCheckedAppsIsAccepted` |
| Missing / null `blockedApps` or `presentIds` rejected with reason | Yes | `activeSessionWithout...IsRejectedWithReason`, `explicitNullListsAreRejectedAsNullNotMissing` |
| Non-string / null / blank list elements rejected | Yes | `badListElementsAreRejectedWithTheirIndex` |
| Stop document (`active=false`) valid without the two lists | Yes | `stopDocumentStaysValidWithoutLists`, `explicitStopIsValidEvenWithoutOtherFields` |
| Teacher-generated documents still parse | Yes (shape written by `ClassActivity.startLock`: `ArrayList` values, `Long` endTime) | `existingStartLockRecordsRemainValid`, `multiStudentTeacherGeneratedSessionParses` |
| Persistence applies the same rule | Yes **after this round's fix** (section 5) | `sharedActiveSessionRuleMatchesParserAndIsUsableByPersistence` (tests the shared rule; `SessionStore.load` itself needs Android and is **not** unit-tested) |

## 3. Tests actually executed

Method: `javac --release 11` of `LockSession`, `LockPolicy`, `BlockLaunchGate` plus the four
unit-test files; JUnit 4.13.2 + Hamcrest 1.3 (jars bundled with the sandbox's Gradle 8.14.3)
run with `org.junit.runner.JUnitCore`, one class at a time. The `@Test` counts below were
counted in the files and match JUnit's reported counts.

| Class | `@Test` methods | JUnit result |
|-------|-----------------|--------------|
| `BlockLaunchGateTest` | 16 | OK (16 tests) |
| `LockSessionTest` | 21 | OK (21 tests) |
| `LockPolicyTest` | 15 | OK (15 tests) |
| `ExampleUnitTest` | 1 | OK (1 test) |
| **Total** | **53** | **53 passed, 0 failed** |

Also executed:
* **Mutation checks** (a deliberately broken copy must fail tests): removing the new
  `presentIds`/`blockedApps` checks makes 3 `LockSessionTest` tests fail; weakening only the
  shared `presentIds` rule makes 2 fail; earlier rounds: "GO_HOME resets state" 10 fail,
  "back-off suppresses Home" 5 fail, "no back-off growth" 2 fail, "missing lists default to
  empty" 3 fail.
* **Stub compile** (`javac --release 11` of `BlockerService`, `BlockActivity`, `BlockLaunchGate`,
  `LockSession`, `LockPolicy`, `SessionStore` against hand-written stubs of Android/AndroidX/
  Firebase): 0 errors. This only catches syntax/type slips against my own recollection of the
  signatures. It is **not** a build and says nothing about the real SDK.

Not executed: `ExampleInstrumentedTest`; every manual case in `docs/TEST_PLAN.md`; any test of
`BlockerService`, `BlockActivity` or `SessionStore` (they need Android).

## 4. Build status (actual)

* `./gradlew testDebugUnitTest assembleDebug` (same tasks as `gradlew.bat ...`) -> **failed
  before the build started**: `Unable to tunnel through proxy. Proxy returns "HTTP/1.1 403
  Forbidden"` while downloading `https://services.gradle.org/distributions/gradle-9.6.0-bin.zip`.
  This is an environment restriction, not a project error. No Gradle version, wrapper or JDK
  toolchain setting was changed.
* Earlier in the audit, the sandbox's local Gradle 8.14.3 (`--offline`) also failed: the JDK 25
  toolchain download from foojay returned 403. No Android SDK is installed there; Google Maven
  and Maven Central are unreachable.
* No `app/build` directory, **no APK**, no Gradle test report was produced. None was fabricated.

Build configuration as inspected (not validated by a build):

| Item | Value |
|------|-------|
| Gradle wrapper | 9.6.0 (`gradle-wrapper.properties`, with `distributionSha256Sum`) |
| Android Gradle Plugin | 9.4.1 (`gradle/libs.versions.toml`) |
| Google services plugin | 4.4.2 (`build.gradle.kts`) |
| JDK for the Gradle daemon | 25 (`gradle-daemon-jvm.properties`, foojay auto-download) |
| Java source/target | 11 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 |
| Documented command | `gradlew.bat testDebugUnitTest assembleDebug` (Windows), `./gradlew ...` (macOS/Linux): task names are the standard AGP ones and fit this single-module app project |

Unverified risk, to be settled by the first real build: whether AGP 9.4.1, Gradle 9.6.0 and
google-services 4.4.2 work together. These versions came with the project and were not touched.

## 5. Regression audit (inspection of the current source)

| Question | Finding |
|----------|---------|
| Does `startLock` write all required fields? | **Yes.** `ClassActivity.startLock` writes `active`, `endTime`, `blockAll`, `blockedApps` (ArrayList), `presentIds` (list of `presentSet`), `date` with a full `set()`. The UI refuses to start without a present student, with minutes <= 0, or with neither Block ALL nor at least one app. Stop is `update("active", false)` only. |
| `blockAll` with no selected apps | Parsed as valid (`blockedApps == []`, the normal case); `LockPolicy.decide` then blocks every non-exempt, non-self package (`blockAllBlocksEverythingExceptExemptSelfAndUnknown`). |
| List mode with missing or empty app selection | Rejected by the parser (tests above). Teacher UI cannot produce it. |
| Malformed documents handled consistently | Every Firestore snapshot goes through `LockSession.parse`; a rejected document is logged with its reason (`Malformed session document (...)`) and the last valid session is kept. *Quirk, not changed:* a stop document whose `endTime` is present but not a number would also be rejected, so the lock would run to its `endTime`; the app never writes such a document. |
| Persistence agrees with validation | **Concrete bug found and fixed.** `SessionStore.load` only checked that the saved sets existed, so a record written by an older build with empty `presentIds`, or a list lock with no apps, could be restored under weaker rules than a Firestore document (and `ENFORCEMENT_DESIGN.md` wrongly claimed otherwise). Now both use `LockSession.activeSessionProblem`; an offending record is cleared and logged. |
| Gate enters/exits degraded mode correctly | By unit test: enters after 3 unconfirmed launches; exits only via `onProtectionConfirmed()` or `reset()`; an allowed foreground does not clear it. In the service, "confirmed" means the foreground package is FocusLock's **and** `BlockActivity` is resumed. That service integration is **untested on a device**. |
| Can GO_HOME cause an endless loop or a new bypass window? | No endless *launch* loop: back-off applies to launches and Home is not delayed. Home repeats only per detection of a blocked foreground (verify re-check 300 ms, poll 1 s). Exposure windows that remain: up to ~350 ms per launch or probe, plus detection latency. **Gap:** if `performGlobalAction(HOME)` itself returns false repeatedly (logged as `dispatched=false`) there is no further fallback and the blocked app stays usable. |
| Do expiry and teacher Stop reset enforcement state? | By reading the code: both go through `endSession`, which clears the session, the persisted copy, the gate, the poll/verify/expiry callbacks and dismisses the block screen; the non-enforcing branch of `evaluate` also resets the gate. Not unit-testable (service); untested on a device. |
| Does logging explain skipped actions? | Yes for the main paths: `SKIP duplicate launch ...`, `SKIP: foreground unknown ...`, `No enforcement: session state=...`, `ALLOW pkg=... reason=...` (de-duplicated, so a repeated identical state is logged once), `MISMATCH`, `listener re-attach deferred`, `Malformed session document`, `ENFORCE HOME`, `PROBE`, `RECOVERY DEGRADED/CLEARED`. |

## 6. Source changes

**This round (one concrete bug):**

| File | Change |
|------|--------|
| `LockSession.java` | The two semantic checks (non-empty `presentIds`; non-empty `blockedApps` unless `blockAll`) moved into a package-private `activeSessionProblem(...)` used by `parse`. Behaviour of `parse` is unchanged. |
| `SessionStore.java` | `load()` now applies `activeSessionProblem` and discards a record that fails it. |
| `LockSessionTest.java` | +1 test for the shared rule (21 tests in the class). |
| `docs/FINAL_AUDIT.md`, `docs/BUILD_SETUP.md`, `docs/ENFORCEMENT_DESIGN.md`, `docs/TEST_PLAN.md` | Documentation corrections. |

**Earlier rounds, relative to the ZIP you first delivered:** removed `local.properties`; added
`local.properties.example` and `docs/BUILD_SETUP.md`; rewrote `BlockLaunchGate` (NORMAL/DEGRADED,
Home never delayed, back-off only for probes) and its tests; `BlockerService` (confirmation only
when the block screen is resumed and in front, PROBE handling, diagnostics, `MULTIWINDOW`
logging); `BlockActivity` (`isBlockScreenResumed()`); `LockSession` (mandatory lists, non-empty
rules); `gradlew` executable bit.

Not changed: Gradle files, `AndroidManifest.xml`, `accessibility_config.xml`, layouts/UI,
`LockPolicy`, `ClassActivity`, login, attendance, teacher/student screens, Firestore schema.

## 7. Packaging check of the ZIP

* Contains all sources, 32 resource files, the Gradle wrapper (`gradlew`, `gradlew.bat`,
  `gradle-wrapper.jar`, `gradle-wrapper.properties`), build files and docs.
* **Excluded** (verified by listing): `local.properties`, `.gradle/`, `build/` directories,
  APKs, `.idea`, `*.iml`, keystores.
* **Secret scan:** no private keys, service-account files, access tokens or passwords found. The
  only hits for "password" are UI hint text and variable names.
* `app/google-services.json` is **kept** because the project needs it. It holds the Firebase
  client configuration (project `focuslock-9c13a`, one client API key). **Do not publish this
  archive publicly without reviewing the Firebase security configuration**: restrict the API key,
  and make sure Firestore security rules (not test-mode rules, which expire after 30 days) limit who can
  read and write `schools/...`. Note that the school document stores `teacherPassHash`, so read
  access to it must be restricted.

## 8. Remaining known issues and limitations

1. **Bypass unconfirmed; no successful build; no device testing.** See the banner.
2. Real compile of the Android-facing classes against the SDK/Firebase/AndroidX has not happened.
3. Possible version incompatibility among AGP 9.4.1 / Gradle 9.6.0 / google-services 4.4.2
   (untested; unchanged).
4. In degraded mode students are sent Home instead of seeing the block screen until a probe
   succeeds; if Home cannot be dispatched there is no further fallback.
5. Multi-window: only the active window is enforced; passive split-screen pane, picture-in-picture
   (cannot be closed by an accessibility service), secondary displays and unreadable windows are
   not (`docs/ENFORCEMENT_DESIGN.md` section 7; all unverified on a device).
6. A blocked app can be visible for up to ~350 ms per launch/probe plus detection latency.
7. A rejected (incomplete) document keeps the previous session; with no previous session nothing is
   enforced (fail-open).
8. The student can disable the service or uninstall the app; OEM battery managers / Force Stop can
   kill it; moving the phone clock forward ends the lock early (expiry uses the phone clock vs.
   the teacher's `endTime`); a teacher stop reaches an offline student only after reconnecting;
   mid-session attendance edits do not change `presentIds`.
9. A stop document with a non-numeric `endTime` is rejected (never written by the app).

## 9. Build and test on Windows

See `docs/BUILD_SETUP.md` for the exact steps. In short, from the project folder:

    gradlew.bat testDebugUnitTest assembleDebug

then install `app\build\outputs\apk\debug\app-debug.apk` and work through `docs/TEST_PLAN.md`.
