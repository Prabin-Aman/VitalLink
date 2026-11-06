# 2. Architecture

## System context

```mermaid
flowchart LR
  W[Web portal<br/>React, on Vercel]

  subgraph Render["Render (free, Virginia)"]
    API[VitalLink API<br/>Spring Boot modular monolith, Java 25]
  end
  subgraph Supabase["Supabase (free, us-east-1)"]
    AUTH[Auth]
    DB[(Postgres<br/>private schema vitallink)]
    ST[(Storage<br/>private bucket)]
  end
  GH[GitHub Actions<br/>cron + CI]

  W -->|sign in| AUTH
  W -->|HTTPS + JWT| API
  AUTH -. JWKS public keys .-> API
  API -->|session pooler :5432| DB
  API -->|sign URLs, verify objects| ST
  W -. PUT / GET via signed URL .-> ST
  GH -->|POST /internal/jobs/nightly| API
  GH -->|weekly encrypted pg_dump| DB
```

- The portal signs in with Supabase Auth and send the access token on every API call.
- Only the API talks to Postgres. The Supabase Data API is switched off ([security](06-security-and-consent.md)).
- File bytes go straight between the client and Storage through signed URLs, never through the API.
- A website can't read phone health stores, so Apple Health data arrives as the Health app's export file.
- The server's notification feed is a list the user reads in the portal. There's no push service.

## Why a modular monolith

One deployable service with strict internal boundaries. It fits the free tier (one web service),
a five-person team and simple debugging, and any module can be split out later. Microservices would
add network hops, multiple deploys and distributed transactions, and give nothing back at this scale.

## Layers inside the service

```mermaid
flowchart TB
  F[Filters<br/>request id · one transaction per /v1 request] --> R
  R[Controller<br/>HTTP in/out, request/response records, Bean Validation] --> D[Argument resolvers<br/>CurrentUser from the JWT · ConsentGate for /users/&#123;id&#125;]
  D --> S[Service<br/>rules of one module + SQL on its own tables, JdbcClient]
  S -. other module's service only .-> S2[Service of another module]
  S --> C[(Postgres)]
  R & D & S --> CORE[Core<br/>settings · db · security · consent · audit · storage · errors · ids]
```

| Layer | Does | Never does |
|---|---|---|
| Filters | Request id on every response and log line; wrap each `/v1` request in one transaction that rolls back on any error status | Business rules |
| Controller | Bind and validate the request, call one service, return its record | SQL, business rules |
| Argument resolvers | Verify the token and load the caller (`CurrentUser`); give shared routes a `ConsentGate` for the path's user | Load module data |
| Service | Rules of one module and the SQL on its own tables (bound parameters only); calls other modules **through their service** | Touch another module's tables |
| Core | Shared infrastructure; knows no module | Depend on a module |

As built, each module's service holds its own SQL: a separate repository class added no value for
plain `JdbcClient` queries. The boundaries are a convention checked in review; guardrail tests check
that every shared route takes a `ConsentGate`, that shared routes only read, and that every `/v1`
route identifies its caller.

## Modules

The design doc had 6 domain modules. The finished frontend needs 10: Records is split into
records and care, and sharing, notifications and library are new.

