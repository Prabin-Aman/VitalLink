# Health

The metric catalogue (44 metrics, each in one sharing scope), idempotent sync ingest of 5-minute buckets,
manual readings, series, latest readings, the dashboard with the readiness score, devices, and My Health
(overview + 7 tabs).

- `POST /me/health/ingest` (`bluetooth` from the web portal; other platforms are accepted), `POST /me/health/manual`
- `POST /me/health/import`: an Apple Health export or a CSV, parsed by `HealthImport` (pure, streaming)
- `GET /me/health/dashboard`, `/series/{metric}`, `/series/blood_pressure`, `/latest`
- `GET /me/health/overview`, `GET /me/health/tabs/{vitals|sleep|heart|respiratory|body|mental|digital}`
- `GET /me/devices`, `DELETE /me/devices/{id}`, `GET /me/sync` (phone uploads: last per platform, 10 newest)

Tables: `metrics`, `health_readings`, `ingest_batches`, `devices`. Classes: `Catalogue`, `HealthService`,
`HealthTabsService` and their controllers.
