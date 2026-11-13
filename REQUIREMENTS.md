# What you need to run VitalLink

Everything here is free. The versions are the ones the project is built and tested with (Nov 2026); newer
patch versions of the tools work too. Package versions are pinned by `package-lock.json` (npm) and
`backend/pom.xml` (Maven), so installing never needs to pick versions by hand.

## 1. Tools to install

| Tool | Version | Needed for |
|---|---|---|
| **Git** | any current | Getting the code |
| **Node.js** (includes npm) | 20 or newer (built with 24.19) | Web portal, `packages/*`, frontend tests |
| **Java JDK** | **25** (built with 25.0.4) | The backend API |
| **Docker** with Compose | Docker 29, Compose v5 | PostgreSQL and Mailpit for local runs |
| **Python** (optional) | 3.12 or newer (built with 3.14) | Only the helper scripts in `scripts/` |

Install commands for each system are in **README.md › Install and run**. On Windows, Docker can be Docker Desktop, or
Docker Engine inside WSL Ubuntu (`wsl --install -d Ubuntu`).

Not needed: **Maven** (the backend uses the Maven wrapper `mvnw` / `mvnw.cmd`, which downloads Maven 3.9.16
on first use) and a separate **PostgreSQL** install (it runs in Docker).

Optional:

| Tool | Needed for |
|---|---|
| `psql`, `pg_dump`, `pg_restore`, `gpg`, `curl` | Only `backend/scripts/restore-drill.sh` (the backup restore drill) |
| A Google Cloud OAuth client ID | "Continue with Google" on Sign In (`GOOGLE_CLIENT_ID` in `backend/.env`) |
| An SMTP provider (free tier) | Real email in production (`SMTP_*`); locally Mailpit catches it |

## 2. Setup

Follow **README.md › Install and run**, for Windows, macOS or Linux.

The Python scripts are optional. To use the screenshot tool:

```bash
pip install -r scripts/requirements.txt
python -m playwright install chromium
```

## 3. Docker images (pulled automatically by `docker compose up`)

| Image | Version | Purpose |
|---|---|---|
| `postgres` | 16-alpine | The database (`backend/docker-compose.yml`) |
| `axllent/mailpit` | v1.27 | Catches email locally; inbox at http://localhost:8025 |
| `eclipse-temurin` | 25-jdk / 25-jre | Building and running the API image for Render (`backend/Dockerfile`) |

## 4. Backend (Java) packages

Downloaded by `mvnw` from Maven Central. Full list with the reason for each: **`backend/docs/11-java-stack.md`**.

| Package | Version |
|---|---|
| Spring Boot (parent: Web MVC, Validation, JDBC, Flyway, Mail, Test) | 4.1.2 |
| PostgreSQL JDBC driver, Flyway + `flyway-database-postgresql` | managed by Spring Boot |
| `spring-security-crypto` (BCrypt only) | managed by Spring Boot |
| `nimbus-jose-jwt` | 10.10 |
| `springdoc-openapi-starter-webmvc-ui` (Swagger UI at /docs) | 3.1.1 |
| Build: `spring-boot-maven-plugin`, `maven-compiler-plugin`, Spotless 3.10.4 + palantir-java-format 2.102.0, JaCoCo 0.8.15 | — |
| Tools (`exec:java`): `exec-maven-plugin` | resolved by Maven on use |

## 5. Frontend (npm) packages

Installed by `npm install` at the root (npm workspaces). Versions are what `package-lock.json` resolves.

**Root (tooling)**

| Package | Version | Purpose |
|---|---|---|
| `vitest` | 3.2.7 | Unit tests for `packages/api` (`npm test`) |
| `openapi-typescript` | 7.13.0 | Generates `packages/api/src/openapi.gen.ts` from the API (`npm run api:types`) |

**`apps/web` — web portal**

| Package | Version | Purpose |
|---|---|---|
| `react`, `react-dom` | 19.2.3 | UI |
| `react-router-dom` | 6.30.6 | Routing |
| `@supabase/supabase-js` | 2.118.0 | Sign-in in production |
| `qrcode` | 1.5.4 | QR codes (the iPhone Shortcut key), MIT licence |
| `@fontsource/inter`, `@fontsource/roboto-mono` | 5.3.0 | Fonts, served locally |
| `vite` | 5.4.21 | Dev server and build |
| `@vitejs/plugin-react` | 4.7.0 | React support for Vite |
| `vite-plugin-svgr` | 4.5.0 | Icons as React components (`?react` imports) |
| `typescript` | 5.9.3 | Type checking |
| `@types/react`, `@types/react-dom` | 19.2.18, 19.2.7 | React types |
| `@types/qrcode` | 1.5.6 | qrcode types |

**`packages/*`** — `@vitallink/api` (client, view-models, form rules), `@vitallink/tokens` (design tokens),
`@vitallink/assets` (icons): workspace packages with no outside dependencies.

## 6. Python packages (`scripts/`, optional)

| Package | Needed by |
|---|---|
| `playwright` | `scripts/shoot.py` (screenshots of every screen) |

`scripts/write-icons.py` uses only the Python standard library.
On Windows, set `PYTHONUTF8=1` before running Python scripts.

## 7. Accounts

None are needed to run locally. The live app uses free plans of **Supabase** (database, sign-in, files), **Render**
(API), **Vercel** (website) and **Brevo** (email), plus a free **Google Cloud** OAuth client for Google sign-in.
