# 1. Introduction

## What is being built

VitalLink brings together one person's health and fitness data: readings from their phone and
watch, workouts, meals, goals, medications, appointments and documents. It turns that data into a
dashboard and plain-language insights. The person can also share chosen parts with someone they
trust, such as a doctor or a family member, for a limited time.

The backend is the single server behind the web portal. Its jobs:

1. **Hold the data.** It stores everything the clients show, owned by the user it describes.
2. **Accept readings.** It takes Apple Health exports, CSV files, Bluetooth uploads and typed readings as
   5-minute aggregates, without duplicates, and updates them when newer data arrives.
3. **Compute.** It produces totals, series, weekly load, streaks, personal records, nutrition totals,
   goal progress, the readiness score and nightly insights.
4. **Guard sharing.** It decides every cross-user read through the consent engine and records every
   decision.
5. **Store files** behind signed links and never makes them public.

## Who uses it

| Actor | What they do | Interface |
|---|---|---|
| **Owner** (consumer) | Imports, logs, reads their own data, shares and revokes | Web portal |
| **Viewer** (consumer) | Reads what an owner shared with them, such as a family member or a doctor using an ordinary account | Same apps, "Shared with me" / Family |
| **Scheduler** | Triggers the nightly job | GitHub Actions, shared secret |

There is no hospital, clinic, provider or admin actor. A doctor who views a patient's shared data
signs up as an ordinary consumer and sees exactly what a family member would.

## Hard constraints

| Constraint | Consequence for the backend |
|---|---|
| Consumers only | No provider portal, no inbound clinical data feeds, no admin console. Content (library, catalogues) ships as seed files in the repo. |
| No blood features | No blood-type, donor or blood-bank tables, enums or endpoints. Blood-group lab analytes are rejected. Blood-era Figma leftovers stay out of the schema. |
| Everything free | Supabase free (500 MB DB, 1 GB storage, 5 GB egress), Render free (512 MB RAM, sleeps after 15 min), GitHub Actions. No paid push, SMS or email providers. |
| Figma is the source of truth | Response shapes match `packages/api/src/types.ts`, which was built from the frames. A field with no frame needs a design decision first (open decisions). |
| Not HIPAA, but regulated | FTC Health Breach Notification Rule applies: any unauthorised disclosure is a breach, and users must be told within 60 days. |

## Functional requirements

FR-1 to FR-12 come from the reviewed design. FR-13 onward cover screens built since then.

| ID | The backend must | Module |
|---|---|---|
| FR-1 | Create the account on first sign-in; keep profile and settings (units, theme, language, goals) | account |
| FR-2 | Accept batched 5-minute aggregates (Apple Health export, CSV, Bluetooth) without duplicates; upsert late corrections | health |
| FR-3 | Serve any metric as a series by hour, day, week or month in the user's time zone | health |
| FR-4 | Build the daily dashboard: steps, energy, sleep, resting HR, readiness score | health |
| FR-5 | Log workouts with sets; weekly load, personal records, training plan and next session | training |
| FR-6 | Log meals and water; daily calories, macros and fibre against targets | nutrition |
| FR-7 | Goals with progress, status (on track / at risk / achieved / paused) and streaks | goals |
| FR-8 | Nightly plain-language insights from the user's own data | goals |
| FR-9 | Store user-uploaded documents; signed upload and download | records |
| FR-10 | Share chosen scopes with a named person for a set time; revoke at any moment | sharing |
| FR-11 | Show the owner every access to their data, allowed or refused | sharing |
| FR-12 | Export or delete all of a user's data | account |
| FR-13 | Lab panels with results, reference ranges, interpretation and the previous value | records |
| FR-14 | Medications with schedules; log doses taken or skipped; due, missed and refill state | care |
| FR-15 | Appointments the user tracks themselves (no booking with a provider) | care |
| FR-16 | Conditions and allergies, plus the emergency medical ID and emergency contacts | care |
| FR-17 | Registry of devices that sync data, with last sync and overdue state | health |
| FR-18 | The nine My Health tabs (overview, vitals, activity, sleep, heart, respiratory, body, mental, digital) | health |
| FR-19 | In-app notification feed generated from the user's own data and events | notifications |
| FR-20 | Family view: people who shared with me, with a summary limited to granted scopes | sharing |
| FR-21 | Health library: categories, featured and trending articles from seeded content | library |

Not in scope: messaging with clinicians and dependent (child) profiles.

## Non-functional requirements

| Quality | Target |
|---|---|
| Latency | p95 < 300 ms for reads once warm (API and DB in the same US-East region) |
| Sync | A batch of up to 5,000 buckets accepted in < 2 s |
| Availability | Best effort on free tier; never lose an acknowledged write |
| Privacy | No cross-user read without a live grant; every decision audited; no health data in logs, push payloads or third parties |
| Security | HTTPS only; we store no passwords; files never publicly addressable |
| Portability | Any Postgres and any container host by changing env vars |
| Cost | $0 |
| Maintainability | One language, typed end to end, OpenAPI is the client contract, tests on every merge |

## Glossary

| Term | Meaning |
|---|---|
| Owner | The user the data is about |
| Viewer | A signed-in user reading an owner's data through a share |
| Share / consent / grant | One row in `consents`: owner → viewer, scopes, start, expiry, revocation |
| Scope | One of `vitals`, `activity`, `sleep`, `nutrition`, `records`; every metric and table maps to one |
| Bucket | A 5-minute window; readings are stored one row per metric per bucket per source |
| Metric catalogue | The closed list of metrics (`metrics` table): unit, scope, plausible range, aggregation |
| Pending endpoint | A frontend call that serves mock data today and shows "isn't available yet" live (`client.pending()`) |
