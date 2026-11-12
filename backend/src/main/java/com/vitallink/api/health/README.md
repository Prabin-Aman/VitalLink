# Health

The metric catalogue (44 metrics, each in one sharing scope), idempotent sync ingest of 5-minute buckets,
manual readings, series, latest readings, the dashboard with the readiness score, devices, and My Health
(overview + 7 tabs).

- `POST /me/health/ingest` (`bluetooth` from the web portal; other platforms are accepted), `POST /me/health/manual`
- `POST /me/health/import`: an Apple Health export or a CSV, parsed by `HealthImport` (pure, streaming)
- `GET /me/health/dashboard`, `/series/{metric}`, `/series/blood_pressure`, `/latest`
- `GET /me/health/overview`, `GET /me/health/tabs/{vitals|sleep|heart|respiratory|body|mental|digital}`
- `GET /me/devices`, `DELETE /me/devices/{id}`, `GET /me/sync` (phone uploads: last per platform, 10 newest)
- Google Health (Fitbit, Pixel Watch), `GoogleHealth` + `ConnectionsController`: `GET /me/connections`,
  `POST /me/connections/google-health/start` (consent URL; HMAC-signed state bound to the user, 15 min),
  `…/finish` (code from the portal's `/devices/google/callback`; stores the refresh token sealed with
  AES-256-GCM, `core/security/Sealer`; first sync 30 days), `…/sync` (since the last sync minus a day; ≤ 90
  days), `DELETE /me/connections/google-health` (revokes at Google; readings stay). Steps, distance, active
  energy and heart rate from 5-minute roll-ups; weight, body fat, resting HR, HRV and sleep stages from data
  points; stored as source `google_health`. A refused data type is reported and skipped; a revoked grant is
  409 `reconnect_needed` and shows on the card. The nightly job syncs everyone connected.

- Android helper (`android/`, Health Connect), `PhoneLinks` + `PhoneController`: `POST /me/phones/code` (8
  characters, 10 minutes, one use; a new code replaces the last), `GET /me/phones`, `DELETE /me/phones/{id}`;
  the app calls `POST /v1/phone/link` (no account token) for an upload-only token, then `POST /v1/phone/ingest`
  (the ingest body, platform `health_connect`) and `GET /v1/phone/status` with `Authorization: Phone <token>`
  (`PhoneCaller`, resolved in WebConfig, counted as ingest by the rate limiter). Only SHA-256 hashes are stored.

Tables: `metrics`, `health_readings`, `ingest_batches`, `devices`, `device_connections` (V21), `phone_links` (V22). Classes:
`Catalogue`, `HealthService`, `HealthTabsService`, `GoogleHealth` and their controllers.
