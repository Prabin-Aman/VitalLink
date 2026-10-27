# VitalLink — context for Claude Code

Group capstone (Kathmandu University, Group 2: Amrit Gautam, Dinesh Chaudhary, Shreenibash Shrestha,
Sudip Ghorasaini; owner Abhinaash). Consumer health & fitness app for the Dallas area: mobile app + web portal.
Owner prefers concise, direct, actionable answers.

## Hard constraints (never break)
- **Consumers only.** No hospital/clinic/provider interface of any kind.
- **No blood features anywhere** (no blood type, donors, blood banks). Blood *pressure*/glucose/oxygen vitals are fine.
- **Everything free** (free tiers only: Supabase free, Render free, GitHub Actions, Expo).
- Design source of truth is Figma; don't invent UI that isn't in the file without asking.

## Repo layout (npm workspaces)
```
packages/tokens  figma-variables.json → build.mjs → css/tokens.css (web, --vl-* vars, .vl-<style> classes)
                                                  + src/generated.ts (colors.light/dark, space, radius, text, elevation)
packages/assets  icons/*.svg (stroke="currentColor", one file per shape+size, e.g. health-18), emergency-qr.json
packages/api     types.ts, client.ts (VitalLinkClient; no baseUrl = mock mode), mock.ts + mock-data/*.ts (Figma
                 sample data), format.ts, screens.ts (dashboard/activity/privacy) and views/*.ts (fitness, care,
                 records, health) → loadX(api) view-models with display strings, web + mobile variants
apps/web         React 19 + Vite 5 + React Router 6 + CSS modules + vite-plugin-svgr (?react imports)
                 components/kit.tsx = shared page parts (PageHeader, PillTabs, StatCard, Panel, BarRow, Split…)
apps/mobile      Expo SDK 57 + Expo Router + react-native-svg + react-native-svg-transformer + @expo-google-fonts
                 src/components/kit.tsx = shared parts (Screen, ChipRow, StatGrid, Section, Row, MenuRow, Pill…)
scripts/         write-icons.py (icons + both Icon registries), icon-masters.json, shoot.py, spa_serve.py
backend/         Java 25 + Spring Boot 4.1 API (Maven wrapper), docs/ 01–12; see "Backend" below
```

## Commands
```
npm install                 # from repo root only (never inside apps/*)
npm run web                 # :5173  every sidebar route, /health/*, /records/*, /welcome, /create-account
npm run mobile              # Expo; `w` for browser
npm run web:build && npm run typecheck
npm run tokens              # after editing figma-variables.json
cd backend && ./mvnw verify                       # backend: all tests (needs Postgres) + coverage
cd backend && ./mvnw spring-boot:run              # API on :8000 (/docs = Swagger UI)
cd apps/mobile && npx expo export --platform web --output-dir dist   # mobile web build for screenshots
python3 scripts/shoot.py <base-url> <outdir> <w> <h> /path:name ...   # 1440x1024 web, 390x844 mobile
```
`npx expo install` needs network to api.expo.dev; if blocked use `EXPO_OFFLINE=1`.
Env: `VITE_API_URL` (web), `EXPO_PUBLIC_API_URL` (mobile). Unset = mock data. Launch configs in
`.claude/launch.json`: `api` (the packaged jar on :8000), `web-live` (web portal against it).

## Conventions
- No raw colours/sizes/fonts in components: web uses `var(--vl-*)` / `tokenVar(token)` / `.vl-<text-style>`;
  mobile uses `c[token]` and `t("textStyle", colorToken)` from `apps/mobile/src/theme.ts`
  (maps weights to Inter_400Regular/500Medium/600SemiBold/700Bold, RobotoMono_400Regular).
- Icons: never redraw. `scripts/icon-masters.json` holds every `Icon / *` component's paths (24px) exactly as
  Figma exports them; a size is the same geometry with the stroke widened to stay 1.9px on screen. To use a
  new name/size add it to `USES` in `scripts/write-icons.py` and run it — it writes the SVGs and regenerates
  `apps/*/src/components/icons.gen.ts`. New Figma icon → re-export the masters first. Python on Windows: set
  `PYTHONUTF8=1` (the default codec is cp1252) and write files with `newline="\n"`.
- Screens do no formatting; add fields to view-models in `packages/api/src/views/*.ts` (or screens.ts).
  Every client call is live except `conversations()` (D1), which stays on `client.pending()` + `need()`:
  mock mode serves sample data, live mode says "isn't available yet" instead of fake numbers. Live
  My Health tabs leave out readings nothing measures and send null blocks; view-models show "—".
