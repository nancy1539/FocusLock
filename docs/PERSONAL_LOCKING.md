# Personal User app locking

## How School/College locking works (traced before changing anything)
1. `ClassActivity.startLock` writes `schools/{code}/classes/{id}/session/current` (active, endTime, blockAll, blockedApps, presentIds).
2. `BlockerService` (the only AccessibilityService) listens to that document for the identity saved by `StudentLoginActivity` in the `focus` prefs, parses it with `LockSession.parse`, persists it in `SessionStore`, and arms an expiry timer.
3. Every trigger (window events, 1 s poll, screen on, verify, expiry) calls `evaluate()`: `LockPolicy.sessionState/decide` -> exemptions -> `enforce()` -> `BlockLaunchGate` (de-dup / retry / go Home) -> `BlockActivity` (singleTask, own task).
4. The session ends on teacher stop, server doc deletion or `endTime`; `endSession` clears the store and dismisses the block screen.

Observations: this design is sound and was reused as is. Things I did **not** change: the service is declared `exported="false"` (the system still binds it; the conventional declaration is `true` + `BIND_ACCESSIBILITY_SERVICE`, left alone because it could not be tested here); with School `blockAll` the Settings app is blockable (existing behavior, kept).

## Personal design (one shared engine)
| Piece | Role |
|---|---|
| `PersonalSession`, `PersonalSessionRepository`, `PersonalSessionController` | Durable personal session (id, ownerUid, packages, start/duration, status ACTIVE/COMPLETED/CANCELLED), atomic writes, one process-wide lock. Pure Java. |
| `EnforcementPolicy` | The single "what is enforced now" value: **School > Personal > None**. Personal sessions are normalized into a `LockSession` and decided by the **same** `LockPolicy.decide`. |
| `BlockerService` | `evaluate()` now resolves one `EnforcementPolicy` per pass. School code paths are untouched. Expiry timer, block screen and `BlockLaunchGate` are shared. |
| `EssentialPackages`, `DeviceExemptions` | Never-block lists (School's set moved verbatim; Settings/installer/permission screens added for Personal only). Used by both service and picker. |
| `AppPickerActivity`, `InstalledApps`, `AppEligibility` | Launchable, non-essential apps only; search; select-all limited to the shown list and 50 apps; reconcile removes uninstalled apps (never wipes when enumeration returns nothing). |
| `UserHomeActivity` + `PersonalDashboardState` | Dashboard renders pure state: accessibility ready/disabled/starting, phase, start blocker. |

Rules: starting is idempotent (second start returns the running session); a personal start while a School session is enforced is refused; a School session never gets replaced and wins when both exist; cancel only touches the personal record and needs the owning uid and the current session id; logout does **not** end a session (that would be an exit) - another account sees only a notice and cannot cancel; selections are stored per uid.

Timekeeping: same boot = monotonic clock only (changing the date cannot shorten/extend); after reboot = wall clock, capped at the chosen duration. Expiry is applied lazily on every read, plus a service timer for prompt dismissal; correctness never depends on a timer. No foreground service, no alarms, no notifications, so no extra permissions.

## Firebase
No change. Personal sessions are **local only** (SharedPreferences `focuslock_personal`); no Firestore schema or rules changes, nothing to do in the console beyond what `PERSONAL_USER_FIX.md` already lists (Email/Password enabled; `users/{uid}` rules).
If you later sync sessions, use `users/{uid}/sessions/{id}` with `allow read, write: if request.auth != null && request.auth.uid == uid;` and never accept an owner id from the client.

## Android notes
* Package visibility: only `<queries>` for the HOME and LAUNCHER intents. No `QUERY_ALL_PACKAGES`, so no Play "all packages" declaration is needed. Apps without a launcher icon are intentionally not listed.
* Gradle: only addition is `androidx.recyclerview:recyclerview:1.3.2` (the picker used to rely on it transitively).
* Android 13+ sideloaded builds: accessibility may need *Allow restricted settings* first.
* Android 12+ exports: all new activities `exported="false"`. No PendingIntents, notifications or foreground services are used.

## Known limits (cannot be fixed with supported APIs)
* The accessibility switch can be turned off, the app force-stopped, or the device booted to safe mode: enforcement stops (the dashboard warns while the app is open; the timer keeps running).
* After a reboot the service starts only once Android binds it; a short gap is possible.
* A blocked app can be visible for a few hundred ms before the block screen covers it; split-screen/PiP secondary windows are logged but not closed (same as School).
* The block screen is launched by the service; OEM background-launch restrictions can degrade it to "go Home" (existing `BlockLaunchGate` behavior).
* Clock changes while powered off cannot be detected.
* Early termination is allowed by design. `PersonalSessionController.CancelPolicy` is the hook for a future strict mode; no fake strict mode is shipped.
* Reinstalling an app with the same package name during a session leaves it blocked (the session keeps its package set).

## Verification status
See the implementation report in the chat: unit tests ran on the JVM with a JUnit shim; the Android layer was **not** compiled with Gradle or run on a device in the build sandbox.
