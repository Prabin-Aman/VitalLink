# 12. Test report

Run on 24 Oct 2026 on Windows 11, Java 25, Postgres 16 (Docker in WSL). CI runs the same suites on
Ubuntu with a Postgres service container on every push that touches `backend/`.

## 12.1 Summary

| Check | Command | Result |
|---|---|---|
| Unit, API, database and guardrail tests | `./mvnw verify` | **222 passed, 0 failed, 0 skipped** |
| Coverage (JaCoCo) | `target/site/jacoco/index.html` | **80.4% of lines** (the hand-run `tools` package, now with LoadCheck, counts as uncovered; see 12.4) |
| System test (packaged jar over real HTTP) | `SystemCheck http://localhost:8000` | **44 of 44 steps passed** |
| Load test (packaged jar, demo account) | `LoadCheck http://localhost:8000` | **Pass**: 5,000-bucket ingest 1.1 s (target < 2 s); warm p95 dashboard 40 ms, overview 60 ms, training 25 ms, goals 25 ms (target < 300 ms) |
| Container | `docker build` + run with a 512 MB limit | Ready in 6 s; 231 MiB in use; heaviest endpoint 0.43 s cold |
| Formatting | `./mvnw spotless:check` | Clean |
| Frontend contract | `npm run typecheck` (web + mobile, incl. `contract.ts` against the generated OpenAPI types), `npm run web:build` | Pass |
| Live portal | Web portal against the API with the demo account | All 24 pages render live data; only Messages says "isn't available yet" (D1) |
| Live mobile app | Expo web build (`:8081`) against the API with the demo account | All 18 tab and Profile screens render live data (Messages: D1); Mark Taken saves through a cross-origin PUT |

## 12.2 Test suites

Every API test calls the whole application (filters, the per-request transaction, the error handler)
through MockMvc, against a real Postgres. Each test makes its own accounts, so tests never share data.

