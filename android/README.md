# VitalLink Sync (Android helper app)

A small Android app that reads **Health Connect** (the Android 14+ system health store; on Android 8 to 13 the
Health Connect app) and uploads it to a VitalLink account. The web portal can't read Health Connect itself: it only
exists on the phone.

## How it works
1. On the website: Settings › Connected Devices › Add a device › **Android phone** shows a code (10 minutes, one use).
2. In the app: type the code → `POST /v1/phone/link` returns a token that can **only upload** readings
   (`POST /v1/phone/ingest`) and say whose account it is (`GET /v1/phone/status`). The server keeps only its hash.
3. **Allow access** asks Health Connect for read permissions (activity, heart, blood pressure, glucose, oxygen,
   temperature, weight, body fat, sleep, plus background and history reading).
4. A WorkManager job syncs about every 3 hours with a network (and on **Sync now**): readings since the last sync
   (6 hours of overlap) or the last 30 days, turned into 5-minute buckets (`Buckets.kt`, the same rules as the server's
   Apple Health import), uploaded in batches of ≤ 5,000 with an idempotency key. The server upserts, so overlap and
   retries never double anything. Readings are stored with the source `health_connect`.
5. Removing the phone on the website voids the token; the app notices on its next upload and unlinks itself.

| File | What it does |
|---|---|
| `MainActivity.kt` | The one screen: link, allow access, sync now, unlink |
| `Reader.kt` | Health Connect → buckets (totals through Health Connect's own 5-minute aggregation) |
| `Buckets.kt` | 5-minute buckets, one source per total, ranges matching `backend/…/health/Catalogue.java` |
| `Api.kt` | The three calls (HttpURLConnection + org.json, no extra libraries) |
| `SyncWorker.kt` | The background sync |
| `PrivacyActivity.kt` | Why the app reads health data (shown from Health Connect's permission screens) |

## Build
No Android SDK is needed on your computer: GitHub Actions (`.github/workflows/android.yml`) runs the unit tests and
builds `app-debug.apk` on every push to `android/`. Download it from the run's **Artifacts**, copy it to the phone and
open it (allow "Install unknown apps" for your file manager or browser). It's free; publishing on Google Play would
cost a one-time $25 and a review of the health permissions.

Locally, with Android Studio or the command-line SDK and Gradle 8.11: `gradle -p android testDebugUnitTest assembleDebug`.
On the emulator the server is `http://10.0.2.2:8000` (the app's Server field), which needs cleartext allowed; the
default is the production API.
