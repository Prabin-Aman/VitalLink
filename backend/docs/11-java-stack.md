# 11. Java stack: packages, libraries and tools

The backend was first built in Python (FastAPI) through phase 5b. On 23 Oct 2026 the owner decided the
backend must be **Java**. The service was ported to Spring Boot with the same database schema, the same
API paths and JSON shapes, and every Python test was ported before the Python code was removed. The
remaining phases (care, goals, records, phase 8) were then built in Java.

Everything below is free and open source. Versions are the ones the build resolves today (`./mvnw
dependency:list`).

## 11.1 Runtime and build

| Tool | Version | Why |
|---|---|---|
| Java (JDK) | 25 (LTS) | Current long-term-support release; records, pattern matching, text blocks and virtual threads keep the code short |
| Maven (via the wrapper `mvnw`) | 3.9.16 | The most widely used Java build tool; the wrapper downloads the right version, so nobody installs Maven |
| Docker base images | `eclipse-temurin:25-jdk` (build), `eclipse-temurin:25-jre` (run) | Free OpenJDK builds; the run image has only the JRE and the jar |

## 11.2 Libraries (`pom.xml`)

| Dependency | Version | Used for | Notes |
|---|---|---|---|
| `spring-boot-starter-parent` | 4.1.2 | Dependency versions and plugin defaults for everything below | Spring Boot 3.5 left free support in June 2026, so the current 4.x line is used |
| `spring-boot-starter-webmvc` (Spring MVC 7.0.10, Tomcat 11.0.27) | via Boot | HTTP controllers, JSON, filters, CORS | Blocking MVC with **virtual threads** on (`spring.threads.virtual.enabled`): simple code, cheap threads |
| Jackson 3 (`tools.jackson.core:jackson-databind`) | 3.1.7 | JSON in and out | snake_case names globally; unknown input fields are refused (mass assignment) |
| `spring-boot-starter-validation` (Hibernate Validator) | 9.1.5 | `@NotBlank`, `@Size`, `@Min`… on request records | Errors come back as `422 invalid_request` with field locations, never the submitted values |
| `spring-boot-starter-jdbc` (Spring JDBC `JdbcClient`, HikariCP) | Spring 7.0.10, HikariCP 7.0.2 | All SQL, with named parameters; the connection pool | Plain SQL rather than an ORM: the schema leans on Postgres enums, arrays, `ON CONFLICT`, `generate_series` and `AT TIME ZONE`, which SQL states directly |
| `org.postgresql:postgresql` | 42.7.14 | JDBC driver | |
| `spring-boot-starter-flyway`, `flyway-database-postgresql` | Flyway 12.4.0 | Schema migrations `V1…V10` in `src/main/resources/db/migration`, applied at startup | Migrations are plain SQL; the history table lives in the private schema |
| `com.nimbusds:nimbus-jose-jwt` | 10.10 | Verifying Supabase access tokens (JWKS, ES256/RS256) and local HS256 dev tokens | One small, well-known library instead of the whole Spring Security stack, because the API needs only "who is this bearer token" |
| `springdoc-openapi-starter-webmvc-ui` | 3.1.1 | The OpenAPI document at `/openapi.json` and Swagger UI at `/docs` | |
| SLF4J + Logback | 2.0.20 / 1.5.38 | Logging, with the request id on every line | Boot defaults |

Test-only:

| Dependency | Version | Used for |
|---|---|---|
| `spring-boot-starter-test` (JUnit Jupiter 6.0.3, AssertJ 3.27.7, Mockito 5.23) | via Boot | Test runner, fluent assertions |
| `spring-boot-starter-webmvc-test` (MockMvc) | via Boot | Calling the whole app (filters, transactions, error handler) without a network port |
| JDK `com.sun.net.httpserver` | JDK 25 | A fake Supabase server for the storage and identity clients |

Nothing is mocked that matters: tests run against a **real Postgres** (`<db>_test`), because the
guarantees (upserts, cascades, the append-only trigger, check constraints) live in the database.

## 11.3 Build plugins

