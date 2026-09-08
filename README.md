# Dual Space — clone any app, unlimited times, no ads

Dual Space runs cloned copies of any installed Android app inside an isolated virtual
environment. Every clone has its own data, login session, notifications and files.
You can clone the same app as many times as your storage allows. Google Play Services is
mirrored into every clone slot *before* the app is installed there, so cloned apps never
show a "Google Play Services is missing / please link" prompt.

There is no advertising or analytics SDK anywhere in the project — the CI build fails if
one ever appears in the dependency graph.

## What's in the box

| Path | What it is |
|---|---|
| `app/` | The Dual Space app (Kotlin, Material 3). Everything user-facing lives here. |
| `Bcore/`, `black-reflection/`, `compiler/` | The BlackBox virtualization engine, *NewBlackbox* edition with Android 13/14/15 support (Apache 2.0) — the sandbox that actually runs the clones. Its built-in remote log uploader has been removed. |
| `.github/workflows/build.yml` | Builds signed arm64 + arm32 release APKs on every push. |
| `docs/ARCHITECTURE.md` | How the virtualization layer and the GMS pre-linking work. |
| `docs/SETUP_GUIDE.md` | The one-time per-brand setup (Samsung, Xiaomi/Redmi, Vivo, Oppo). |
| `docs/TEST_REPORT.md` | Acceptance-test matrix to fill in on real devices. |
| `keystore/` | Signing-key instructions (`keystore.properties.example`). |

## Getting an APK — no Android Studio needed

1. Create a new **private** repository on GitHub and upload the contents of this folder
   (or `git init && git add . && git commit -m "Dual Space" && git push`).
2. Open the **Actions** tab. The *Build Dual Space APK* workflow starts automatically.
3. When it finishes (~10–15 min the first time), open the run and download the
   **DualSpace-APKs** artifact. It contains:
   * `app-arm64-release.apk` — the main app. Install this on any modern phone.
   * `app-arm32-release.apk` — the companion for 32-bit-only apps (optional).
4. Copy the APK to the phone and install it (allow "install from unknown sources").

For a stable signature across updates, add four repository secrets before the first run:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (see `keystore/README.md`).
Without them the workflow signs with a temporary key, which still installs fine but a later
build cannot update over it without uninstalling first.

## Building locally