| Suite | Tests | What it proves |
|---|---|---|
| `ConsentRulesTest` | 15 | Every rule of `decide()` without a database: owner, unconfirmed email, no grant, grant to someone else, binding follows the account not the email, scope not granted, revoked, not yet valid, expiry exclusive, live grant wins, vitals never opens records |
| `SecurityTest` | 12 | Missing, expired, wrong-audience, wrong-issuer, wrong-signature, no-expiry and garbage tokens → 401; no email → 403; dev tokens can't be minted in production; production never accepts HS256; request ids in headers and error bodies; bodies over 2 MB → 413 |
| `DatabaseTest` | 9 | `/ready`; nothing in `public`; every table has row-level security; the audit log accepts inserts but refuses UPDATE, DELETE and TRUNCATE; refusal reasons only on blocked entries |
| `AccountTest` | 19 | First call creates the account (UUIDv7); emails stored lowercase; 6 parallel first requests create one account; same email with a new identity → 409; defaults; PATCH (null clears, mass assignment and bad values refused); targets; isolation; deleting a user cascades |
| `DataRightsTest` | 4 | Export holds the user's data and nobody else's; **every table with user data is exported** (guardrail); delete needs a sign-in in the last 10 minutes; delete removes everything and revokes shares received, and a later sign-up with that email inherits nothing |
| `HealthTest` | 30 | Catalogue in the database matches the code and has no blood features; ingest inserts → updates → unchanged; idempotent replay; same key + new payload → 409; keys per user; invalid buckets reject the whole batch; duplicates; 5,001 buckets → 413; day and week series in local time; averages skip gaps; blood pressure pairs and categories; manual readings; dashboard and readiness; devices; isolation |
| `HealthTabsTest` | 11 | The overview and all seven tabs from two weeks of data (resting HR, BMI, distance units, overnight HRV, sleep stages and bedtime consistency, heart zones, VO₂max six months ago, waist and lean-mass change, mood, deep sleep vs late phone use); readings nothing measures are left out; empty accounts get empty tabs; shared tabs need their scope |
| `TrainingTest` | 16 | Exercise catalogue matches; sets numbered per exercise; validation; synced workouts update instead of duplicating; the training view (PRs, volume, pace, zone, streaks, muscle balance note); plans (one active); delete and isolation; can't link another user's planned session |
| `NutritionTest` | 12 | Food catalogue matches (uuid5 ids); search finds seeded and own foods only, wildcards literal; macros copied at logging time; validation; can't use someone else's food; day view (yesterday excluded, water, targets); Mifflin-St Jeor needs a full profile; targets defaults and limits; scoped deletes |
| `SharingTest` | 23 | Grant → read → refused scope → revoke → refused, all in the owner's access log with labels; shared-with-me; shared training needs activity; shared dashboard has no readiness score; unconfirmed email and strangers get 404 like unknown users; not yet valid; expired; binding on first read; refusals audited despite the rollback; duplicate and self shares; validation (no hospital or app kinds); **guardrails: every shared route takes a ConsentGate, shared routes are read-only, every /v1 route identifies its caller, no provider or admin routes** |
| `CareTest` | 17 | Medication → dose logged → taken (idempotent, undo); only scheduled doses, within 7 days back / 12 h ahead; stop and restart; schedule changes and refill due; validation; isolation; appointment statuses (pending, confirmed, cancelled, completed) and the upcoming filter; emergency ID assembled from profile, conditions, medications and contacts (one primary, at most 5); the privacy switch; shared care needs the records scope |
| `DoseStateTest` | 8 | The dose state rules with a fixed clock: taken this morning, due within the 2 h grace, later dose due, last night's dose missed, misses older than a day drop off, weekly medications, frequency wording, refills due within a week |
| `GoalsTest` | 14 | Daily goals average the last 7 days (on track at 85%); no data → at risk; weekly workout goals; target goals from a baseline (halfway → on track, met → achieved); lifecycle (pause, achieve, resume, abandon); consistency grid and step streak; today's progress and streaks; validation (no blood goals); isolation |
| `RecordsTest` | 14 | Upload → confirm → download through signed URLs (tokens can't be reused for another method or tampered with; others can't download; deleting the record deletes the file); the server rejects a stored file of the wrong type; upload limits; overview counts every type; validation; lab panels with previous values and interpretation (lab's reading wins); **blood-group results refused**; lab validation and isolation; shared records need the records scope |
| `FeedLibraryFamilyTest` | 4 | A new lab result is in the feed, and only its owner can mark it read; owners hear (once a day) when shared records are opened; the library loads from resources (categories without blood, featured, trending, no reviewer names, articles by slug); family shows only what each share covers, and the summary read is audited |
| `NightlyJobTest` | 3 | Needs the secret; notifies once about a missed dose, tomorrow's appointment and an overdue device, and a re-run adds nothing; a full run returns a summary with no failed users |
| `RateLimiterTest` | 2 | With a fixed clock: the call over the limit is refused with 429 `rate_limited` and the seconds to the next window, while other calls and other users keep their own counts; a new hour starts a fresh count |
| `CorsTest` | 3 | A preflight from each listed origin (comma-separated `CORS_ORIGIN`) is allowed; any other origin gets 403 and no allow header |
| `OpenApiTest` | 2 | The OpenAPI document uses the names the API sends (no camelCase anywhere), and the caller and consent gate are not documented as parameters while shared routes declare `user_id` |
| `SupabaseStorageTest` | 4 | The production storage and identity clients against a fake Supabase: the right REST calls with the service key, size and type from HEAD, missing objects, failures surfacing as "storage unavailable" (503) |

## 12.3 System test

`tools/SystemCheck` drives a running server the way the apps do, over real HTTP: two new accounts;
health sync and series; manual reading; dashboard; the overview and seven tabs; a workout; a meal; a
medication and a dose; an appointment; the emergency ID; a record with a file uploaded to its signed
URL, confirmed and downloaded byte-for-byte; a lab panel (and a refused blood-group result); the feed;
a goal; the library; a share → reads → a refused scope → family view → access log → revoke → refused;
the nightly job; export; and deleting both accounts. CI runs it against the packaged jar.

Health tests run their accounts in a fixed-offset zone where it is mid-afternoon (`ApiTest.AFTERNOON`),
so "today at 9:00" is never in the future, whatever time the suite runs.

## 12.4 Coverage by package (lines)

| Package | Lines covered | Package | Lines covered |
|---|---|---|---|
| account | 98.1% | notifications | 97.6% |
| care | 87.1% | nutrition | 99.0% |
| core | 83.2% | ops | 77.8% |
| core.audit | 95.5% | records | 87.1% |
| core.consent | 99.0% | sharing | 83.7% |
| core.db | 83.1% | training | 97.5% |
| core.security | 89.5% | goals | 88.6% |
| core.storage | 80.9% | health | 93.4% |
| core.userdata | 100% | jobs | 95.5% |
| core.web | 85.6% | library | 93.3% |

`tools` (DevToken, DemoSeed, SystemCheck, LoadCheck) is run by hand and in CI, not by the unit suites, so it reads
0% there; the system test exercises the API end to end instead.

## 12.5 Not covered yet

- Real Supabase Auth and Storage (no project yet): the clients are tested against a fake; check the
  first deploy with the system test pointed at a staging project.
- Phone sync from HealthKit and Health Connect (phase 9, needs devices).
- Load tests ran locally (12.1), not yet against Render's free instance (0.1 CPU); rerun LoadCheck there
  after the first deploy.
- Visual comparison of every live screen against Figma (the screens were compared in mock mode; live
  data differs from the Figma copy as listed in CLAUDE.md).
