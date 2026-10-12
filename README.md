# Runtimer

A Couch to 5K-style run/walk interval timer for Android that repeats until you tap **Finish**.
It runs as a foreground service, so it keeps going with the screen locked, and its beeps
duck your music (Spotify etc.) rather than pausing it.

## Features
- Week 1–6 presets, or custom run/walk lengths in 15-second steps
- Optional 5-minute warm-up and cool-down (cool-down starts when you tap Finish)
- Distinct cues: two high beeps = run, one long lower beep = walk, plus a 3-second countdown
- Vibration on every change
- Pause / Skip / Finish from the notification and lock screen

## Getting the APK
Every push to `main` runs the **Build APK** workflow (see the Actions tab). The finished APK is
attached to the **latest** release: open Releases on your phone, download `Runtimer.apk`,
and install it (allow your browser or Files app to install unknown apps when asked).

## First run
1. Allow notifications when asked.
2. Tap **Let Runtimer run in the background**, then allow it. On Samsung, also check
   Settings → Apps → Runtimer → Battery → **Unrestricted**.
