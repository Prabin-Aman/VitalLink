# VitalLink backend

**Status: phases 1–3 are built.** That's the skeleton (`/health`, `/ready`, the error format,
request ids), the database connection and Alembic, the baseline lockdown with the append-only audit log,
the Docker image and CI, and accounts. Accounts means `users` and `profiles`, token checking,
`GET /v1/me`, `GET/PATCH /v1/me/account` and `GET/PATCH /v1/me/targets`, and the health core (metric
catalogue, idempotent sync ingest, manual readings, series, latest readings, dashboard with readiness,
devices). 69 tests run against real Postgres, including a check that models and migrations match. The Render and Supabase accounts don't
exist yet. Everything else in this folder is still the plan.

The plan builds on the reviewed design doc
["VitalLink Backend System Design"](https://claude.ai/code/artifact/d83166e0-f781-45e9-80e8-d0f399be60cf)
(30 Sep 2026) and adds the domains that the finished frontend needs.

## Run locally

Needs Python 3.12 (`py -3.12`) and Docker. On this machine Docker runs inside WSL Ubuntu, so Docker
Desktop isn't needed. From `backend/`:

```bash
cp .env.example .env                      # then set a password in POSTGRES_PASSWORD and DATABASE_URL
py -3.12 -m venv .venv
.venv/Scripts/python -m pip install -e ".[dev]"
```

Start Postgres. Keep this terminal open, because WSL stops (and the database with it) when no window uses it:

```bash
wsl -d Ubuntu -e sh -c "cd /mnt/c/Users/abhin/Documents/vitallink-app/backend && docker compose up"
```

Then, in another terminal:

```bash
.venv/Scripts/python -m alembic upgrade head            # apply migrations
.venv/Scripts/python -m uvicorn app.main:app --reload   # http://localhost:8000/health, /ready, /docs
.venv/Scripts/python -m pytest                          # uses a separate <db>_test database
.venv/Scripts/python -m ruff check . && .venv/Scripts/python -m ruff format --check .
```

Tests that need the database are skipped, not failed, when Postgres isn't running.

Load the demo account (90 days of data; the last week matches the Figma values):

```bash
.venv/Scripts/python -m seeds.demo
```

Call the API locally with a dev token (accepted only when `ENV=local` or `test`):

```bash
.venv/Scripts/python -m app.devtoken alex@example.com --first Alex --last Carter   # prints a token
curl -H "Authorization: Bearer <token>" http://localhost:8000/v1/me
```

## Container and CI

- `Dockerfile` builds the image Render runs (Python 3.12 slim, non-root user). `start.sh` applies
  migrations, then starts uvicorn on `$PORT`. Render's free tier has no pre-deploy step, so a failed
  migration stops the new container and the old one keeps serving.
- `.github/workflows/backend.yml` runs on every push or PR that touches `backend/`. It lints, applies,
  rolls back and re-applies the migrations, runs the tests on a Postgres service (`REQUIRE_DB=1`, so a
  missing database fails instead of skipping), and builds the image.

Build and run the image locally (inside WSL, next to the running database):

```bash
docker build -t vitallink-api .
docker run --rm --network host --env-file .env -e PORT=8100 vitallink-api   # http://localhost:8100/ready
```

## In one paragraph

VitalLink is a free health and fitness app for people in the Dallas area. It has two clients: the
Expo mobile app (`apps/mobile`) and the React web portal (`apps/web`). Both talk to **one FastAPI
service** (a modular monolith) hosted on **Render's free tier**. The service stores everything in a
private schema in **Supabase Postgres**. **Supabase Auth** handles sign-in and **Supabase Storage**
holds uploaded documents. The phone syncs data from Apple HealthKit and Android Health Connect.
Every user is a consumer who owns their data. Data leaves an account only through a **share** that
the owner creates, which is read-only, time-boxed and revocable. A **consent engine** checks every
such read and writes an entry to an **append-only audit log**. There is no hospital, clinic,
provider or admin interface anywhere.

## Reading order

| # | Doc | What's in it |
|---|---|---|
| 1 | [Introduction](docs/01-introduction.md) | Purpose, users, scope, hard constraints, requirements |
| 2 | [Architecture](docs/02-architecture.md) | System diagram, layers, the 10 modules and what each owns |
| 3 | [Class diagram](docs/03-class-diagram.md) | Domain model and service-layer classes (UML, Mermaid) |
| 4 | [Database](docs/04-database.md) | ER diagrams, every table with its constraints, indexes, deletion rules |
| 5 | [API](docs/05-api.md) | Conventions and the endpoint catalogue, mapped to `packages/api` client calls |
| 6 | [Security & consent](docs/06-security-and-consent.md) | Consent rules, Supabase lockdown, threats, privacy commitments |
| 7 | [Key flows](docs/07-flows.md) | Sequence diagrams: sign-in, sync, sharing, upload, doses, nightly job |
| 8 | [Operations & testing](docs/08-operations-and-testing.md) | Environments, CI/CD, jobs, observability, capacity, backups, tests |
| 9 | [Build plan](docs/09-build-plan.md) | Phased steps with exit criteria and which screens go live in each |
| 10 | [Open decisions](docs/10-open-decisions.md) | Choices the owner must make before or during the build |

Diagrams are Mermaid, so GitHub and VS Code render them inline.

## Stack (all free tier)

| Concern | Choice |
|---|---|
| Language / framework | Python 3.12, FastAPI, Pydantic v2 |
| Database access | SQLAlchemy 2 (async) + asyncpg, Alembic migrations |
| Database | Supabase Postgres (us-east-1), private `vitallink` schema, session pooler :5432 |
| Auth | Supabase Auth (email confirmation on, optional MFA); API verifies JWTs via JWKS |
| Files | Supabase Storage, one private bucket, 5-minute signed URLs |
| Hosting | Render free web service (Virginia), one Docker image |
| Scheduled work | GitHub Actions cron → `POST /internal/jobs/nightly` |
| Analysis | NumPy / SciPy (correlations, trends) in the nightly job only |
| Tests | pytest + httpx against a real Postgres (Docker locally, service container in CI) |

## Planned layout

```
backend/
  README.md                this file
  docs/                    the design (start here)
  app/
    main.py                FastAPI app, routers mounted under /v1
    core/                  settings, db, security, consent engine, audit, errors, jobs, ids
    modules/
      account/             users, profiles, settings, export, delete
      health/              metric catalogue, ingest, series, dashboard, My Health tabs, devices
      training/            exercises, workouts, sets, plans, records, weekly load
      nutrition/           foods, meals, hydration, targets, daily totals
      goals/               goals, progress, streaks, insights
      records/             documents + storage, lab panels and results
      care/                medications + doses, appointments, conditions, emergency profile
      sharing/             consents, shared views, family view, access log
      notifications/       in-app feed, push tokens
      library/             health articles (seeded content)
  migrations/              Alembic
  seeds/                   metric catalogue, exercises, foods subset, library articles, demo user
  tests/                   unit / integration / api / guardrails / deployment checks
  Dockerfile, docker-compose.yml, pyproject.toml, .env.example
```

Each folder under `app/` that exists today contains a `README.md` that describes its job. The Python
files arrive in the build phases.

## Rules that never bend

- **Consumers only.** No route, table, enum or role for hospitals, clinics, providers or admins.
  A guardrail test fails the build if one appears.
- **No blood features.** No blood type, donors or blood banks. Blood *pressure*, glucose and oxygen
  are ordinary vitals. Lab entry rejects blood-group analytes, so a blood type can't get in that way.
- **Everything free.** Free tiers only, with no card on file.
- **The API is the only door.** No client reads Postgres directly. Every cross-user read goes through
  the consent engine and gets an audit entry, whether it's allowed or refused.
