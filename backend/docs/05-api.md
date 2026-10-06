# 5. API

A JSON REST API under `/v1`. FastAPI generates the OpenAPI document (`/openapi.json`, `/docs`).
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
| Rate limits | Per user: 60 ingest / 600 other per hour (in memory, single instance) |
| Versioning | Additive changes stay in `/v1`; breaking changes go to `/v2` |

## 5.2 Endpoints that are live (built and called by the client)

These are the paths `VitalLinkClient` requests through `get()`; each exists in the backend.

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

These were marked live in the client before they existed and are pending again until their phase:
`workouts()`, `goals()` (phase 5) and `medications()` (phase 7).

## 5.3 Endpoints for the pending screens

Each row replaces one `client.pending()` call. When the endpoint ships, that method switches to
`get()` and the screen stops showing "isn't available yet" in live mode.

| Client call (pending today) | Method and path | Returns | Module |
|---|---|---|---|
| `bloodPressureLatest()` | `GET /me/health/latest?metrics=bp_systolic,bp_diastolic` | `Reading[]` | health |
| `streaks()` | `GET /me/streaks` | `{label, days}[]` | goals |
| `nextAppointment()` | `GET /me/appointments?upcoming=true&limit=1` | `Appointment[]` | care |
| `latestLab()` | `GET /me/labs/latest-result` | `LabResult` + panel name | records |
| `privacySettings()` | `GET /me/privacy` | `{emergency_on_lock_screen, devices: {total, syncing, overdue}}` | sharing |
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

Two shapes should change when they go live. The mock serves pre-formatted strings for
`nextAppointment` (`{with, when}`) and `latestLab` (`{value: "14.2 g/dL", note}`). The live endpoints
return the structured `Appointment` and `LabResult` instead, and `packages/api/src/screens.ts`
formats them like every other view-model. That's a frontend change in the same phase.

## 5.4 Full catalogue

### Account

| Method and path | Purpose |
|---|---|
| `GET /me` | Account; creates it on first call (insert-if-absent) |
| `GET, PATCH /me/account` | Profile and settings: names, DOB, sex, height, phone, ZIP, city, language, units, theme. `two_factor` is read from the token's MFA factors, never stored |
| `GET, PATCH /me/targets` | Daily goals (steps, move, exercise, stand, sleep): one source for the dashboard, rings and goals |
| `GET /me/export` | Every row the user owns, plus record file names, as one JSON download |
| `DELETE /me` | Revoke received shares → delete stored files → delete rows (cascade) → delete the Supabase identity. Needs a recent token |

### Health

| Method and path | Purpose |
|---|---|
| `POST /me/health/ingest` | `{idempotency_key, platform, devices[], buckets[]}`, ≤ 5,000 buckets; 202 `{inserted, updated, unchanged, replayed}` |
| `POST /me/health/manual` | One manual reading (mood, weight, waist, BP from a cuff without sync…) for metrics with `manual_allowed` |
| `GET /me/health/dashboard` | Today's summary + readiness score |
| `GET /me/health/series/{metric}` | `days` ≤ 400 (the local days ending today), `interval` hour/day/week/month (weeks start Monday). Aggregation comes from the catalogue. Totals fill empty buckets with 0; averages skip them |
| `GET /me/health/summary/{metric}` | Latest, 7-day and 30-day averages, trend |
| `GET /me/health/latest?metrics=` | Latest `Reading` for each metric, with assessment |
| `GET /me/health/overview` | `HealthOverview` (rings, sections, readings, sources, insights) |
| `GET /me/health/tabs/{tab}` | The seven tab payloads; activity uses series + workouts |
| `GET /me/devices`, `DELETE /me/devices/{id}` | Registry; delete only forgets the device, readings stay |

### Training

| Method and path | Purpose |
|---|---|
| `POST, GET /me/workouts`; `GET, PATCH, DELETE /me/workouts/{id}` | Workouts with sets |
| `GET /me/training?week=` | `Training`: week aggregate, sessions, muscle sets, records, next planned session, lifetime total |
| `GET /me/training/records` | Personal records with estimated one-rep max |
| `POST, GET /me/training/plans`; `PATCH /me/training/plans/{id}` | Plans and planned sessions; activating one archives the previous |
| `GET /exercises` | Exercise catalogue |

### Nutrition

| Method and path | Purpose |
|---|---|
| `GET /foods/search?q=` | Trigram search over USDA subset + the caller's own foods |
| `POST /me/foods` | Custom food |
| `POST /me/meals`; `PATCH, DELETE /me/meals/{id}` | Meals with items (macros copied) |
| `POST /me/hydration`; `DELETE /me/hydration/{id}` | Water |
| `GET /me/nutrition/day?date=` | `NutritionDay` |
| `GET, PUT /me/nutrition/targets` | Targets |