| Module | Responsibilities | Owns tables | Calls |
|---|---|---|---|
| **account** | User from token (insert-if-absent), profile, settings, targets, export, delete | `users`, `profiles` | every module's export/delete hooks |
| **health** | Metric catalogue, ingest + idempotency, series, summaries, dashboard, readiness, My Health tabs, devices, manual readings (mood, weight, BP) | `metrics`, `health_readings`, `ingest_batches`, `devices` | account (timezone, goals) |
| **training** | Exercise catalogue, workouts + sets, plans + planned sessions, weekly load, PRs, muscle-group sets | `exercises`, `workouts`, `exercise_sets`, `training_plans`, `plan_sessions` | health (HR zones, source label) |
| **nutrition** | Food search, meals with copied macros, hydration, targets, daily totals, burn estimate | `foods`, `meal_entries`, `meal_items`, `hydration_logs`, `nutrition_targets` | account (profile for BMR), health (active energy) |
| **goals** | Goals, progress and status computed on read, streaks, consistency grid; the nightly job marks reached goals achieved | `goals` | health, training, nutrition (read totals) |
| **records** | Documents, signed upload/confirm/download, lab panels and results with previous value | `health_records`, `lab_panels`, `lab_results` | core storage |
| **care** | Medications, schedules, dose log, appointments, conditions/allergies, emergency profile + contacts | `medications`, `medication_schedules`, `dose_logs`, `appointments`, `conditions`, `emergency_profiles`, `emergency_contacts` | account (profile) |
| **sharing** | Create/list/revoke shares, shared-with-me, shared views, family view, access log, privacy centre | `consents`, `audit_log` (through core audit writer) | every module's read service, called with a scope |
| **notifications** | Feed, mark read, generation from events and the nightly job, retention | `notifications` | none (others call it) |
| **library** | Categories, featured, trending, article body; Markdown in `src/main/resources/library/`, loaded at startup | `article_categories`, `articles`, `library_features` | none |

`messages` (clinician messaging) and `dependents` (child profiles) are **not modules** until
decisions D1 and D3 are made.

## Shared core

| Part | Job |
|---|---|
| `Settings` | Every config value comes from env vars (`application.yml`); nothing environment-specific in code |
| `db` | HikariCP pool (10); `RequestTransactionFilter`: one transaction per request, committed on success, rolled back on any error status, body held until the commit |
| `security` | `TokenVerifier`: JWT signature, issuer, audience and expiry against Supabase JWKS (cached 10 min); reads `email_verified` and `aal` (MFA) claims. `IdentityAdmin` deletes the sign-in identity on account delete |
| `consent` | `Consent.decide()`, a pure function, plus `ConsentGate`, the wrapper that loads grants, decides, binds, audits and tells `AccessListener`s |
| `audit` | `AuditLog`, the only code that inserts into `audit_log`. It runs in its own transaction (`REQUIRES_NEW`) |
| `ErrorHandler` | One error envelope `{"error": {code, message, detail, request_id}}`; exception → status mapping |
| `Ids`, `Clock`, `Num` | UUIDv7/v5, "now" at the database's microsecond precision, half-even rounding |
| `storage` | `FileStorage`: Supabase Storage (sign upload/download, stat, delete) or signed local files in dev/test |
| `userdata` | `UserData`, the export/delete hook each module implements |
| `jobs` (module) | `NightlyJob`: per-user steps, each in its own transaction, then global cleanup; returns a summary |

Rate limiting: 60 ingest / 3,000 other requests per user per hour (`core/web/RateLimiter`, in memory).

## Cross-cutting decisions

| Decision | Choice | Why |
|---|---|---|
| IDs | UUIDv7 for every exposed id; composite keys for `health_readings`; identity bigint for `audit_log` and `ingest_batches` | Not enumerable, and time-ordered for btree locality |
| Time | `timestamptz` UTC in the DB; user IANA timezone on `users`; day grouping in the user's zone | "Today" for a Dallas user runs midnight to midnight Central |
| Units | SI in the DB (m, kg, °C, ml); the API returns SI; view-models convert per `profiles.units` | Fixes the km vs miles mix noted in CLAUDE.md |
| Display strings | The API returns data, and `packages/api` view-models format it. Exceptions are fields the types already define as server text (`detail`, `assessment`, insight `text`) | Same contract as the mock |
| Enums | Postgres enums for closed sets; a lookup table for the metric catalogue (grows, carries ranges) | Bad values are rejected by the DB |
| Soft delete | None. Deletes are hard and cascade from `users`. Lifecycle states where a row stays visible (`status`) | No `deleted_at` leak risk |
| JSON | None stored as built (`insights` and workout routes aren't built) | Anything queried is a column |
