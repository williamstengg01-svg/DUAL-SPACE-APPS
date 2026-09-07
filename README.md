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

## Changelog

* **1.1.0** — switched to the maintained *NewBlackbox* engine (Android 13/14/15 support; fixes
  clones that closed immediately on modern phones), added *Diagnostics* screen with on-device
  crash log, added *Storage access* setup step, removed the engine's remote log uploader.
* **1.0.0** — first release.

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
