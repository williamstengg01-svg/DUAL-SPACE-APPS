# Acceptance test report

Fill in one column per physical device. Instrumented tests (`CloneIsolationTest`) cover
the first two rows automatically: `./gradlew connectedArm64DebugAndroidTest`.

Build under test: `app-arm64-release.apk` version ____ (commit ____)

| # | Acceptance criterion | Samsung<br>(model/One UI) | Xiaomi/Redmi<br>(model/MIUI-HyperOS) | Vivo<br>(model/Funtouch-OriginOS) | Oppo<br>(model/ColorOS) |
|---|---|---|---|---|---|
| 1 | Any selected app can be cloned and runs (WhatsApp, Telegram, Instagram, one game) | | | | |
| 2 | Same app cloned 10+ times, each logged into a different account at the same time | | | | |
| 3 | Opening a fresh clone shows **no** Google Play Services prompt; Google sign-in works on first try | | | | |
| 4 | Push notification (FCM) arrives inside a clone while Dual Space is in background for 30 min | | | | |
| 5 | Clone keeps running after screen off 1 h (post setup-wizard) | | | | |
| 6 | Home-screen shortcut launches the right clone | | | | |
| 7 | Rename / change icon / freeze / hide / lock work | | | | |
| 8 | Clear data logs the clone out but keeps it; delete removes it and frees storage | | | | |
| 9 | Data persists after reboot | | | | |
| 10 | Data persists after installing a newer Dual Space APK over the old one | | | | |
| 11 | No ads anywhere; `apkanalyzer`/dependency scan shows no ad SDK | CI ✔ | CI ✔ | CI ✔ | CI ✔ |
| 12 | 32-bit-only app → picker points to the 32-bit build; works there | | | | |
| 13 | App lock (PIN + fingerprint) gates the app and locked clones | | | | |
| 14 | UI in English / Bahasa Indonesia / 简体中文 follows system language | | | | |

Notes / failures:

-
