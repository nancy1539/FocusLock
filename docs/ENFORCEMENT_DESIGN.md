# FocusLock – Lock Enforcement Design

## 1. Responsibilities and where they live

| # | Responsibility | Code |
|---|----------------|------|
| 1 | Session validation (parse, malformed vs. stop) | `LockSession.parse` |
| 2 | Student eligibility | `LockSession.includesStudent`, `LockPolicy.sessionState` |
| 3 | Package classification (blocked / allowed / exempt) | `LockPolicy.decide`, `BlockerService.isExempt` |
| 4 | Foreground package detection | `BlockerService.detectForeground` |
| 5 | Blocking decision (single source of truth) | `BlockerService.evaluate` -> `LockPolicy.decide` |
| 6 | Block-screen launch and de-duplication | `BlockerService.enforce`, `BlockLaunchGate`, `BlockActivity` |
| 7 | Persistence and Firestore | `SessionStore`, `BlockerService.ensureListener / onSessionSnapshot` |
| 8 | Recovery and lifecycle | poll, verify, expiry timer, screen receiver, listener re-attach |

Every trigger (window-state event, windows-changed event, 1 s poll, verify,
screen-on / user-present, expiry timer, session update, service connect) calls
the same `evaluate()`; none of them has its own blocking rule.

## 2. Definitions

* **Valid active session** – parsed document with `active == true`, a numeric
  `endTime > 0`, and `now < endTime`.
* **Expired** – `now >= endTime` (wall clock, checked on every decision).
* **Teacher-stopped** – document with `active == false`.
* **Eligible student** – `studentId` non-empty and contained in `presentIds`.
* **Blocked application** – `(blockAll || package in blockedApps)` and not exempt
  and not FocusLock itself.
* **Allowed application** – everything else, always including FocusLock and the
  exempt set.
* **Exempt set** – static list in `BlockerService.STATIC_EXEMPT` (system UI,
  telephony/emergency, common dialers) plus, resolved at decision time, every
  installed HOME launcher, the default dialer and the default keyboard.
  Exemptions win over both `blockAll` and an explicit `blockedApps` entry.
* **Unavailable session state** – `session == null` (nothing received, nothing
  persisted, or ended). Treated as *not locked*. Missing/invalid data never
  keeps a student locked.

## 3. Failure classification

| Situation | Detected by | Effect on a valid cached session |
|-----------|-------------|----------------------------------|
| Temporary network loss / offline | SDK serves cache; no error to listener | **Kept.** Enforced until `endTime`. |
| Firestore listener error (e.g. PERMISSION_DENIED, UNAVAILABLE) | `error != null` | **Kept.** Dead listener removed, new one attached with exponential back-off (2 s .. 60 s). |
| Cache-miss snapshot (`!exists && fromCache`) | `getMetadata().isFromCache()` | **Kept.** Not authoritative. |
| Server confirms doc does not exist | `!exists && !fromCache` | Ended (no session). |
| Malformed / incomplete document (incl. active session without `blockedApps` or `presentIds`, see section 8) | `LockSession.parse` returns error | **Kept.** Update ignored, the reason is logged (`Malformed session document ... : <reason>`). Cannot lock forever because the kept session still expires. |
| Explicit teacher stop | `active == false` | Ended immediately; persisted state cleared; block screen dismissed. |
| Expiry | `now >= endTime` on any decision, plus a timer | Ended; persisted state cleared; block screen dismissed. |
| Eligibility change (new session without this student) | `NOT_ELIGIBLE` | Not enforced; block screen dismissed. |
| Service / process recreation | `onServiceConnected` | Persisted session restored if same owner, schema version known, not expired. |

## 4. Events and recovery

* Subscribed: `typeWindowStateChanged` (primary), `typeWindowsChanged` (added /
  removed / active / focus changes only on API 28+, debounced to one pending
  evaluation).
