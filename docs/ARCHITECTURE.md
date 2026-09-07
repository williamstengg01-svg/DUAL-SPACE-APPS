# Dual Space — Architecture

## 1. Two layers

```
┌──────────────────────────────────────────────────────────────┐
│  app/  (Kotlin, Material 3)                                  │
│  MainActivity · AppPicker · CloneDetail · SetupWizard · Lock │
│  CloneStore (registry) · CloneManager (façade) · GmsLinker   │
├──────────────────────────────────────────────────────────────┤
│  Bcore/ (+ black-reflection, compiler) — NewBlackbox engine   │
│  BlackBoxCore → BPackageManager / BActivityManager / …       │
│  Proxy Activities/Services/Providers (:p0 … :p49 processes)  │
│  Hooks on ActivityManager, PackageManager, ContentProvider,  │
│  Binder services, file paths (native + Java)                 │
└──────────────────────────────────────────────────────────────┘
```

The engine is a "virtual Android inside an app". When a clone is launched, the engine
starts one of its pre-declared stub processes (`:p0`, `:p1`, …), loads the target APK's
code into it, and installs hooks so that every framework call the app makes — start an
activity, query a package, open a file, bind a service, read `Settings.Secure.ANDROID_ID`
— is answered by the engine's own *virtual* system services rather than by the real ones.
The cloned app therefore believes it is a normally installed app with its own data dir,
while in reality everything lives under Dual Space's private storage.

## 2. Unlimited clones = virtual users

The engine models isolation with **virtual user ids** (0, 1, 2, …), mirroring Android's
own multi-user design. A package installed into user 0 and the same package installed
into user 1 have separate:

* data directories (`/data/user/<uid>/<pkg>` inside the virtual root),
* external data (`Android/data/<pkg>` per user),
* accounts, shared preferences, databases, caches,
* Android ID / device identifiers (faked per sandbox),
* running processes.

`CloneStore.nextUserIdFor(pkg)` simply hands out the smallest user id in which that
package is not yet installed. Clone #1 of WhatsApp lives in user 0, #2 in user 1, #3 in
user 2, and so on. Nothing caps this; the engine creates users on demand
(`BlackBoxCore.createUser`). Different packages happily share a user id, so ten clones of
WhatsApp and one clone of Telegram use ten slots, not eleven.

Freezing a clone stops its processes and marks it so the launcher refuses to start it.
Deleting a clone uninstalls it from its slot; if the slot is then empty the GMS mirror is
removed and the user id is deleted, so storage is reclaimed.

## 3. Google Play Services "pre-linking"

Google does not license Play Services for redistribution, so it **cannot be bundled**
inside the APK. What Dual Space does instead — and what every working cloner does — is
mirror the phone's own Google packages into the sandbox:

```
com.google.android.gms            Google Play Services
com.google.android.gsf            Google Services Framework
com.google.android.gsf.login
com.android.vending               Google Play Store
+ backup/sync-adapter/partner-setup helpers
```

`GmsLinker` is invoked at four moments:

1. **Before install** (`CloneManager.createClone` → `GmsLinker.ensure(userId)`): the
   mirror is put into the slot *first*, so the very first launch of the clone resolves
   `com.google.android.gms` normally. This is what removes the "This app requires Google
   Play Services" dialog and the "link to Google Play Services" step some cloners show.
2. **On every launch** (`CloneManager.launch`): a cheap `isInstalled` check, re-mirroring
   only if the user cleared the slot.
3. **On app start / boot / after a Dual Space update** (`DualSpaceApp`, `BootReceiver` →
   `GmsLinker.syncAll`): compares the phone's current Play Services `versionCode` with
   the one recorded at last sync; if the phone updated Play Services, the mirror in every
   slot is refreshed, so clones never fall into a "Play Services out of date" state.

Inside the clone, Google sign-in, Firebase Cloud Messaging, Maps, in-app billing and
Play Integrity all talk to the mirrored GMS, which in turn talks to Google with the
phone's real Google account(s) — the sandbox exposes the host's accounts to the
mirrored GMS through the engine's virtual `AccountManager`.

Limitation: hardware-backed Play Integrity ("STRONG" verdict) cannot be satisfied from
any virtual environment. Apps that hard-require it will refuse to run as a clone.

## 4. Process model and stability

* The host UI runs in the main process. The engine's system server runs in its own
  `:black_box_service` process. Each clone gets one or more of the stub processes
  `:p0 … :p49`, so a crash inside one clone kills only that process.
* The host never touches the clone's data directly; all engine calls go through Binder
  to the engine's server process, which is why `CloneManager` methods are blocking and are
  called from `Dispatchers.IO`.
* `isEnableDaemonService = true` keeps a small daemon service alive so OEM battery
  managers see "an app doing work" and are less likely to kill clones. The brand setup
  wizard (`BrandCompat`) handles the rest.

## 5. Why 32-bit and 64-bit builds

A virtual app's code is loaded **into the host process**. A 64-bit process cannot load
32-bit native libraries and vice versa. Dual Space therefore ships two flavors with
different application ids: `com.dualspace.clone` (arm64-v8a) and `com.dualspace.clone32`
(armeabi-v7a). The app picker inspects the target APK's `lib/` folders (`AbiUtils`) and,
if the app only ships the other architecture, tells the user to use the companion build.
Apps with no native libraries at all run in either.

## 6. Data persistence

Everything — clone registry, custom icons, sandboxes — lives in Dual Space's private
storage (`/data/data/com.dualspace.clone/…`). Android preserves that across reboots and
app updates (`android:allowBackup="false"` prevents partial cloud restores from
corrupting sandbox state). Uninstalling Dual Space removes all clones.

## 7. Privacy

No network code exists in `app/`. The engine only talks to the local system — the upstream
engine shipped a crash-log uploader that posted logcat to a third-party server by default;
in Dual Space that class is stubbed out and the configuration returns no upload target, so
crash logs stay on the device (`CrashLog`, visible under Settings → Diagnostics). There is no
crash reporter, analytics, or advertising SDK; the CI workflow greps the resolved
dependency graph for known ad/analytics artifacts and fails the build if any appear.

## 8. Logging and crash policy (1.2.0)

* `util/DsLog` is the single log file (`files/logs/dualspace.log`, 1 MB, rotated once). It is
  initialised in `Application.attachBaseContext` *before* the engine class is loaded and is
  registered as the engine's `Slog.Sink`, so engine warnings/errors from every process land
  in the same file, prefixed with the process (`main`, `black`, `p3`) and — inside a clone
  process — the clone package and slot.
* `util/CrashLog` is the last uncaught-exception handler in every process. The engine's own
  "crash prevention" handlers return without killing the process; on the main thread that
  leaves a dead looper and a frozen UI, which Android reports as "isn't responding". CrashLog
  therefore captures the *system* handler before the engine replaces it and, for main-thread
  crashes, delegates to it (clean process death, crash dialog, relaunch). Background-thread
  exceptions still go through the engine chain, which may recover them.
* `util/AnrWatchdog` posts a tick to the main looper every 2 s; if a tick is not processed
  within 5 s (checked twice) the main thread's stack is written to the log and to a report.
* `data/GmsLinker` publishes a `Status` (phone has GMS, engine reachable, per-slot linked)
  that drives the green/red indicators. All engine calls stay on `Dispatchers.IO`; the UI
  reads the cached status only.
