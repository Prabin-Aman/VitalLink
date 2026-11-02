# VitalLink app (web + mobile)

Built from the Figma file `JuPJkw5r1aAtdgRQk2UuaA`. One npm-workspaces repo; both apps render the
same view-models and the same design tokens.

```
packages/
  tokens/   figma-variables.json → build.mjs → css/tokens.css (web) + src/generated.ts (both)
  assets/   icons exported from Figma (SVG, stroke = currentColor)
  api/      typed client for the API, mock mode, form rules (forms.ts), view-models (views/, one file per area)
apps/
  web/      React 19 · Vite · React Router · CSS modules       (desktop portal, 1440 frames)
            src/screens/<area>/ (dashboard, health, fitness, records, care, sharing, inbox, library,
            settings, public) · src/forms/ (dialogs) · src/components/ (shell, kit, charts, form parts)
  mobile/   Expo SDK 57 · Expo Router · react-native-svg            (390 frames)
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
npm run mobile       # Expo Go on a phone, or press w for the browser
npm run typecheck
npm test             # frontend unit tests (Vitest)
npm run tokens       # after editing packages/tokens/figma-variables.json
python scripts/write-icons.py   # after adding an icon size to USES (regenerates both Icon registries)
```

With no API URL (no `apps/web/.env.local`) both apps run on **mock data** (the sample values in the Figma frames).
To use the backend set `VITE_API_URL` (web) / `EXPO_PUBLIC_API_URL` (mobile). Screens whose endpoint
doesn't exist yet say so instead of showing sample numbers.

## Screens

Every frame in the Figma file is built; each screen file names its frame in a header comment.

| Area | Web | Mobile |
|---|---|---|
| Public & auth | Homepage `/welcome`, Sign in, Create account | Welcome, Sign in, Create account |
| Dashboard | Dashboard `/` | Home tab |
| My Health | Overview, Vitals, Activity, Sleep, Heart, Respiratory, Body Composition, Mental Wellbeing, Digital Wellbeing (`/health/*`) | Overview, Activity, Digital (Health tab; Sleep/Heart/Body have no mobile frame yet) |
| Fitness | Workouts, Nutrition, Goals | Train tab: Workouts, Nutrition, Goals |
| Records | Medical Records, Lab Results, Document Viewer (`/records/*`) | Records tab |
| Care | Medications, Appointments, Health Library, Family, Messages, Notifications, Emergency Medical ID, Connected Devices, Privacy, Settings | Profile tab → the same screens |

Tabs and sub-sections that have no frame yet show "… is in a later batch".

What's built and what's still missing: [STATUS.md](STATUS.md).

## Rules

- No raw colours, sizes or fonts in components — everything comes from `@vitallink/tokens`.
- Icons are never redrawn: sizes are generated from the Figma icon components (`scripts/icon-masters.json`).
- Screens do no formatting — `packages/api` view-models produce display strings.
- Sign-in and sign-up are stubs until the group Supabase project exists; locally the API accepts dev tokens
  (`backend/README.md`).
