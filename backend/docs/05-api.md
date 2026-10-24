# 5. API

A JSON REST API under `/v1`. springdoc generates the OpenAPI document (`/openapi.json`, Swagger UI at `/docs`).
`packages/api/src/types.ts` is kept in step with it, and each interface there is the contract for
one endpoint below.

## 5.1 Conventions

| Topic | Rule |
|---|---|
| Route families | `/v1/me/…` is the caller acting on their own data. `/v1/users/{user_id}/…` is a shared view of someone else's data, and every such route declares a scope and passes the consent check. `/v1/library/…` and `/v1/foods/…` are shared content (signed-in only). |
| Auth | `Authorization: Bearer <Supabase access token>` on everything except `/health` and `/ready`. No cookies, so there's no CSRF surface. CORS allows only the web portal's origin. |
| Sensitive actions | `DELETE /me` requires a token issued within the last 10 min (`iat`), and `aal2` when MFA is enrolled. `POST /me/shares` has no recency check: Supabase's token refresh resets `iat`, so it would add friction without security; it will require `aal2` once MFA enrolment is readable. |
| Status codes | 200 read · 201 created · 202 accepted (ingest) · 204 deleted · 401 bad/missing token · 403 consent refused or step-up needed · 404 not found **and** unknown/unshared user (existence never leaks) · 409 conflict · 413 batch too large · 422 invalid body · 429 rate limited · 503 DB unavailable |
| Errors | `{"error": {"code", "message", "detail", "request_id"}}`. Clients switch on `code`. |
| Pagination | `limit` and `offset`, default 50, max 200; lists return `{items, total}` |
| Time | ISO 8601 with offset in and out; day-based queries use the user's timezone |
| Units | SI in and out (m, kg, ml, °C, s); the client converts per `profiles.units` |
| Idempotency | Ingest takes `idempotency_key`. A same-key, same-payload replay returns the first result with `replayed: true`; a same key with a different payload → 409. Dose logging is idempotent through its natural key. |
| Rate limits | Per user: 60 ingest / 600 other per hour, fixed hourly windows (in memory, single instance; `vitallink.rate-limit.*`). Over the limit → 429 `rate_limited` with `Retry-After` and `detail.retry_after_s` |
| Versioning | Additive changes stay in `/v1`; breaking changes go to `/v2` |

## 5.2 Endpoints that are live (built and called by the client)

As of 23 Oct 2026 every client call is live except `conversations()` (decision D1). The table lists
the calls built first; 5.3 lists the rest with their paths.

| Client call | Method and path | Returns (`types.ts`) |
|---|---|---|
| `me()` | `GET /me` | `Me` (first call creates the account) |
| `dashboard()` | `GET /me/health/dashboard` | `Dashboard` |
| `series(metric, days, interval)` | `GET /me/health/series/{metric}?days&interval` | `Series` (aggregation from the catalogue; SI units) |
| `bloodPressure()` | `GET /me/health/series/blood_pressure?days&interval` | `{systolic, diastolic}: Series` (pairs `bp_systolic`/`bp_diastolic`) |
| `account()` | `GET /me/account` | `AccountProfile` |
| `targets()` | `GET /me/targets` | `Targets` (daily goals) |
| `latest(metrics)`, `bloodPressureLatest()` | `GET /me/health/latest?metrics=` | `Reading[]` |
| `devices()` | `GET /me/devices` | `Device[]` |
| `shares()` | `GET /me/shares` | `Share[]` |
| `sharedWithMe()` | `GET /me/shared-with-me` | `SharedWithMe[]` |
| `accessLog()` | `GET /me/access-log?limit` | `AccessLogEntry[]` |



## 5.3 Endpoints for the screens built later (all live)

Each row replaced one `client.pending()` call; only `conversations()` is still pending.

