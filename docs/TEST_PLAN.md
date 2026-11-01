# FocusLock – Manual Test Plan (device / emulator required)

**Status of every "Actual result": NOT EXECUTED.** No device or emulator was
available while this plan was written. Fill the column in on your phone.
Unit tests (`LockSessionTest`, `LockPolicyTest`, `BlockLaunchGateTest`) cover
only the pure decision logic, not real foreground-app behaviour.

## Common setup
* Teacher phone (or second account) and student phone, both with the new APK.
* Student logged in, FocusLock accessibility service ON (Android 13+ sideload:
  App info -> menu -> *Allow restricted settings* first).
* Teacher marks the student **Present** for today, then starts a lock with
  e.g. Instagram + YouTube blocked, 15 minutes (unless the test says otherwise).
* Student phone connected via USB: `adb logcat -v time -s FocusLockDebug`.

Legend: **Device** = needs physical device/emulator. Expected log lines are in
`FocusLockDebug`.

---

### 1. Teacher starts a lock; student opens a blocked app  (Device)
* **Pre:** Setup above; student on home screen.
* **Steps:** Tap Instagram.
* **Expected:** Block screen "Focus Time ... Locked until HH:MM" within ~0.5 s.
  Log: `SESSION UPDATED (server)`, `BLOCK pkg=com.instagram.android ... attempt=1/3`.
* **Actual:** _not executed_
* **Limit:** Instagram may be visible for the launch latency (a few hundred ms).

### 2. Student rapidly reopens a blocked app  (Device)
* **Steps:** Tap Instagram -> block screen -> press *Go Home* -> immediately tap
  Instagram again; repeat 10 times as fast as possible.
* **Expected:** Block screen every time. No gap of ~1 s (old bug). Log shows a
  `BLOCK` per cycle; any `SKIP duplicate launch ... re-check in` line is followed
  by a later `ALLOW ... SELF_PACKAGE` or another `BLOCK`.
* **Actual:** _not executed_

### 3. Switch between two blocked apps  (Device)
* **Steps:** Open Instagram (blocked) -> Home -> YouTube (blocked) -> Home -> Instagram.
* **Expected:** Block screen for each.
* **Actual:** _not executed_

### 4. Allowed app <-> blocked app  (Device)
* **Steps:** Open Calculator (allowed) -> Instagram -> Calculator -> Instagram.
* **Expected:** Calculator opens normally; Instagram is blocked each time.
* **Actual:** _not executed_

### 5. Back on the block screen  (Device)
* **Steps:** With block screen shown press Back / use back gesture; repeat 5 times.
* **Expected:** Launcher appears; blocked app is never shown. The block screen
  stays alive in the background (no `finish()`).
* **Actual:** _not executed_

### 6. Recents -> blocked app  (Device)
* **Steps:** Block screen shown -> open Recents -> pick Instagram card.
* **Expected:** Block screen again. The block screen itself is not listed
  (excludeFromRecents).
* **Actual:** _not executed_
* **Limit:** OEM Recents may show a thumbnail of the blocked app (content visible
  in the preview only).

### 7. Return to blocked app from home screen  (Device)
* **Steps:** Open Instagram while the lock is active, go Home, tap Instagram again,
  also try opening it by tapping a notification.
* **Expected:** Block screen each time.
* **Actual:** _not executed_

### 8. Screen off / on  (Device)
* **Steps:** With Instagram blocked and block screen showing, press power, wait
  5 s, press power again, unlock.
* **Expected:** Block screen (or launcher) after unlock, never Instagram.
  Log: `Screen off: polling paused`, then `evaluate ... trigger=screen:...`.
* **Actual:** _not executed_

### 9. Device locked / unlocked  (Device)
* **Steps:** Open Instagram (blocked), lock phone with keyguard, unlock.
* **Expected:** Same as 8.
* **Actual:** _not executed_

### 10. Accessibility service disconnects and reconnects  (Device)
* **Steps:** Settings -> Accessibility -> FocusLock OFF, then ON, while the lock is
  running; open Instagram.
* **Expected:** Logs `SERVICE UNBOUND`, `SERVICE DESTROYED`, then `SERVICE CONNECTED`
  and `RESTORED persisted session`; Instagram blocked immediately.
* **Actual:** _not executed_
* **Limit:** While OFF the student can use any app (see 18).

### 11. App process recreated  (Device)
* **Steps:** `adb shell am kill com.example.focuslock` (or swipe away on a phone
  that kills the service), wait for the service to reconnect, open Instagram.
  Optionally put the phone in airplane mode first to exercise the cache-miss path.
* **Expected:** `RESTORED persisted session` on reconnect; Instagram blocked
  even before the first Firestore snapshot; a later
  `Session doc absent in local cache; NOT authoritative` does not unlock.
* **Actual:** _not executed_
* **Limit:** If the OEM does not rebind the service after a kill, nothing runs
  until the user re-enables it (see Remaining limitations).

### 12. Wi-Fi / data disconnected temporarily  (Device)
* **Steps:** Airplane mode ON for 2 minutes, try Instagram, airplane mode OFF.
* **Expected:** Blocked throughout (no unlock). No `SESSION ENDED` log.
* **Actual:** _not executed_