* Not subscribed: `typeWindowContentChanged` (noisy, not needed).
* `notificationTimeout` is 0 so the framework cannot coalesce quick reopen events.
* Foreground = active TYPE_APPLICATION window -> focused TYPE_APPLICATION
  window -> active window root -> (last resort) event package.
* If the event package and the window snapshot disagree, a single re-verify is
  scheduled 150 ms later instead of guessing.
* Poll: 1 s, exactly one pending callback, only while a session is enforced,
  the screen is interactive, and window information is available. It runs the
  same `evaluate()` path.
* Screen on / user present trigger an immediate evaluate; screen off pauses the poll.

## 5. Launch de-duplication and recovery (`BlockLaunchGate`)

There is no "ignore events for N ms" cooldown: **every** detection of a blocked
foreground gets a protective action (launch, a bounded wait for a launch that is
already in flight, or Home). The service always schedules a re-check.

**NORMAL mode**

1. Blocked package detected, nothing in flight -> **LAUNCH** now.
2. Launch in flight (< 350 ms old) -> **WAIT_FOR_PENDING** (re-check scheduled).
3. In flight but older than 350 ms and the blocked app is still in front -> that
   launch failed -> **LAUNCH** again.
4. After 3 unconfirmed launches -> enter **DEGRADED** mode and **GO_HOME**.

**DEGRADED mode** (the block screen demonstrably does not take over)

* Every detection of a blocked foreground -> **GO_HOME** immediately
  (`GLOBAL_ACTION_HOME`; does not depend on starting an Activity from the
  background). Home is never delayed or suppressed by back-off.
* Exponential back-off (2 s, 4 s, ... capped at 60 s) applies **only** to launch
  **probes**: when one is due, that detection yields **PROBE** (one launch) instead
  of Home. A probe that has not produced a confirmed block screen after 350 ms
  counts as failed and the next probe is pushed out. The blocked app can be visible
  for at most one 350 ms launch window per probe - the same exposure as a normal launch.
* The previous behaviour reset all counters on GO_HOME, so after Home (launcher in
  front = "allowed app") the next reopen of the blocked app began a new run of
  launches with no back-off. That loop is gone.

**Leaving recovery** (the only two ways)

* **Protection confirmed:** foreground window is FocusLock's own package **and**
  `BlockActivity` is resumed (`BlockActivity.isBlockScreenResumed()`). Clears
  attempts, degraded state, back-off, counters (`RECOVERY CLEARED` is logged when
  anything beyond a single clean launch was cleared).
* **Session ends / changes / identity changes / student no longer eligible**
  (`BlockLaunchGate.reset()`).

An *allowed* foreground (launcher after Home, another app, FocusLock's own
non-block screens) deliberately does **not** clear recovery state.

**Diagnostics** (tag `FocusLockDebug`): `BLOCK ... gate{mode=.. attempts=.. launches=..
homes=.. probeFailures=..}`, `RECOVERY DEGRADED`, `PROBE`, `ENFORCE HOME ... gate{...
nextProbeIn=..}`, `RECOVERY CLEARED`, `PROTECTED`, `BlockActivity launch FAILED`.

`BlockActivity` is `singleTask` in its own task, so even a redundant launch
only delivers `onNewIntent()` and cannot stack or loop.

## 6. Permissions and capabilities

* No new manifest permissions. No foreground service, boot receiver or overlay.
* `canRetrieveWindowContent="true"` + `flagRetrieveInteractiveWindows`: required
  for `getWindows()`; the only thing read is a window root's package name.
  Android shows this capability in the Accessibility permission dialog.
* `exported="false"` on the service is unchanged (the system binds with
  `BIND_ACCESSIBILITY_SERVICE`).

## 7. Multi-window cases: what AccessibilityService can and cannot enforce

Basis: documented behaviour of `AccessibilityService.getWindows()` (default display
only, interactive windows only, needs `flagRetrieveInteractiveWindows` +
`canRetrieveWindowContent`), `AccessibilityWindowInfo` (`isActive`, `isFocused`,
`isInPictureInPictureMode` API 26+) and `performGlobalAction`. **None of the
multi-window behaviour below has been verified on a device.**