| Client call (pending today) | Method and path | Returns | Module |
|---|---|---|---|
| `bloodPressureLatest()` | `GET /me/health/latest?metrics=bp_systolic,bp_diastolic` | `Reading[]` | health |
| `goals()` | `GET /me/goals/today` | `Goal[]` (daily targets + active goals, today) | goals |
| `streaks()` | `GET /me/streaks` | `{label, days}[]` | goals |
| `medications()` | `GET /me/medications/today` | `Medication[]` (one per scheduled dose today) | care |
| `nextAppointment()` | `GET /me/appointments?upcoming=true&limit=1` | `Appointment \| null` | care |
| `latestLab()` | `GET /me/labs/latest-result` | `LatestLab \| null` (404 `no_labs` → null) | records |
| `privacySettings()` | `GET /me/privacy` | `PrivacySettings` | sharing |
| `training()` | `GET /me/training?week=` | `Training` | training |
| `nutrition()` | `GET /me/nutrition/day?date=` | `NutritionDay` | nutrition |
| `goalsOverview()` | `GET /me/goals/overview` | `GoalsOverview` | goals |
| `medicationsOverview()` | `GET /me/medications` | `MedicationsOverview` | care |
| `appointments()` | `GET /me/appointments?from=&to=` | `Appointment[]` | care |
| `conversations()` | — | blocked on [D1](10-open-decisions.md) | — |
| `notifications()` | `GET /me/notifications` | `AppNotification[]` | notifications |
| `family()` | `GET /me/family` | `FamilyMember[]` | sharing |
| `emergencyProfile()` | `GET /me/emergency-profile` | `EmergencyProfile` | care |
| `devices()` | `GET /me/devices` | `Device[]` |
| `shares()` | `GET /me/shares` | `Share[]` |
| `sharedWithMe()` | `GET /me/shared-with-me` | `SharedWithMe[]` |
| `accessLog()` | `GET /me/access-log?limit` | `AccessLogEntry[]` | health |
| `library()` | `GET /library` | `Library` | library |
| `account()` | `GET /me/account` | `AccountProfile` | account |
| `recordsOverview()` | `GET /me/records/overview` | `RecordsOverview` | records |
| `labPanels()` | `GET /me/labs` | `LabPanel[]` | records |
| `healthOverview()` | `GET /me/health/overview` | `HealthOverview` | health |
| `vitals()` … `digital()` | `GET /me/health/tabs/{vitals,sleep,heart,respiratory,body,mental,digital}` | `VitalsTab` … `DigitalTab` | health |

Four shapes changed when they went live, and the mock was changed to match. `nextAppointment` and
`latestLab` return the structured `Appointment` and `LatestLab` instead of pre-formatted strings, and
`screens.ts` formats them. `medications()` returns each dose's time (`at`) and a `missed`/`skipped`
status instead of a formatted `time`. `privacySettings()` uses `emergency_on_lock_screen`. Live
My Health tabs leave out readings nothing measures (walking speed, wrist temperature, app names) and
send `null` for blocks without data; the view-models show "—" for those.

## 5.4 Full catalogue

### Account

