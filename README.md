# VitalLink web portal

Built from the Figma file `JuPJkw5r1aAtdgRQk2UuaA`. One npm-workspaces repo: the web portal and the
packages it uses, plus the Java API. The mobile app was removed in November 2026 (it's in git history).

```
packages/
  tokens/   figma-variables.json → build.mjs → css/tokens.css + src/generated.ts
  assets/   icons exported from Figma (SVG, stroke = currentColor)
  api/      typed client for the API, mock mode, form rules (forms.ts), view-models (views/, one file per area)
apps/
  web/      React 19 · Vite · React Router · CSS modules       (desktop portal, 1440 frames)
            src/screens/<area>/ (dashboard, health, fitness, records, care, sharing, inbox, library,
            settings, public) · src/forms/ (dialogs) · src/components/ (shell, kit, charts, form parts)
backend/    Spring Boot 4.1 / Java 25 API on PostgreSQL (Flyway, RLS) — start at backend/README.md
```

## Requirements

Node.js 20+, Java JDK 25, Docker (inside WSL Ubuntu on this machine) and Git; Python only for the helper
scripts. Maven and PostgreSQL don't need installing (the Maven wrapper and Docker provide them).
**[REQUIREMENTS.md](REQUIREMENTS.md)** lists every tool and package with its version and how to install it.

## Run

One-time setup, from the repo root:

```bash
npm install
```
```powershell
copy backend\.env.example backend\.env
```
```powershell
copy apps\web\.env.example apps\web\.env.local
```

Set the passwords in `backend\.env`. Then, each time, in three terminals:

1. Database and mail catcher (keep this window open; the Mailpit inbox is http://localhost:8025):

```powershell
wsl -d Ubuntu -- bash -lc "cd /mnt/c/Users/abhin/Documents/vitallink-app/backend && docker compose up"
```

2. The API on http://localhost:8000 (Swagger UI at /docs), from the `backend` folder:

```powershell
.\mvnw.cmd spring-boot:run
```

3. The web portal on http://localhost:5173, from the repo root:

```powershell
npm run web
```

Create an account on the Sign In page (or give the seeded demo account a password with the `SetPassword` tool,
see `backend/src/main/java/com/vitallink/api/tools/README.md`).

Other commands:

```bash
npm run typecheck
npm test             # frontend unit tests (Vitest)
npm run tokens       # after editing packages/tokens/figma-variables.json
python scripts/write-icons.py   # after adding an icon size to USES (regenerates the Icon registry)
```

With no API URL (no `apps/web/.env.local`) the portal runs on **mock data** (the sample values in the Figma frames).
To use the backend set `VITE_API_URL`. Screens whose endpoint
doesn't exist yet say so instead of showing sample numbers.

## Screens

Every frame in the Figma file is built; each screen file names its frame in a header comment.

| Area | Screens |
|---|---|
| Public & auth | Homepage `/welcome`, Sign in, Create account |
| Dashboard | Dashboard `/` |
| My Health | Overview, Vitals, Activity, Sleep, Heart, Respiratory, Body Composition, Mental Wellbeing, Digital Wellbeing (`/health/*`) |
| Fitness | Workouts, Nutrition, Goals |
| Records | Medical Records, Lab Results, Document Viewer (`/records/*`) |
| Care | Medications, Appointments, Health Library, Family, Messages, Notifications, Emergency Medical ID, Connected Devices, Privacy, Settings |

What's built and what's still missing: [STATUS.md](STATUS.md).

## Rules

- No raw colours, sizes or fonts in components — everything comes from `@vitallink/tokens`.
- Icons are never redrawn: sizes are generated from the Figma icon components (`scripts/icon-masters.json`).
- Screens do no formatting — `packages/api` view-models produce display strings.
- Sign-in is Supabase Auth in production and the API's own email/password accounts locally (`apps/web/src/auth.ts`).