### 13. Firestore listener reports an error  (Device)
* **Steps:** Temporarily set Firestore rules to deny reads of the session doc,
  wait for the error; try Instagram; restore the rules.
* **Expected:** Log `FIRESTORE LISTENER ERROR code=PERMISSION_DENIED ... keeping last
  valid session ... re-attaching in 2000ms` (2 s, 4 s, ... 60 s). Instagram stays
  blocked. After rules are restored a new listener attaches and updates flow again.
* **Actual:** _not executed_
* **Limit:** While the listener is failing, a teacher stop cannot be received.

### 14. endTime reached  (Device)
* **Steps:** Start a 1-minute lock, wait. Try Instagram after expiry; also test
  with the block screen showing when the time passes.
* **Expected:** Block screen dismisses itself; `SESSION ENDED: endTime reached`;
  Instagram opens normally.
* **Actual:** _not executed_
* **Limit:** Uses the **student device clock** (see Remaining limitations).

### 15. Teacher explicitly stops the session  (Device)
* **Steps:** Teacher taps Stop Lock while student is on the block screen.
* **Expected:** `SESSION ENDED: teacher stopped the session (server)`; block screen
  closes; Instagram usable.
* **Actual:** _not executed_
* **Limit:** If the student is offline the stop is received only after
  reconnecting.

### 16. Eligibility changes through a valid session update  (Device)
* **Steps:** Teacher un-marks the student Present and **starts the lock again**
  (marking attendance alone does not rewrite the session document).
* **Expected:** `SESSION UPDATED ... eligible=false`, block screen dismissed,
  `No enforcement: session state=NOT_ELIGIBLE`.
* **Actual:** _not executed_
* **Limit:** Existing behaviour retained: attendance edits made mid-session do not
  change `presentIds` in the session document.

### 17. Default launcher, dialer and keyboard keep working  (Device)
* **Steps:** Start a **Block ALL** lock. Use the launcher, make a call to a normal
  number, use the on-screen keyboard in the allowed app, open emergency dialer
  from the lock screen.
* **Expected:** None of them is blocked (`ALLOW ... EXEMPT_PACKAGE` or SELF); every
  other app is.
* **Actual:** _not executed_
* **Limit:** Exempt packages are resolved from the installed launchers, default
  dialer and default IME; OEM-specific in-call packages not in the static list
  would be blocked in Block ALL mode. Add them to `STATIC_EXEMPT` if you see one.

### 18. Student tries to disable the accessibility service  (Device)
* **Steps:** Settings -> Accessibility -> FocusLock -> OFF while locked, in both
  *selected apps* and *Block ALL* mode.
* **Expected (honest):** In *selected apps* mode Settings is not blocked, so the
  student **can** switch it off and bypass the lock. In *Block ALL* mode Settings
  is not exempt and is blocked, but the student can still reach it through paths
  that do not produce a detectable window (for example the status-bar quick
  settings panel).
* **Actual:** _not executed_
* **Limit:** Cannot be fixed inside an ordinary app. See Remaining limitations
  (device-owner provisioning).

### 19. Block screen cannot be launched -> degraded recovery  (Device)
* **Steps:** With a lock running, make launching fail, e.g. (suggested, untested)
  `adb shell pm disable com.example.focuslock/com.example.focuslock.BlockActivity`.
  Open a blocked app repeatedly (also reopen it right after being sent Home).
  Then `adb shell pm enable ...` and open the blocked app again after > 60 s.
* **Expected:** Log: 3x `BlockActivity launch FAILED`, `RECOVERY DEGRADED`, then
  `ENFORCE HOME` on **every** blocked detection (no gap, no new run of launches);
  `PROBE` lines at growing intervals (2 s, 4 s, ... 60 s); after re-enabling, the next
  `PROBE` shows the block screen and `RECOVERY CLEARED` appears.
* **Actual:** _not executed_
* **Limit:** In degraded mode the student sees Home instead of the block screen.

### 20. Incomplete session document  (Device + Firebase console)
* **Steps:** While a lock is running, delete the `presentIds` field (then, separately,
  `blockedApps`) of `.../session/current` in the console.
* **Expected:** Log `Malformed session document (server): mandatory field 'presentIds' is
  missing ...`; enforcement continues with the last valid session; restoring the field
  is picked up. A document with `blockedApps: []` and `blockAll: true` is **accepted**;
  an active document with `presentIds: []`, or with `blockAll: false` and `blockedApps: []`,
  is **rejected** with a logged reason.
* **Actual:** _not executed_

### 21. Split-screen  (Device)
* **Steps:** (a) Open an allowed app and a blocked app in split-screen and touch the
  blocked pane. (b) Blocked video playing in one pane, interact only with the other.
* **Expected (to be confirmed):** (a) blocked app becomes active -> `BLOCK ...`.
  (b) NOT enforced; `MULTIWINDOW blocked app visible but NOT enforced` is logged.
  Record how the block screen is placed and whether Home leaves split-screen.
* **Actual:** _not executed_