Rule implemented: the *active* TYPE_APPLICATION window is evaluated (fallback:
focused window, then active root, then event package). Other visible application
windows are only **logged** (`MULTIWINDOW ... NOT enforced`).

| Case | Enforced? | Why |
|------|-----------|-----|
| Single full-screen app | Yes (event, `windowsChanged`, 1 s poll, verify) | Active window is unambiguous. |
| Split-screen / freeform: blocked app is the **active** window (student touched or focused it) | Yes, at the moment it becomes active | Active-window change triggers `windowsChanged`. How the block screen is placed (full screen vs. one pane) is OEM-defined and untested; the blocked pane could stay visible next to it. |
| Split-screen / freeform: blocked app in the **passive** pane (e.g. video playing while the student works in the other pane) | **No** | Only the active window is evaluated. Logged as `MULTIWINDOW`. It is enforced as soon as the student touches/focuses it. |
| Picture-in-picture window of a blocked app | **No** | The service can detect it (`isInPictureInPictureMode`, logged) but has **no API to close or hide another app's PiP window**; Home does not remove it and launching the block screen does not remove it. Enforcing would only loop Home every poll and make the phone unusable, so it is deliberately not attempted. |
| Secondary display (external monitor, desktop mode, casting) | **No** | `getWindows()` covers the default display only; `getWindowsOnAllDisplays()` (API 30+) is not used. |
| Bubbles / other overlay-style windows | Only if they are the active application window | Not verified how each OEM reports them. |
| Window whose root cannot be read (`getRoot()` null) | Falls back to `getRootInActiveWindow()` / event package | Package cannot be read from a window the framework does not expose. |
| Audio, downloads, calls, notifications of a blocked app | **No** | Not a foreground window; out of scope for an accessibility service. |
| Recents thumbnails, status-bar panels, system dialogs | **No** | System UI is exempt (`com.android.systemui`, `android`). |
| Exiting split-screen via `GLOBAL_ACTION_HOME` | Unknown | Behaviour differs by Android version/OEM. |

Not claimed: that a student cannot see a blocked app for a short time (launch
latency, up to ~350 ms per launch/probe), nor that any of this is stronger than
what an ordinary app can do. Real guarantees (suspending packages, lock-task
mode) need device-owner provisioning.

## 8. Session document validation (`LockSession.parse`)

Existing schema (`ClassActivity.startLock`, full `set()`): `active`, `endTime`,
`blockAll`, `blockedApps`, `presentIds`, `date`. Stop is `update("active", false)`.

* `active == false` -> accepted as an explicit stop, other fields irrelevant.
* `active == true` requires: numeric `endTime > 0`; **`blockedApps` present, a list of
  non-blank strings**; **`presentIds` present, a list of non-blank strings**; `blockAll`
  a boolean if present (absent = list mode).
* Why mandatory: `startLock` always writes both; the teacher UI cannot start a lock
  without at least one present student and, unless *Block ALL* is chosen, at least one
  app. A **missing** list is therefore a damaged/partial document. Reading it as an
  empty set would silently disable enforcement (`presentIds`) or turn a list lock into
  one that blocks nothing (`blockedApps`).
* **Semantic checks:** `presentIds` must be **non-empty** (an active session must target
  at least one student); `blockedApps` must be **non-empty when `blockAll` is false/absent**
  (it would block nothing). `blockedApps == []` **is accepted when `blockAll` is true**
  (`startLock` writes the checkbox selection, which may be empty, next to `blockAll == true`).
* Rejected documents are not applied: the service logs
  `Malformed session document (...): mandatory field 'presentIds' is missing ...` and keeps
  its last valid session. If there was none, nothing is enforced (fail-open, unchanged).
* `SessionStore.load` applies the same semantic rules to the persisted copy through the
  shared `LockSession.activeSessionProblem(...)`; a record that fails them (for example one
  written by an older build) is cleared and logged, not restored.