- Tabs/chips with no Figma frame show a "… is in a later batch" placeholder rather than invented UI.
- Theme: `apps/web/src/theme.ts` sets `<html data-theme>` (Settings › Appearance); `.vl-light/.vl-dark`
  pin a subtree to one mode (theme previews).
- Charts are drawn from data with tokens (not exported images).
- Verify every screen: render with Playwright, compare against the Figma screenshot, fix spacing/type.
  Read text styles from Figma (`textStyleId` names like Heading/H5, Body/Small Strong) rather than guessing.

## Figma
File key `JuPJkw5r1aAtdgRQk2UuaA`. Load the figma-design-to-code skill before `get_design_context`
and figma-use before `use_figma`. `get_metadata` without node lists only the cover page; use `use_figma` to list pages.
Figma asset URLs (www.figma.com) may be blocked by the proxy → use `get_screenshot` with base64, or plugin export.

Every screen file starts with a comment naming its Figma frame (e.g. `// Workouts — Desktop 1440 (Figma 129:1142)`).
Pages: 4:3 mobile auth · 4:4 mobile health · 4:5 mobile fitness · 4:6 mobile records · 149:2 mobile care ·
4:8 mobile profile · 4:10 public site · 4:11 web auth · 4:12 portal (care + My Health tabs) · 4:13 dashboard ·
4:14 web fitness · 4:15 web records. Icon components live on 2:298.

Web shell: sidebar 260, top bar 64, content px32 pt28 gap24. Mobile: header px16 py14, tab bar 72
(Home, Health, Train, Records, Profile). Health and Train are stacks (chips switch screens); care screens
(Medications … Settings, Privacy) live under Profile with a back button; Nutrition/Goals live under Train.

## Status
Done (Oct 2026, every frame in the Figma file, compared against Figma screenshots): web portal (all 16 sidebar
items, 9 My Health tabs, Lab Results, Document Viewer, Create Account, public homepage at /welcome) and mobile
(Welcome, Create Account, Health Overview/Activity/Digital, Workouts, Nutrition, Goals, Records, Profile and
the 10 Profile screens). Mock data drives everything. Sign-in/sign-up are stubs (real auth = Supabase Auth).
Backend (Java) is complete through phase 8 and every screen except Messages reads live data (23 Oct 2026).
**Next:** owner decides D1–D16 → create the group Supabase/Render accounts → Supabase sign-in in the
apps → phase 9 (phone sync on devices) → phase 10 (rate limits, load tests, types from OpenAPI).

## Open issues flagged to the owner (awaiting decisions)
- Desktop Privacy shows "Hospitals with access" + hospital rows — conflicts with consumers-only. Built as
  designed; `grantee_kind: "hospital"` is design-only (API never returns it). Likely to remove.
- "HIPAA-aligned" sign-in copy is misleading for a non-HIPAA consumer app.
- Sign-in brand panel body text (textSecondary on inverse) has low contrast.
- Figma leftovers from blood era: variables text/blood, border/blood, bg/blood-subtle, urgency/emergency-bg,
  donor-eligible, donor-deferred (mapped to neutral tokens in code); component descriptions mention blood.
- Design data inconsistencies, code computes from data: steps total 60,912 / avg 8,702 (Figma 58,904 / 8,415);
  goal days 2/7 (Figma 3/7); dashboard "daily goal 8,000" vs 10,000 used; mobile "Active shares 2 · links & family"
  → "7 · doctors & family" (share links were removed in backend review).