### Goals and insights

| Method and path | Purpose |
|---|---|
| `POST, GET /me/goals`; `GET, PATCH, DELETE /me/goals/{id}` | Goals; PATCH changes lifecycle (pause, resume, complete) |
| `GET /me/goals/overview` | `GoalsOverview` |
| `GET /me/streaks` | Current and longest streaks |
| `GET /me/insights?area=`; `POST /me/insights/{id}/dismiss` | Insights |

### Records

| Method and path | Purpose |
|---|---|
| `POST, GET /me/records`; `GET, PATCH, DELETE /me/records/{id}` | Document metadata |
| `GET /me/records/overview` | `RecordsOverview` (counts, recent, shares covering records) |
| `POST /me/records/{id}/upload` | Signed upload URL (5 min); status → pending |
| `POST /me/records/{id}/confirm` | Server stats the object (size, type); status → attached |
| `GET /me/records/{id}/download` | Signed download URL (5 min), attached only |
| `POST, GET /me/labs`; `GET, PATCH, DELETE /me/labs/{id}` | Lab panels with results (manual entry, [D6](10-open-decisions.md)) |
| `GET /me/labs/latest-result` | Latest result for the dashboard card |

### Care

| Method and path | Purpose |
|---|---|
| `GET /me/medications` | `MedicationsOverview` |
| `GET /me/medications/today` | Today's doses (dashboard) |
| `POST /me/medications`; `PATCH, DELETE /me/medications/{id}` | Medication with schedule times |
| `PUT /me/medications/{id}/doses/{scheduled_for}` | `{status: taken \| skipped}`; idempotent |
| `POST, GET /me/appointments`; `PATCH, DELETE /me/appointments/{id}` | User-tracked appointments |
| `POST, GET /me/conditions`; `DELETE /me/conditions/{id}` | Conditions and allergies |
| `GET, PUT /me/emergency-profile` | Emergency medical ID (assembled from profile, conditions, active meds) |
| `POST, GET /me/emergency-contacts`; `PATCH, DELETE /me/emergency-contacts/{id}` | Contacts, one primary |

### Sharing

| Method and path | Purpose |
|---|---|
| `POST, GET /me/shares`; `DELETE /me/shares/{id}` | Create (viewer email, label, kind, scopes, purpose, expiry), list, revoke |
| `GET /me/shared-with-me` | Grants others gave me: owner, scopes, expiry |
| `GET /me/family` | Me + everyone who shares with me. Each summary is read through the consent gate, so fields outside the granted scopes are null |
| `GET /me/access-log` | Every decision about my data |
| `GET /me/privacy` | Privacy centre switches and counts |

### Shared views (consent-checked)

| Method and path | Scope |
|---|---|
| `GET /users/{id}/health/dashboard` | vitals + activity + sleep. Returns only the granted parts; audited as `limited` if partial |
| `GET /users/{id}/health/series/{metric}` | the metric's scope |
| `GET /users/{id}/health/tabs/{tab}` | the tab's scope (sleep → sleep, mental/digital → activity, others → vitals) |
| `GET /users/{id}/workouts`, `/training` | activity |
| `GET /users/{id}/nutrition/day` | nutrition |
| `GET /users/{id}/records`, `/labs`, `/medications`, `/appointments`, `/conditions` | records |
| `GET /users/{id}/records/{rid}/download` | records |
| `GET /users/{id}/summary` | the family card: resting HR (vitals), conditions + medication count + next appointment (records) |

Viewers never write. There are no `POST`, `PATCH` or `DELETE` routes under `/users/{id}`.

### Notifications and library

| Method and path | Purpose |
|---|---|
| `GET /me/notifications?unread=` | Feed, newest first |
| `POST /me/notifications/read` | `{ids}` or `{all: true}` |
| `GET /library` | `Library` (categories, featured web + mobile, trending) |
| `GET /library/articles/{slug}` | One article (Markdown body) |
| `GET /library/categories/{code}` | Articles in a category |

### Operations

| Method and path | Purpose |
|---|---|
| `GET /health` | Process up |
| `GET /ready` | DB reachable (the uptime ping hits this) |
| `POST /internal/jobs/nightly` | Scheduler only; `X-Job-Secret` compared in constant time; runs synchronously; returns a summary |

That's about 95 operations. Most are small CRUD routes. The hard parts are ingest, the consent
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