| Method and path | Purpose |
|---|---|
| `GET /me` | Account; creates it on first call (insert-if-absent) |
| `GET, PATCH /me/account` | Profile and settings: names, DOB, sex, height, phone, ZIP, city, language, units, theme. `two_factor` is read from the token's MFA factors, never stored |
| `GET, PATCH /me/targets` | Daily goals (steps, move, exercise, stand, sleep): one source for the dashboard, rings and goals |
| `GET /me/export` | Every row the user owns, plus record file names, as one JSON download (each module's `UserData` hook) |
| `DELETE /me` | Needs a sign-in within 10 min (else 403 `reauth_required`). Revoke and unbind received shares → module hooks (stored files) → delete rows (cascade) → delete the Supabase identity after commit |

### Health

| Method and path | Purpose |
|---|---|
| `POST /me/health/ingest` | `{idempotency_key, platform, devices[], buckets[]}`, ≤ 5,000 buckets; 202 `{inserted, updated, unchanged, replayed}` |
| `POST /me/health/manual` | One manual reading (mood, weight, waist, BP from a cuff without sync…) for metrics with `manual_allowed` |
| `GET /me/health/dashboard` | Today's summary + readiness score |
| `GET /me/health/series/{metric}` | `days` ≤ 400 (the local days ending today), `interval` hour/day/week/month (weeks start Monday). Aggregation comes from the catalogue. Totals fill empty buckets with 0; averages skip them |
| `GET /me/health/latest?metrics=` | Latest `Reading` for each metric, with assessment |
| `GET /me/health/overview` | `HealthOverview` (rings, sections, readings, sources, insights) |
| `GET /me/health/tabs/{tab}` | The seven tab payloads; activity uses series + workouts |
| `GET /me/devices`, `DELETE /me/devices/{id}` | Registry; delete only forgets the device, readings stay |

### Training

| Method and path | Purpose |
|---|---|
| `POST, GET /me/workouts`; `GET, DELETE /me/workouts/{id}` | Workouts with sets (a synced workout re-sent with its platform id is updated) |
| `GET /me/training?week=` | `Training`: week aggregate, sessions, muscle sets, records, next planned session, lifetime total |
| `POST, GET /me/training/plans` | Plans and planned sessions; a new plan archives the previous |
| `GET /exercises` | Exercise catalogue |

### Nutrition

| Method and path | Purpose |
|---|---|
| `GET /foods/search?q=` | Name search over 30 seeded foods + the caller's own foods |
| `POST /me/foods` | Custom food |
| `POST /me/meals`; `DELETE /me/meals/{id}` | Meals with items (macros copied) |
| `POST /me/hydration`; `DELETE /me/hydration/{id}` | Water |
| `GET /me/nutrition/day?date=` | `NutritionDay` |
| `GET, PATCH /me/nutrition/targets` | Targets |

### Goals and insights

| Method and path | Purpose |
|---|---|
| `POST, GET /me/goals`; `GET, PATCH, DELETE /me/goals/{id}` | Goals; PATCH edits or changes lifecycle (active, paused, achieved, abandoned) |
| `GET /me/goals/metrics` | What a goal can track (kind, unit, default area) |
| `GET /me/goals/overview` | `GoalsOverview` |
| `GET /me/goals/today` | `Goal[]`: today's progress |
| `GET /me/streaks` | Move-ring streak, longest this year, stand streak |

### Records

| Method and path | Purpose |
|---|---|
| `POST, GET /me/records`; `GET, PATCH, DELETE /me/records/{id}` | Document metadata |
| `GET /me/records/overview` | `RecordsOverview` (counts, recent, shares covering records) |
| `POST /me/records/{id}/upload` | Signed upload URL (5 min); status → pending |
| `POST /me/records/{id}/confirm` | Server stats the object (size, type); status → attached |
| `GET /me/records/{id}/download` | Signed download URL (5 min), attached only |
| `POST, GET /me/labs`; `GET, PUT, DELETE /me/labs/{id}` | Lab panels with results (manual entry, [D6](10-open-decisions.md)); blood-group results refused |
| `GET /me/labs/latest-result` | Latest result for the dashboard card |

### Care

| Method and path | Purpose |
|---|---|
| `GET /me/medications` | `MedicationsOverview` |
| `GET /me/medications/today` | Today's doses (dashboard) |
| `POST /me/medications`; `GET, PATCH, DELETE /me/medications/{id}` | Medication with schedule times; PATCH `status: stopped` stops it |
| `PUT, DELETE /me/medications/{id}/doses/{scheduled_for}` | `{status: taken \| skipped}`; idempotent; only scheduled doses, 7 days back to 12 h ahead |
| `GET /me/appointments?from&to&upcoming&limit`; `POST`; `PATCH, DELETE /me/appointments/{id}` | User-tracked appointments |
| `POST, GET /me/conditions`; `DELETE /me/conditions/{id}` | Conditions and allergies |
| `GET, PATCH /me/emergency-profile` | Emergency medical ID (assembled from profile, conditions, active meds) |
| `POST, GET /me/emergency-contacts`; `PATCH, DELETE /me/emergency-contacts/{id}` | Contacts, one primary |

### Sharing

| Method and path | Purpose |
|---|---|
| `POST, GET /me/shares`; `DELETE /me/shares/{id}` | Create (viewer email, label, kind, scopes, purpose, expiry), list, revoke |
| `GET /me/shared-with-me` | Grants others gave me: owner, scopes, expiry |
| `GET /me/family` | Me + everyone who shares with me. Each summary is read through the consent gate, so fields outside the granted scopes are null |
| `GET /me/access-log` | Every decision about my data |
| `GET, PATCH /me/privacy` | Privacy centre: the lock-screen medical ID switch and device counts |

### Shared views (consent-checked)

| Method and path | Scope |
|---|---|
| `GET /users/{id}/health/dashboard` | vitals (the readiness score is left out: it also uses sleep) |
| `GET /users/{id}/health/latest`, `/health/series/blood_pressure` | the metrics' scopes / vitals |
| `GET /users/{id}/health/series/{metric}` | the metric's scope |
| `GET /users/{id}/health/tabs/{tab}` | the tab's scope (sleep → sleep, mental/digital → activity, others → vitals) |
| `GET /users/{id}/workouts`, `/training` | activity |
| `GET /users/{id}/nutrition/day` | nutrition |
| `GET /users/{id}/records`, `/labs`, `/medications`, `/appointments`, `/conditions` | records |
| `GET /users/{id}/summary` | the family card (vitals and/or records; fields outside the share are null) |
| `GET /users/{id}/records/{rid}/download` | records |

Viewers never write. There are no `POST`, `PATCH` or `DELETE` routes under `/users/{id}`.

### Notifications and library

| Method and path | Purpose |
|---|---|
| `GET /me/notifications?unread=` | Feed, newest first |
| `POST /me/notifications/read` | `{ids}` or `{all: true}` → `{updated}` |
| `GET /library` | `Library` (categories, featured web + mobile, trending) |
| `GET /library/articles/{slug}` | One article (Markdown body) |
| `GET /library/categories`, `/library/categories/{code}` | Categories; articles in a category |

### Operations

| Method and path | Purpose |
|---|---|
| `GET /health` | Process up |
| `GET /ready` | DB reachable (the uptime ping hits this) |
| `POST /internal/jobs/nightly` | Scheduler only; `X-Job-Secret` compared in constant time; runs synchronously; returns a summary |

That's about 95 operations, and all are built. Most are small CRUD routes. The hard parts are ingest, the consent
gate, the health tab aggregations and the nightly job.

## 5.5 Example payloads

Ingest request:

```json
{
  "idempotency_key": "0192a3f4-7c1e-7d2a-9b3c-5e6f7a8b9c0d",
  "platform": "healthkit",
  "devices": [
    { "external_id": "watch-7F3A", "name": "Apple Watch Series 9", "kind": "wearable",
      "metrics": ["heart_rate", "steps", "active_energy", "sleep_deep"] }
  ],
  "buckets": [
    { "metric": "steps", "start": "2026-09-13T08:40:00-05:00", "value": 412, "samples": 3 },
    { "metric": "heart_rate", "start": "2026-09-13T08:40:00-05:00", "value": 71.5, "samples": 60 }
  ]
}
```

Response `202`:

```json
{ "batch_id": 18234, "submitted": 2, "inserted": 1, "updated": 1, "unchanged": 0, "replayed": false }
```

Refused shared read (`403`):

```json
{ "error": { "code": "scope_not_granted", "message": "This share doesn't include sleep data.",
             "detail": { "scope": "sleep" }, "request_id": "req_01J8Z6…" } }
```
