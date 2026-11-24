# HRV Monitor (Suunto Smart Sensor → intervals.icu)

Install: on the phone open this repo's **Releases** → latest → `hrv-monitor.apk` → allow "install unknown apps".

Use: Find sensor → Start → rest (default 120 s) → measure (default 180 s) → Upload.
Uploads RMSSD (`hrv`), SDNN (`hrvSDNN`) and resting HR to today's intervals.icu wellness entry.
API key: intervals.icu → Settings → Developer Settings. Athlete ID `0` = you.

HRV cleaning: range filter 300–2000 ms, ±20 % local-median filter, artifacts removed (not
interpolated), RMSSD only over truly consecutive clean beats. See `Hrv.kt`.
