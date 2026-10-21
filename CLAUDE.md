# StopScroll — notes pour Claude

Personal Android app (Kotlin, Compose) blocking Instagram Reels infinite scroll via an AccessibilityService. The user is a French-speaking Android beginner: answer in French, explain notions simply, comment code generously (in French, like the existing code). See README.md for features, install and maintenance.

## Build & device

- JDK: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`; SDK in `%LOCALAPPDATA%/Android/Sdk`. AGP 9.4.1 (built-in Kotlin: no kotlin-android plugin), Gradle 9.6, compile/target SDK 37, minSdk 26.
- `./gradlew assembleDebug` / `assembleRelease` (release signed with the debug keystore on purpose).
- Test device: Pixel 7a, Android 17, via adb. In Git Bash, prefix adb commands touching device paths with `MSYS_NO_PATHCONV=1`.
- Ask/check the user is not using the phone (`dumpsys power` → lastUserActivityTime) before driving it with adb.
- Debug build: `adb shell am broadcast -a fr.brion.stopscroll.DUMP_TREE` exports the accessibility tree to `files/dumps/` (read with `run-as fr.brion.stopscroll`). Dumps contain on-screen text: delete them after use.

## Hard-won lessons (do not regress)

- **Never test overlay leaks with `adb shell input`**: those injected events mix with the service's own dispatchGesture and reach Instagram through the hole, producing false leaks. Use a virtual touchscreen via the `uinput` tool (shell is in group `uhid`); `sendevent` on the real touchscreen is denied by SELinux.
- An injected gesture is cancelled as soon as a real finger moves → long press cannot be forwarded live.
- Overlay pass-through = a tiny hole: 4 panes around the replay point + a "plug" pane, all permanently attached (unused ones parked offscreen). Resizes must be two-phase (grow covering panes, wait for frame commit + margin, then shrink) or a swipe leaks through transient gaps.
- Window resizes are slow when the service's process is not touch-boosted (~300 ms after a touch): prepare the hole at finger-up, then only toggle the plug's FLAG_NOT_TOUCHABLE at replay time.
- Accessibility tree queries slow window updates (shared system_server): screen analysis runs on a worker thread and is paused while the user touches the overlay (`SwipeBlockerOverlay.isBusy`).
- A viewer measured mid-animation (shifted/scaled) → cover the whole screen and re-measure (`ReelViewer.settling`).
- The service listens to all packages only while an overlay is shown (to remove it when leaving Instagram); otherwise `packageNames` = Instagram only.
- Toasts are suppressed for a background app without notification permission → use `MessageOverlay`.
- Double-tap is reconstructed by the overlay and replayed as two sequential gestures (a single multi-stroke gesture could be half-delivered).

## Future work (not implemented)

Time ranges with blocking disabled: plug into `BlockingPolicy.isBlockingPeriodNow()` only.
