# Battery Monitor — Requirements (v1)

Android-only, personal use. Kotlin + Jetpack Compose. APK built by GitHub Actions.
Min SDK 26 (Android 8), target latest. Fully offline, no accounts.

## Goals

1. **Charging:** measure how long each 1% takes, predict the time to full, then compare the prediction with the actual result.
2. **Discharging:** track screen-on time per app, measure the drain rate, and predict when the battery will run out.
3. **Background:** keep running while the app is closed.
4. **UX:** show a charging animation and suggest ways to save battery.

---

## F1. Charge Tracking

| ID | Function | Description |
|---|---|---|
| F1.1 | `onPowerConnected()` | Start a **charge session**: start %, start time. |
| F1.2 | `onLevelChanged(level)` | Record a timestamp for each 1% gain. Save the step as `LevelStep(pct, timestamp, secondsTaken)`. |
| F1.3 | `calcChargeRate()` | Average seconds per 1% so far in the session, weighted toward the latest steps. |
| F1.4 | `estimateTimeToFull()` | **First estimate after 1 minute of charging** (from % gained or rate so far). Split: up to 80% at the current rate, 80→100% at a slower rate learned from past sessions. **One charge curve for all chargers** (kept simple). Recalculated on every 1% step. Shows "Full at HH:MM (Xh Ym)". |
| F1.5 | `snapshotEstimate()` | Save the prediction at 1 minute, at 50%, and at 80% (only the points the session actually passes). |
| F1.6 | `onFullyCharged()` | At 100%, record the actual time and score each snapshot: error in minutes and an accuracy %. |
| F1.7 | `scorePartial()` | **A partial charge (unplugged early) is scored too:** for each snapshot, predict when the session's end % would be reached and compare it with the actual time. |
| F1.8 | `learnCurve()` | After each session, update the stored rate for below 80% and for 80–100%. Future estimates use it. |
| F1.9 | `onPowerDisconnected()` | Close the session: end %, duration, average min per 1%, complete or partial. |

**Charge session screen**
- **Graph:** % on the Y axis, time on the X axis. Actual line, plus a dashed line for the predicted path.
- **Step table:** time for each 1% step (e.g. 45→46%: 1m 05s). No temperature.
- **"Combined" button:** switches the table to grouped totals, 5% per row (e.g. 45→50%: 5m 40s), with a running total ("45%→current: 23m").
- **Accuracy card:** predicted vs actual for each snapshot.

## F2. Discharge & App Usage Tracking

The same idea as charging, applied to battery use.

| ID | Function | Description |
|---|---|---|
| F2.1 | `onPowerDisconnected()` → `startDischargeSession()` | Start a **discharge session**: start %, start time. |
| F2.2 | `onLevelChanged(level)` | Record the time for each 1% drop and which app(s) were in front during it. |
| F2.3 | `onScreenOn()` / `onScreenOff()` | Log screen intervals. Drops while the screen is off are counted as "Screen off / idle". |
| F2.4 | `trackForegroundApp()` | Uses `UsageStatsManager` to record the app in front and for how long. Needs **Usage Access**, which the user grants in Settings. |
| F2.5 | `attributeDrain()` | Split each 1% drop across the apps in the foreground during it, by time. **This is an estimate:** Android gives no true per-app battery data. |
| F2.6 | `calcDrainRate()` | Minutes per 1%, overall and per app (e.g. YouTube: 1% every 3m, WhatsApp: 1% every 9m). |
| F2.7 | `estimateTimeToEmpty()` | **First estimate after 1 minute.** Current % × blended drain rate (screen-on and screen-off, weighted by your usage pattern). Recalculated on every 1% drop. Shows "Empty at HH:MM (Xh Ym)". |
| F2.8 | `snapshotEstimate()` | Save the prediction at 1 minute, at 50%, and at 20% (only the points the session passes). |
| F2.9 | `scoreDischarge()` | At plug-in (partial) or at 0–1%: compare each snapshot with the actual time the end % was reached. Store the error and accuracy. |
| F2.10 | `getAppUsageReport(session / today / 7 days)` | Per app: screen time, % used (estimated), minutes per 1%. Sorted by battery used. |

**Discharge session screen**
- **Graph:** % over time, colored by the app in front (or screen off). Dashed predicted line to 0%.
- **Step table:** time for each 1% drop and the main app during it.
- **"Combined" button:** grouped per 5%, plus a running total.
- **Top apps list:** "YouTube: 18% · 54m screen time", and so on.
- **Accuracy card:** predicted vs actual.

## F3. Background Service

| ID | Function | Description |
|---|---|---|
| F3.1 | `BatteryMonitorService` | A foreground service that keeps running, with a persistent notification. |
| F3.2 | `updateNotification()` | While charging: "72% · full in 38m". On battery: "72% · ~6h 10m left". |
| F3.3 | `BootReceiver` | Restarts the service after the phone reboots. |
| F3.4 | `requestIgnoreBatteryOptimizations()` | Asks to be exempt from Doze so the system doesn't kill the service. |
| F3.5 | Sampling | Mostly event-driven through the battery and screen broadcasts. App polling happens only while the screen is on, about every 5 seconds, to keep the monitor's own drain low. |

## F4. UI