- Desktop steps chart: Figma's in-plot "Goal 10,000" label collides with Monday's value → moved to a key below.
- BP chart uses a true 40–170 mmHg scale, so lines are flatter than the hand-drawn Figma ones.
- (Fixed) mobile 18px bike/run icons are now generated from the 24px masters, not scaled 20px exports.
- Public homepage (99:2): left out "For Providers" nav, "For organisations" footer column, "HIPAA-aligned"
  security card, and the whole testimonials section (two quotes are blood-bank leftovers — "four units of
  O-negative", "inventory… log units" — and all three are unverified endorsements). Duplicate "Training Plans"
  footer link shown once; only the first FAQ has answer copy, the rest are plain rows.
- More blood-era Figma layer names (visible content is fine): "North Texas Blood Center", "Avatar/Tone=Blood",
  "Notif / Blood match found near you", "Stat / Blood group", "Cat / Blood", "Article / Who can donate…",
  "Article / Iron-rich foods…", "Article / Controlling heavy bleeding", "Step / Blood Information",
  "Find Blood Now" (CTA button).
- Document Viewer "Sharing permissions" showed "Link · expires" and "Anyone with the link"; share links were
  removed, so it shows the named share with its expiry and drops the anonymous-link row.
- Mobile has no entry point for Nutrition/Goals (frames show the Train tab) → added rows to Profile ›
  "Health & records". Mobile Sleep/Heart/Body chips have no frames → placeholder. Mobile record rows have
  chevrons but no detail frame.
- Values computed from mock data differ from some Figma copy: Goals "across 6 areas" (Figma 4) and the
  consistency caption (26 of 35, Sundays weakest; Figma 27, Thursdays); Appointments "3 upcoming · next visit
  Tue 15 Sep" (Figma "2 upcoming · tomorrow"; 15 Sep 2026 is a Tuesday and today is Sun 13 Sep); Nutrition
  date "Sunday, 13 September" (Figma Saturday 19); Devices last sync "3 minutes" (Figma 12); Mental "6 of 7
  days logged" (Figma 5); notifications show the same 7 items on web and mobile (the frames showed 6 and 5).
- Copy written for data (no Figma text): the Family note uses "their own privacy centre" (Figma "her"),
  Create Account "Entries don't match yet." helper, mobile Emergency "Show QR" reveals the web QR card.
- Workouts/Records use km (as in Figma) while Activity uses miles — units should follow Settings › Units.

## Backend (Java; phases 1–8 built)
`backend/` is one **Spring Boot 4.1 / Java 25** service (modular monolith), built with the Maven wrapper.
It was first written in Python (FastAPI) through phase 5b and ported on 23 Oct 2026 at the owner's request
(same schema, paths and JSON); the Python code is gone. Docs: `backend/README.md`, `backend/docs/01–12`
(11 = every library and version, 12 = test report). One README per package in `src/main/java/com/vitallink/api`.
- Packages: `core` (settings, db, security, audit, consent, storage, userdata, web), `account`, `health`
  (incl. My Health overview + 7 tabs), `training`, `nutrition`, `sharing` (shares, access log, privacy,
  family, all `/users/{id}` views), `care`, `goals`, `records`, `notifications`, `library`, `jobs`, `ops`, `tools`.
- Plain SQL with Spring `JdbcClient` + HikariCP; Flyway `V1–V10` in `src/main/resources/db/migration`
  (V1–V6 = the old Alembic 0001–0006); 33 tables in the private `vitallink` schema; RLS on everywhere.
- One transaction per `/v1` request (`RequestTransactionFilter`, rolls back on any error status).
  `AuditLog` writes in its own transaction. Consent: pure `Consent.decide()` + `ConsentGate`; every
  `/users/{id}` handler takes a `ConsentGate` (guardrail test); shared routes are GET-only.
- Auth: Nimbus JOSE; production verifies Supabase JWKS, local/test HS256 dev tokens. Files: `FileStorage`
  = Supabase Storage in production, signed local files (`/local-storage/{token}`) in dev/test.
- Each module implements `UserData` (export queries + delete hook) for `GET /me/export` and `DELETE /me`.
- Tests: 217 JUnit + MockMvc tests on real Postgres (`<db>_test`), 80.4% line coverage; `SystemCheck`
  (44-step black-box test over HTTP) runs in CI against the packaged jar. `./mvnw spotless:apply` formats.
- Tools (local only): `DevToken <email>`, `DemoSeed` (demo@example.com, 90 days in every module),
  `SystemCheck <url>` — run with `./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.<Tool>`.
- Local: Postgres via `docker compose up` in WSL; `.env` `DATABASE_URL` may be `postgresql://…` or JDBC
  (use 127.0.0.1, not localhost). Live web: `VITE_API_URL=http://localhost:8000` + dev token in
  `sessionStorage["vl.token"]` or `VITE_DEV_TOKEN` in `apps/web/.env.local` (any tab). Nightly: `POST /internal/jobs/nightly` with `X-Job-Secret`
  (`.github/workflows/nightly.yml`).
- Decisions applied by default (owner hasn't decided): D2 (QR on the phone), D4 (doctor/family/other),
  D5 (user confirms appointments), D6 (labs typed in), D9 (no push), D14 (no reviewer names). Messages (D1)
  and dependents (D3) are not built. Compliance: FTC Health Breach Notification Rule (not HIPAA).
- Rate limits: `core/web/RateLimiter` (60 ingest / 600 other per user per hour → 429). `LoadCheck <url>`
  checks the load targets. Workflows: `backend.yml`, `nightly.yml`, `backup.yml`, `deploy-check.yml`.
- Frontend writes: Medications "Mark Taken" (web + mobile), Settings Save/theme, notifications read.
  Mobile live mode takes a dev token from `EXPO_PUBLIC_DEV_TOKEN`.
- Not built yet: stored insights (computed on read),
  goal_progress table (computed on read), phone sync (phase 9). Render/Supabase accounts don't exist yet.