Requirements: JDK 21, Android SDK platform 35, build-tools 35.0.0 and NDK 29.0.13846066
(Android Studio's SDK Manager can install all of them).

```
./gradlew assembleArm64Release        # or assembleArm64Debug
./gradlew :app:testArm64ReleaseUnitTest
./gradlew connectedArm64DebugAndroidTest   # instrumented tests on a connected phone
```

Outputs land in `app/build/outputs/apk/<flavor>/<buildType>/`.

## How the requirements map to the code

| Requirement | Where |
|---|---|
| Clone any installed app | `data/CloneManager.createClone` → `BlackBoxCore.installPackageAsUser` |
| Unlimited clones of the same app | `data/CloneStore.nextUserIdFor` hands out a fresh virtual user slot per copy |
| Separate identity per clone | Each slot is a separate sandbox: own data dir, own Android ID, own accounts |
| Rename / icon / freeze / hide / lock / delete / clear data | `ui/CloneDetailActivity` |
| Home-screen shortcut per clone | `CloneDetailActivity.addShortcut` → `ui/ShortcutActivity` |
| GMS pre-linked, no prompts | `data/GmsLinker` (called before install, on launch, on boot and after updates) |
| No ads | No ad SDK dependency; CI step *Verify no ad / analytics SDKs* enforces it |
| Samsung / Xiaomi / Redmi / Vivo / Oppo | `util/BrandCompat` deep-links + `ui/SetupWizardActivity` |
| 32-bit and 64-bit apps | `arm64` and `arm32` product flavors; picker warns when the other build is needed |
| App lock (PIN / fingerprint) | `ui/LockActivity`, `ui/LockGate` |
| Data survives reboot / update | Sandboxes live in app-private storage; `BootReceiver` re-syncs GMS |
| Crash diagnostics | `util/CrashLog` records clone crashes on-device; *Settings → Diagnostics* shows/shares them |
| EN / ID / ZH-CN | `res/values`, `res/values-in`, `res/values-zh-rCN` |

## Updating without uninstalling (clones survive)

Android only installs a new APK over the old one when both are signed with the **same key**.
Since 1.2.3 the GitHub build signs with a permanent release key stored in the repository
secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, so every later
build installs straight over the previous one and every clone, its login and its data stay
in place. Builds made *before* 1.2.3 used a throw-away key, so upgrading from one of those
needs a single last uninstall. Keep a backup of the key file (`keystore/README.md`): if it is
ever lost, the next build cannot update the installed app and the phone will ask to uninstall
again.

## Changelog

* **1.2.4** — resuming clones, lighter slots, flexible setup.
  * Opening a clone that is already running now *resumes* it. The engine used to stack a
    second copy of the app's launch activity on the running task, so the clone came back on
    its splash screen with a stale view while the real, logged-in screens sat underneath.
  * The Play Store mirror is off by default (Settings → *Mirror the Play Store into clones*).
    Clones still get Play Services; the Store only added two more processes per clone, and
    that memory pressure is what makes Android destroy a clone's screens in the background.
    Turning it off also removes it from slots that already have it.
  * Setup wizard: no step blocks *Done* any more. It shows how many are set and lets you
    continue with any of them; the steps only improve background survival.
  * The mirrored Play Services no longer floods the log with refused `getCurrentUserId`
    calls; it gets the user id the clone actually runs in.
  * The log now records every screen shown, whether a screen was rebuilt from a saved state
    and why a screen was destroyed, which is what identifies a "went back on its own" report.
* **1.2.3** — in-place updates. The workflow signs with a permanent release key (PKCS12 from
  repository secrets; `storeType` now honoured in `keystore.properties`), and prints the
  signing certificate in the build log so a mismatch can be spotted before installing.
* **1.2.2** — fixes from the first field log (OPPO, Android 15).
  * Crash loop fixed: when Android restarted one of the engine's stub processes on its own
    (a client was still bound when it died), the new process had no idea which app it hosted
    and every incoming bind crashed it with a NullPointerException, once a second, until the
    client gave up. The process now re-registers itself with the engine before serving the
    bind, and a bind that still cannot be served returns null instead of crashing.
  * Location prompts: runtime-permission checks made by a clone (and by the mirrored Play
    Services on its behalf) are answered for the *host*, whose UID actually holds the grant,
    instead of for the original app; Play Services' own location requests are no longer
    blocked; and Dual Space asks for location once (setup step and before the first launch)
    so clones stop asking.
  * Play Services was sometimes silently not linked into a new slot because the engine's
    client answered "installed" from the phone's package list; linking is now verified with
    the engine service itself and retried.
  * Broadcasts a clone sends "to all users" are sent to our own user instead of being refused.
* **1.2.1** — network inside clones.
  * Rewrote the ConnectivityManager hook. Calls now carry the host package (the one the
    system accepts for our UID), so NetworkCallback registration works: apps no longer sit
    on "no connection" after login while their HTTP calls succeed. If a registration still
    fails, the callback gets a synthetic request plus one `onAvailable` for the phone's
    active network instead of a `null` that made `unregisterNetworkCallback` crash the app
    later. Fabricated "connected" answers are used only when the system refuses a call,
    never instead of a real `null`.
  * Network self-test: a few seconds after a clone starts, its own process logs what it
    sees (active network, INTERNET/VALIDATED, whether a default NetworkCallback fires, and
    an HTTPS probe), so the log shows whether "offline" is real or only what the app believes.
  * Home screen nudges when a new crash / failure report exists, with a shortcut to the log.
* **1.2.0** — stability and diagnostics release.
  * No more "isn't responding" freezes after a crash: the engine's crash-prevention
    handlers swallowed main-thread exceptions and left a dead UI thread; main-thread crashes
    now go to the system handler (clean exit, relaunch) and are recorded with the clone.
  * Log file `files/logs/dualspace.log` (host, engine service and every clone process,
    including engine warnings/errors), ANR watchdog, Diagnostics screen with share/export.
  * Google Play Services indicator (green/red) on the home screen, clone tiles and clone page.
  * Smoother cloning: no APK scan for the whole app list, icons decoded off the main thread,
    step-by-step progress dialog, engine calls moved off the UI thread.
* **1.1.1** — fixes clones that bounced straight back to the home screen:
  * engine: activity hand-over hook now works on **Android 16** (the framework removed
    `ClientTransaction.mActivityCallbacks` / `mActivityToken`; the hook now reads
    `mTransactionItems` and the per-item token as well);
  * engine: cloning no longer crashes the host app when the engine service is not up yet
    (a `NullPointerException` in the install path is now reported as a message);
  * engine: the launch splash gives up after 12 s with a message instead of hanging, and a
    stub activity that cannot hand over stops retrying instead of looping;
  * engine: R8 shrinking of the reflection-heavy engine module is off;
  * app: every clone/launch failure now shows *why* (and is recorded in Diagnostics), and a
    clone whose sandbox copy went missing is re-installed automatically on launch.
* **1.1.0** — switched to the maintained *NewBlackbox* engine (Android 13/14/15 support; fixes
  clones that closed immediately on modern phones), added *Diagnostics* screen with on-device
  crash log, added *Storage access* setup step, removed the engine's remote log uploader.
* **1.0.0** — first release.

## Getting the log file (when something crashes or freezes)

1. Reproduce the problem once (open the clone, use it until it fails).
2. Open Dual Space → **⋮ → Diagnostics & logs** (also under Settings).
3. Tap **Share log file** and send it, or **Save to Downloads** and pick it up from
   `Download/DualSpace/dualspace-log-<date>.txt` with any file manager.

The file contains: device / Android version, the Play Services link status, every crash,
failure and ANR report (with the stack trace and which clone it came from), and the log of
the host app, the engine service and each clone process. Nothing is sent anywhere by itself.

## Troubleshooting — "the clone goes straight back to the home screen"

1. Open the clone again from Dual Space. Since 1.1.1 a failed launch shows a message with the
   reason instead of silently closing.
2. Open **Settings → Diagnostics** inside Dual Space. Every failed launch, failed clone and
   crash (including crashes inside the cloned app's own process) is listed there with a stack
   trace. Tap **Share** and send the text when asking for help — it contains the Android
   version, the device model and the exact exception, which is what is needed to fix it.
3. Make sure the setup wizard steps are done (battery optimisation, storage access, and the
   OEM autostart permission on Xiaomi / Vivo / Oppo). Aggressive battery managers kill the
   engine's background process, and a clone cannot start without it.
4. If the phone was just updated to a new Android major version, the engine may need a
   compatibility fix for that version. The Diagnostics report shows which framework call
   failed.

## Known limits (please read)

* **Google Play Services inside clones is mirrored from the phone.** It cannot be bundled
  in the APK (Google does not license GMS for redistribution). On phones without Google
  services (some Chinese-market ROMs) clones run, but Google sign-in inside them will not.
* **Play Integrity / SafetyNet.** Apps that demand a *hardware-attested* integrity verdict
  (some banks, Google Pay, a few games) may refuse to run in *any* virtual environment,
  Dual Space included. This is a Google policy, not something an app cloner can fix.
* **targetSdk is 28 on purpose.** App virtualization depends on behaviour Android
  restricts for apps targeting 29+. This is fine for side-loading; it is not accepted by
  Google Play's target-SDK policy, which is why every commercial app cloner is either
  side-loaded or ships a heavily reduced Play version.
* **Anti-cheat / anti-virtualization** apps (some games, some banking apps) actively
  detect sandboxes and may block themselves.
* Cloned apps run inside the Dual Space process family, so the phone's OEM battery
  manager must be told to leave Dual Space alone — that's what the setup wizard does.

## License

Dual Space app code: MIT. Virtualization engine: BlackBox, Apache License 2.0
(see `LICENSE-BlackBox`).