| ID | Screen / Function | Description |
|---|---|---|
| F4.1 | **Dashboard** | Large %, status, ETA (to full or to empty), temperature, current in mA if the device reports it. |
| F4.2 | `ChargingAnimation` | Charging animation while plugged in. The design will be decided later; v1 uses a placeholder. |
| F4.3 | **History** | List of charge and discharge sessions. Tap one to open its session screen (see F1 / F2). |
| F4.4 | **App Usage** | Screen time and estimated drain per app, today and for the week. |
| F4.5 | **Suggestions** | Output of F5, each with an "Open setting" button. |
| F4.6 | **Settings** | Alert thresholds, and a toggle for the permission the monitor is waiting on. |

## F5. Optimization Suggestions

Rule-based, with no network. Each suggestion opens the matching system settings screen, because Android doesn't let apps change these settings themselves.

| ID | Rule | Suggestion |
|---|---|---|
| F5.1 | Brightness high or auto-brightness off | Turn on adaptive brightness |
| F5.2 | Screen timeout > 1 min | Reduce the screen timeout |
| F5.3 | An app is > 25% of the estimated drain | "App X used about N% today". Opens that app's battery settings. |
| F5.4 | Screen-off drain > 1.5%/h | High standby drain: check background apps and syncing |
| F5.5 | Battery temperature > 40 °C while charging | Remove the case or stop using fast charging |
| F5.6 | Regularly charging to 100% | Consider an 80% charge limit if the phone supports one |
| F5.7 | Battery saver off at < 20% | Turn on battery saver |

## F6. Alerts (v2 — not in v1)

- F6.1: charged to a set % (for example 80%), unplug.
- F6.2: fully charged.
- F6.3: high temperature.

## Data (Room DB)

- `Session(id, type[charge|discharge], start, end, startPct, endPct, complete)`
- `LevelStep(sessionId, pct, timestamp, secondsTaken, topApp)`
- `StepApp(stepId, packageName, foregroundMs)`
- `CurveStats(rateBelow80, rate80to100, dischargeOnRate, dischargeOffRate)`
- `Estimate(sessionId, madeAtPct, madeAt, predictedEnd, actualEnd, errorMin)`
- `ScreenInterval(start, end)`
- `AppUsage(date, packageName, foregroundMs, estDrainPct)`
- Retention: 90 days. Older rows are deleted daily.

## Permissions

- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`
- `POST_NOTIFICATIONS`
- `RECEIVE_BOOT_COMPLETED`
- `PACKAGE_USAGE_STATS`: special permission, granted in Settings
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

## Known Limits

- Per-app drain is an **estimate** based on foreground time. Only system/root apps can read true per-app power use.
- Current in mA is unreliable on some devices: wrong sign or wrong units. The app must work on any phone: `normalizeCurrent()` detects µA vs mA from the value's size (>10,000 means µA) and fixes the sign using the charging state. If the device reports nothing, the mA field is hidden. It's display-only and not used in the estimates.
- The first few sessions will be less accurate until the charge curve has been learned.

## Dependencies

| Need | Library |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose |
| Database | Room (with KSP) |
| Settings storage | DataStore Preferences |
| Background cleanup (90-day purge) | WorkManager |
| Dependency injection | Hilt |
| Charts | Vico (Compose charts) |
| Charging animation (later) | Lottie Compose |
| Async / streams | Kotlin Coroutines + Flow |
| Battery, screen, usage data | Android SDK only (`BatteryManager`, `UsageStatsManager`, broadcasts). No third-party library needed. |

## Build

- A GitHub Actions workflow runs `./gradlew assembleRelease` with a keystore stored in secrets, and uploads the APK as an artifact.

## Out of Scope (v1)

iOS, alerts (F6), cloud sync, root features, a home-screen widget, CSV export.

## UI Layout (agreed)

- **Theme:** Material 3, white background with black text, and a dark mode that follows the system.
- **Home:** two cards at the top.
  - **Charging** is active (filled green) while plugged in. It shows %, "Full at", time left, and 1% every Xm.
  - **Discharging** is active (filled orange) while unplugged. It shows "Empty at" and time left.
  - An inactive card shows its last session.
  - Tapping a card opens that session list.
  - Below the cards: top 5 apps by battery used today, most used apps today, and apps unused for 7 days.
- **Sessions list** (charging or discharging): grouped by day, with Session 1, Session 2… per day. It covers the last 90 days.
  - Plugging in ends the discharge session and starts a charge session. Unplugging does the reverse.
- **Session detail:**
  - chart of the actual % (solid line) and each estimate (dashed lines)
  - estimate vs actual table
  - apps used (discharge only)
  - step table with a **1% / Combined** toggle
- **Apps tab:** Session, Today or 7 days. Shows battery used per app, screen time, and unused apps.
- **Tips tab:** tips that apply to your phone right now, plus recommended settings, including Developer options.
- **Cleaner tab:**
  - A list of user apps that can run in the background, each with a checkbox. The selection is remembered.
  - **Kill selected** force-stops the ticked apps through an optional Accessibility helper, which taps Force stop on each app's info page. Without the helper, it falls back to a soft background kill.
  - The user always triggers this manually; nothing is killed automatically.
- **First launch:** a permissions screen: usage access, notifications, unrestricted battery, and the Force stop helper.
