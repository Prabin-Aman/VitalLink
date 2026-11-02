# What you need to run VitalLink

Everything here is free. The versions are the ones the project is built and tested with (31 Oct 2026); newer
patch versions of the tools work too. Package versions are pinned by `package-lock.json` (npm) and
`backend/pom.xml` (Maven), so installing never needs to pick versions by hand.

## 1. Tools to install

| Tool | Version | Needed for | Install (Windows) |
|---|---|---|---|
| **Node.js** (includes npm) | 20 or newer (built with 24.19, npm 11.17) | Web portal, mobile app, `packages/*`, frontend tests | https://nodejs.org (LTS installer) |
| **Java JDK** | **25** (built with 25.0.4 LTS) | The backend API (Spring Boot) | Eclipse Temurin 25: https://adoptium.net |
| **Docker** with Compose | Docker 29, Compose v5 | PostgreSQL and Mailpit for local runs | Docker Engine inside **WSL Ubuntu** (how this machine runs it), or Docker Desktop |
| **WSL 2 + Ubuntu** | any current | Runs Docker on Windows without Docker Desktop | `wsl --install -d Ubuntu` in an admin PowerShell |
| **Git** | any current | Cloning and committing | https://git-scm.com |
| **Python** | 3.12 or newer (built with 3.14) | Only the helper scripts in `scripts/` (icons, screenshots) | https://python.org |

Not needed: **Maven** (the backend uses the Maven wrapper `mvnw` / `mvnw.cmd`, which downloads Maven 3.9.16
on first use) and a separate **PostgreSQL** install (it runs in Docker).

Optional:

| Tool | Needed for |
|---|---|
| **Expo Go** app on a phone (Android / iOS) | Opening the mobile app on a device (`npm run mobile`) |
| `psql`, `pg_dump`, `pg_restore`, `gpg`, `curl` | Only `backend/scripts/restore-drill.sh` (the backup restore drill) |
| A Google Cloud OAuth client ID | "Continue with Google" on Sign In (`GOOGLE_CLIENT_ID` in `backend/.env`) |
| An SMTP provider (free tier) | Real email in production (`SMTP_*`); locally Mailpit catches it |

## 2. One-time setup

```bash
npm install                      # from the repo root only: installs every npm package below
```
```powershell
copy backend\.env.example backend\.env
```
```powershell
copy apps\web\.env.example apps\web\.env.local
```

Fill in the passwords in `backend\.env`; `apps\web\.env.local` points the portal at the local API.

```bash
pip install -r scripts/requirements.txt     # only for the screenshot script
```
```bash
python -m playwright install chromium       # only for the screenshot script
```

Then follow **README.md › Run** (start Docker, the API, the portal).

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
| `@fontsource/inter`, `@fontsource/roboto-mono` | 5.3.0 | Fonts, served locally |
| `vite` | 5.4.21 | Dev server and build |
| `@vitejs/plugin-react` | 4.7.0 | React support for Vite |
| `vite-plugin-svgr` | 4.5.0 | Icons as React components (`?react` imports) |
| `typescript` | 5.9.3 | Type checking |
| `@types/react`, `@types/react-dom` | 19.2.18, 19.2.7 | React types |

**`apps/mobile` — mobile app (Expo)**

| Package | Version | Purpose |
|---|---|---|
| `expo` | 57.0.26 | Expo SDK 57 |
| `react-native` | 0.86.3 | Native UI |
| `react`, `react-dom` | 19.2.3 | UI (react-dom for the web build) |
| `expo-router` | 57.0.24 | File-based navigation |
| `expo-constants`, `expo-font`, `expo-linking`, `expo-status-bar` | 57.0.x | Expo modules |
| `expo-document-picker` | 57.0.3 | Choosing a file to upload (entry sheets, in progress) |
| `@expo/metro-runtime` | 57.0.16 | Metro bundler runtime (web) |
| `react-native-web` | 0.21.3 | Running the app in a browser |
| `react-native-safe-area-context` | 5.7.0 | Notch / safe areas |
| `react-native-screens` | 4.26.2 | Native screen containers |
| `react-native-svg` | 15.15.4 | Icons and charts |
| `react-native-svg-transformer` | 1.5.3 | SVG files as components |
| `@expo-google-fonts/inter`, `@expo-google-fonts/roboto-mono` | 0.4.2 | Fonts |
| `typescript`, `@types/react` | per `package.json` | Type checking |

**`packages/*`** — `@vitallink/api` (client, view-models, form rules), `@vitallink/tokens` (design tokens),
`@vitallink/assets` (icons): workspace packages with no outside dependencies.

## 6. Python packages (`scripts/`, optional)

| Package | Needed by |
|---|---|
| `playwright` | `scripts/shoot.py` (screenshots of every screen) |

`scripts/write-icons.py` and `scripts/spa_serve.py` use only the Python standard library.
On Windows run Python scripts with `PYTHONUTF8=1` set (see CLAUDE.md).

## 7. Accounts

None are needed to run locally. Deployment needs the group's **Supabase** (free) and **Render** (free)
projects; Google sign-in needs a free **Google Cloud** OAuth client ID. **Sign in with Apple** is not offered
because it needs Apple's paid developer programme.