| Plugin | Version | Does |
|---|---|---|
| `spring-boot-maven-plugin` | 4.1.2 | Packages one runnable jar (`target/vitallink-api.jar`) |
| `maven-compiler-plugin` | via Boot | Java 25, `-Xlint:all`, parameter names kept |
| `maven-surefire-plugin` | 3.5.6 | Runs the tests |
| `jacoco-maven-plugin` | 0.8.15 | Coverage report at `target/site/jacoco/index.html` after `mvnw verify` |
| `spotless-maven-plugin` + palantir-java-format | 3.10.4 / 2.102.0 | One code format; CI runs `spotless:check`, `spotless:apply` fixes it |
| `exec-maven-plugin` | via Boot | Runs the command-line tools (`DevToken`, `DemoSeed`) |

## 11.4 Package layout (`src/main/java/com/vitallink/api`)

Each package has a README.

| Package | Contents |
|---|---|
| `core` | Settings, errors, ids, clock, rounding, streaks; `db` (pool, per-request transaction), `security` (tokens), `audit`, `consent`, `storage`, `userdata` (export/delete hook), `web` (CORS, argument resolvers, PATCH helper, OpenAPI) |
| `account` | Users and profiles, targets, export and delete |
| `health` | Metric catalogue, ingest, series, latest, dashboard, devices, My Health overview and tabs |
| `training` | Exercises, workouts and sets, plans, the training view |
| `nutrition` | Foods, meals, water, targets, the day view |
| `sharing` | Shares, shared-with-me, access log, privacy centre, family, all shared views |
| `care` | Medications and doses, appointments, conditions, emergency ID and contacts |
| `goals` | Goals, progress and status, overview, today's progress, streaks |
| `records` | Records and stored files, lab panels and results |
| `notifications` | The feed and the access notifier |
| `library` | Articles loaded from `src/main/resources/library` |
| `jobs` | The nightly job and its endpoint |
| `ops` | `/health`, `/ready` |
| `tools` | `DevToken`, `DemoSeed` (local only) |

Each module has a `…Controller` (HTTP only), a `…Service` (rules and SQL for its own tables) and
`…Dtos` (request/response records). Modules call each other only through services.

## 11.5 How the Python design maps to Java

| Python (FastAPI) | Java (Spring Boot) |
|---|---|
| Pydantic models | Java records with Bean Validation annotations; cross-field rules in the record's constructor |
| `model_config = extra="forbid"` | `spring.jackson.deserialization.fail-on-unknown-properties=true` |
| PATCH with `exclude_unset` | `core.web.Patch` over the raw JSON object (missing = leave, null = clear) |
| `Depends(current_user)` | A `CurrentUser` parameter, supplied by an argument resolver |
| `Depends(shared_access)` | A `ConsentGate` parameter for `/users/{user_id}/…` (guardrail test) |
| SQLAlchemy session per request | `RequestTransactionFilter`: one transaction per `/v1` request, rolled back on any error status |
| `write_audit` on its own connection | `AuditLog` with `PROPAGATION_REQUIRES_NEW` |
| Alembic `0001…0006` | Flyway `V1…V6` (identical SQL), then `V7…V10` |
| `round()` (banker's rounding) | `core.Num` uses `HALF_EVEN`, so numbers match |
| `uuid.uuid5` | `core.Ids.uuid5` (byte-for-byte identical, so seeded food ids and dev subjects are unchanged) |
| pytest + httpx TestClient | JUnit 5 + MockMvc |
| ruff | Spotless (palantir-java-format) and `-Xlint:all` |
| `python -m app.devtoken`, `python -m seeds.demo` | `exec:java` with `tools.DevToken`, `tools.DemoSeed` |

An existing local database migrated by Alembic keeps its data: baseline Flyway at version 6 once
(see the README), and V7–V10 apply on top.

## 11.6 Choices considered and not taken

| Option | Why not |
|---|---|
| Spring Data JPA / Hibernate ORM | Mapping Postgres enums, arrays and composite keys is awkward; most reads are aggregates that SQL expresses directly; plain JDBC keeps every query visible and reviewable |
| Spring Security | Pulls in a filter chain, sessions and CSRF handling the API doesn't need; one verifier class with Nimbus is easier to read and test |
| Testcontainers | Needs Docker reachable from the test JVM; on the team's Windows machines Docker runs inside WSL. The tests use the same Postgres as development (`docker compose up`) and a service container in CI |
| Quarkus / Micronaut | Faster cold start, but far fewer learning resources for the team |
| Kotlin | Owner asked for Java |