### 22. Picture-in-picture  (Device)
* **Steps:** Start a video in a blocked app, press Home so it floats as PiP.
* **Expected (to be confirmed):** the blocked app is already in front when entering PiP
  (blocked then); once floating, it is NOT closed - only logged as `[picture-in-picture]`.
* **Actual:** _not executed_



---

## Personal User flow (added with the blank-screen fix - see `PERSONAL_USER_FIX.md`)

**Status of every "Actual result": NOT EXECUTED on a device.** Needs an emulator/phone with
internet. Email/Password sign-in must be enabled in the Firebase console and Firestore rules
must allow read/write of `users/{uid}` for the signed-in user.

| # | Steps | Expected | Actual |
|---|-------|----------|--------|
| P1 | Launch -> Personal User | Email / Password / parent phone / PIN form, Login + Register buttons | _not executed_ |
| P2 | Register with valid new email, 6+ char password, 10-digit phone, 4-digit PIN | Spinner, buttons disabled, then **Personal User Dashboard** with your email | _not executed_ |
| P3 | Register with bad email / short password / bad phone / bad PIN | Error shown on the offending field, no network call | _not executed_ |
| P4 | Register an already-used email | "This email is already registered. Try logging in instead" | _not executed_ |
| P5 | Log out (dashboard) -> Personal User -> Login with correct credentials | Dashboard shows | _not executed_ |
| P6 | Login with wrong password / unknown email | "Incorrect email or password"; no dashboard | _not executed_ |
| P7 | Airplane mode -> Login | "No internet connection..." message; buttons re-enabled | _not executed_ |
| P8 | On dashboard press **Log out** | Returns to FocusLock role-selection screen; Back does not return to dashboard | _not executed_ |
| P9 | Log in, swipe app away, relaunch -> Personal User | Goes straight to dashboard (no form) | _not executed_ |
| P10 | Log out, relaunch -> Personal User | Form shown (no automatic navigation) | _not executed_ |
| P11 | Rotate device while the spinner is showing | Spinner stays; result still handled once | _not executed_ |
| P12 | `adb logcat -s UserLoginActivity UserHomeActivity` during P2/P6 | Diagnostic lines, no password/PIN/email | _not executed_ |


---

## Personal User app locking (added with PERSONAL_LOCKING.md)

**Status of every "Actual result": NOT EXECUTED.** Needs a phone/emulator, a signed-in Personal
User, and FocusLock accessibility switched on. Watch logs with
`adb logcat -v time -s FocusLockDebug UserHomeActivity`. Pick two harmless apps (e.g. Chrome,
Calculator) as targets.

| # | Steps | Expected | Actual |
|---|-------|----------|--------|
| L1 | Fresh login, accessibility OFF, tap **Start Lock Session** | Nothing starts; explanation dialog, then Open settings goes to Android Accessibility | _not executed_ |
| L2 | Turn FocusLock on in Accessibility, return | Card says ON / enforcement ready | _not executed_ |
| L3 | Select apps: search, tick two, Select all shown, Clear, tick two again, Save | Counter correct (max 50), dashboard lists the names | _not executed_ |
| L4 | Kill the app, relaunch, open dashboard | Same apps still selected | _not executed_ |
| L5 | No apps selected -> Start | "Select at least one app" and no session | _not executed_ |
| L6 | 1 min preset/custom, Start | Status Active, countdown, "Started/Ends", restricted apps listed | _not executed_ |
| L7 | Home, open a selected app | FocusLock block screen naming the app, with remaining time, within ~0.5 s | _not executed_ |
| L8 | On the block screen: Go Home / Back / Open FocusLock | Home / Home / dashboard opens (FocusLock is never blocked) | _not executed_ |
| L9 | Open a non-selected app, Settings, dialer | All open normally | _not executed_ |
| L10 | Rapidly reopen a selected app 10 times | Block screen every time, no stacked screens | _not executed_ |
| L11 | Wait for expiry with the dashboard closed, open the app | Opens normally; dashboard shows Completed + history line | _not executed_ |
| L12 | Start 15 min, **End session** -> Keep focusing, then End session -> confirm | Dialog both times; after confirm apps open at once; status Completed (ended early) | _not executed_ |
| L13 | Start, switch accessibility OFF in Settings | Dashboard warns apps are NOT restricted; timer continues; apps open | _not executed_ |
| L14 | Start 15 min, change the phone clock +2 h, open dashboard | Remaining time barely changes (monotonic) | _not executed_ |
| L15 | Start 15 min, reboot, wait for service, open selected app | Still blocked until the original end; Completed afterwards | _not executed_ |
| L16 | Start, uninstall one selected app | No crash; still enforcing the other | _not executed_ |
| L17 | Start, log out, log in as ANOTHER account | Other account sees "active (another account)" notice, empty selection, no End button | _not executed_ |
| L18 | While a School lock is active on this device try Start | Refused with the School notice | _not executed_ |
| L19 | Personal session active, teacher starts a School lock | School list is enforced; after it ends the personal session resumes | _not executed_ |
| L20 | Run School cases 1-22 above again | Unchanged | _not executed_ |
