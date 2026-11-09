# VitalLink backend

One Spring Boot service (a modular
monolith) serves the web portal: accounts with export and delete, the health core
(sync ingest, series, dashboard, devices, My Health overview and seven tabs), sharing with the consent
engine and the append-only audit log, training, nutrition, care (medications and doses, appointments,
conditions, the emergency medical ID), goals and streaks, records with stored files and lab panels,
notifications, the health library, the family view, and the nightly job. 239 tests run against a real
Postgres (80.4% of lines covered). Every frontend screen reads live data.

The plan builds on the reviewed design doc
["VitalLink Backend System Design"](https://claude.ai/code/artifact/d83166e0-f781-45e9-80e8-d0f399be60cf)
(30 Sep 2026) and adds the domains the finished frontend needs.

## Run locally

Needs **Java 25** and Docker (PostgreSQL and the Mailpit mail catcher run in it; see the repo's
`REQUIREMENTS.md` for every tool and package). On this machine Docker runs inside WSL Ubuntu. Maven is not needed: the
wrapper (`./mvnw`, or `mvnw.cmd` in PowerShell) downloads it. From `backend/`:

```bash
cp .env.example .env                      # then set a password in POSTGRES_PASSWORD and DATABASE_URL
```

Start Postgres. Keep this terminal open, because WSL stops (and the database with it) when no window uses it:

```bash
wsl -d Ubuntu -e sh -c "cd /mnt/c/Users/abhin/Documents/vitallink-app/backend && docker compose up"
```

Then, in another terminal:

```bash
./mvnw spring-boot:run          # http://localhost:8000/health, /ready, /docs (Swagger UI), /openapi.json
./mvnw test                     # every test, against <db>_test next to your database
./mvnw verify                   # tests + coverage report (target/site/jacoco/index.html)
./mvnw spotless:apply           # format the code (CI runs spotless:check)
```

The API applies database migrations itself when it starts (Flyway). Tests that need the database are
skipped, not failed, when Postgres isn't running (`REQUIRE_DB=1` makes them fail instead, as in CI).

Load the demo account (90 days of data in every module; the last week matches the Figma values):

```bash
./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.DemoSeed
```

Check the performance targets against a running API (5,000-bucket ingest < 2 s, warm p95 < 300 ms):

```bash
./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.LoadCheck -Dexec.args="http://localhost:8000"
```

Call the API locally with a dev token (accepted only when `ENV=local` or `test`):

```bash
./mvnw -q exec:java -Dexec.mainClass=com.vitallink.api.tools.DevToken -Dexec.args="demo@example.com"
curl -H "Authorization: Bearer <token>" http://localhost:8000/v1/me
```

See it in the web portal with live data: run the API, then `VITE_API_URL=http://localhost:8000 npm run
web` from the repo root, and in the browser console `sessionStorage.setItem("vl.token", "<token>")`.

Run the nightly job by hand (set `JOB_SECRET` in `.env`):

```bash
curl -X POST -H "X-Job-Secret: <JOB_SECRET>" http://localhost:8000/internal/jobs/nightly
```

### A local database from the Python version

A database migrated by Alembic (0001–0006) has the same schema as V1–V6. Keep its data by baselining
Flyway once; V7–V10 then apply on top:

```bash
./mvnw -q -DskipTests package
java -jar target/vitallink-api.jar --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=6
# then, in psql: DROP TABLE vitallink.alembic_version;
```

The test database is recreated automatically.

## Container and CI

- `Dockerfile` builds the image Render runs: Maven build stage, then a JRE-only image as a non-root
  user. Flyway migrates at startup; if a migration fails the process exits and Render keeps serving the
  previous version.
- `.github/workflows/backend.yml` runs on every push or PR that touches `backend/`: formatting check,
  all tests on a Postgres service container (fresh database, so every migration runs from scratch),
  coverage, and the image build.
- `.github/workflows/nightly.yml` wakes the API and calls the nightly job at 09:00 UTC. It needs the
  repository secrets `API_URL` and `JOB_SECRET`.

```bash
docker build -t vitallink-api .
docker run --rm --network host --env-file .env -e PORT=8100 vitallink-api   # http://localhost:8100/ready
```

## Configuration

| Variable | Meaning |
|---|---|
| `ENV` | `local`, `test` or `production` (dev tokens and the demo seed only work in local/test) |
| `DATABASE_URL` | `postgresql://user:pass@host:port/db` (as Supabase shows it) or a JDBC URL with `DATABASE_USER` / `DATABASE_PASSWORD` |
| `CORS_ORIGIN` | The web portal's origin; locally a comma-separated list |
| `SUPABASE_URL` | Production: tokens are verified against this project's JWKS |
| `SUPABASE_SERVICE_ROLE_KEY` | Production: Storage signing and deleting sign-in identities (server-side only) |
| `STORAGE_BUCKET` | Private bucket for record files (default `records`) |
| `JOB_SECRET` | Shared with the nightly workflow |
| `DEV_JWT_SECRET` | Local/test only: signs dev tokens |
| `PORT` | Render sets it; 8000 locally |

## In one paragraph

VitalLink is a free health and fitness app for people in the Dallas area. Its client is the React
web portal (`apps/web`), which talks to **one Spring Boot
service** (a modular monolith) hosted on **Render's free tier**. The service stores everything in a
private schema in **Supabase Postgres**. **Supabase Auth** handles sign-in and **Supabase Storage**
holds uploaded documents. Apple Health data arrives as the export file from the Health app; Bluetooth devices pair in the browser.
Every user is a consumer who owns their data. Data leaves an account only through a **share** that
the owner creates, which is read-only, time-boxed and revocable. A **consent engine** checks every
such read and writes an entry to an **append-only audit log**. There is no hospital, clinic,
provider or admin interface anywhere.

## Reading order

| # | Doc | What's in it |
|---|---|---|
| 1 | [Introduction](docs/01-introduction.md) | Purpose, users, scope, hard constraints, requirements |
| 2 | [Architecture](docs/02-architecture.md) | System diagram, layers, the modules and what each owns |
| 3 | [Class diagram](docs/03-class-diagram.md) | Domain model and the as-built Java classes |
| 4 | [Database](docs/04-database.md) | ER diagrams, every table with its constraints, indexes, migrations V1–V11 |
| 5 | [API](docs/05-api.md) | Conventions and the endpoint catalogue, mapped to `packages/api` client calls |
| 6 | [Security & consent](docs/06-security-and-consent.md) | Consent rules, Supabase lockdown, threats, privacy commitments |
| 7 | [Key flows](docs/07-flows.md) | Sequence diagrams: sign-in, sync, sharing, upload, doses, nightly job |
| 8 | [Operations & testing](docs/08-operations-and-testing.md) | Environments, CI/CD, jobs, observability, capacity, tests |
| 11 | [Java stack](docs/11-java-stack.md) | Every package, library, plugin and tool, with versions and why |
| 12 | [Test report](docs/12-test-report.md) | What each test suite covers, results and coverage |

Diagrams are Mermaid, so GitHub and VS Code render them inline.

`docs/VitalLink_Backend_Report.docx` (and its PDF) is the 15 Oct 2026 report on the Python build of
phases 1–5b. The Markdown docs above describe the current Java build; regenerate the report from them.

## Stack (all free tier)

| Concern | Choice |
|---|---|
| Language / framework | Java 25, Spring Boot 4.1 (Spring MVC on virtual threads), Jakarta Bean Validation |
| Database access | Spring `JdbcClient` (plain SQL) + HikariCP, Flyway migrations |
| Database | Supabase Postgres (us-east-1), private `vitallink` schema, session pooler :5432 |
| Auth | Supabase Auth (email confirmation on, optional MFA); API verifies JWTs via JWKS (Nimbus JOSE) |
| Files | Supabase Storage, one private bucket, 5-minute signed URLs |
| API docs | springdoc-openapi: `/openapi.json`, Swagger UI at `/docs` |
| Hosting | Render free web service (Virginia), one Docker image |
| Scheduled work | GitHub Actions cron → `POST /internal/jobs/nightly` |
| Tests | JUnit 5 + MockMvc + AssertJ against a real Postgres; JaCoCo coverage; Spotless formatting |

## Layout

```
backend/
  README.md, docs/              the design and this documentation
  pom.xml, mvnw, .mvn/          the Maven build (wrapper included)
  src/main/java/com/vitallink/api/
    core/                       settings, db, security, audit, consent, storage, errors, web
    account/ health/ training/ nutrition/ sharing/ care/ goals/ records/
    notifications/ library/ jobs/ ops/ tools/    one package per module, each with a README
  src/main/resources/
    application.yml             configuration (env vars)
    db/migration/V1…V11__*.sql  Flyway migrations
    library/                    health library articles (Markdown)
  src/test/java/…               the test suites
  Dockerfile, docker-compose.yml, .env.example
```

## Rules that never bend

- **Consumers only.** No route, table, enum or role for hospitals, clinics, providers or admins.
  A guardrail test fails the build if one appears.
- **No blood features.** No blood type, donors or blood banks. Blood *pressure*, glucose and oxygen
  readings are fine. Lab results for blood group are refused, and the library has no blood category.
- **Everything free.** Free tiers only.
- **Every shared read is consent-checked and audited.** Every `/users/{user_id}/…` route takes a
  `ConsentGate`, and none of them writes (guardrail tests).

Design docs and the test report: [docs/README.md](docs/README.md).
