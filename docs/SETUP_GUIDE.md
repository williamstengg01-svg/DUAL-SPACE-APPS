# One-time setup per phone brand

Dual Space shows this guide automatically on first launch (and again from *Settings →
Device setup*). Every step has an **Open settings** button that jumps to the exact page.
Do it once; clones will then keep receiving messages and notifications in the background.

## All brands

1. **Battery optimisation** → choose *Allow* / *Don't optimise* for Dual Space.
2. **Notifications** → make sure notifications for Dual Space are on. Clones deliver their
   notifications through Dual Space's notification channels.
3. **Home-screen shortcuts** (optional) → if a clone shortcut doesn't appear, allow
   *Home screen shortcuts* for Dual Space in the app's permission page.

## Samsung (One UI)

* Settings → Battery → *Background usage limits* → **Never sleeping apps** → add Dual Space.
* Also remove Dual Space from **Sleeping apps** and **Deep sleeping apps** if present.
* Turn **Put unused apps to sleep** off, or Dual Space may be auto-added later.
* Secure Folder and Samsung's own *Dual Messenger* can stay enabled; Dual Space does not
  conflict with them.

## Xiaomi / Redmi / POCO (MIUI · HyperOS)

* Security → **Autostart** → enable Dual Space.
* Settings → Apps → Dual Space → **Battery saver** → *No restrictions*.
* Settings → Apps → Dual Space → **Other permissions** → allow *Display pop-up windows
  while running in the background* and *Display pop-up window*.
* MIUI's own *Dual apps* feature can remain; Dual Space works independently of it.

## Vivo / iQOO (Funtouch OS · OriginOS)

* i Manager → App manager → **Autostart manager** → enable Dual Space.
* i Manager → **Background power consumption** (or Settings → Battery → *High
  background power consumption*) → allow Dual Space.
* Settings → Apps → Dual Space → Permissions → allow **Background pop-ups** and
  **Display over other apps**.

## Oppo / Realme / OnePlus (ColorOS · OxygenOS)

* Phone Manager → Privacy → **Startup manager** (*Auto-launch*) → enable Dual Space.
* Settings → Battery → *App battery management* → Dual Space → **Allow background
  activity**, disable *Smart power saving* for it.
* Oppo's *App Cloner* and OnePlus *Parallel Apps* can stay on.

## Other phones

Only the battery-optimisation and notification steps are needed; stock Android does not
have a separate autostart list.

## If a clone still stops in the background

* Open Dual Space once after each reboot (or add the clone shortcut to the home screen
  and open it) — some skins reset the autostart list after a system update.
* Lock Dual Space in the Recents screen (long-press the card → *Lock*) on Xiaomi/Vivo/Oppo.
* Check that the *original* app's notification permission is on too; a few OEMs mirror
  that state to virtual instances.
