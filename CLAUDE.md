# VitalLink — context for Claude Code

Group capstone (The University of Texas at Arlington, Group 2: Amrit Gautam, Dinesh Chaudhary, Shreenibash Shrestha,
Sudip Ghorasaini; owner Abhinaash). Consumer health & fitness web portal for the Dallas area. The Expo mobile app
was deleted at the owner's request (Nov 2026; in git history).
Owner prefers concise, direct, actionable answers. Git: `origin` = https://github.com/zeutonx/VitalLink (the owner's
renamed account, 12 Nov 2026); `prabin` = https://github.com/Prabin-Aman/VitalLink.

## Hard constraints (never break)
- **Consumers only.** No hospital/clinic/provider interface of any kind.
- **No blood features anywhere** (no blood type, donors, blood banks). Blood *pressure*/glucose/oxygen vitals are fine.
- **Everything free** (free tiers only: Supabase free, Render free, Vercel Hobby, GitHub Actions).
- Design source of truth is Figma; don't invent UI that isn't in the file without asking.

## Repo layout (npm workspaces)
```
packages/tokens  figma-variables.json → build.mjs → css/tokens.css (web, --vl-* vars, .vl-<style> classes)
                                                  + src/generated.ts (colors.light/dark, space, radius, text, elevation)
packages/assets  icons/*.svg (stroke="currentColor", one file per shape+size, e.g. health-18), emergency-qr.json
packages/api     types.ts (hand-written; contract.ts checks it against openapi.gen.ts, `npm run api:types`), client.ts (VitalLinkClient; no baseUrl = mock mode), mock.ts + mock-data/*.ts (Figma
                 sample data), format.ts, forms.ts (form rules), bluetooth.ts, views/*.ts one file per area
                 (shared, dashboard, activity, health, fitness, workout-tabs, nutrition-tabs, care, records,
                 privacy, library, search) → loadX(api) view-models with display strings
apps/web         React 19 + Vite 5 + React Router 6 + CSS modules + vite-plugin-svgr (?react imports)
                 components/kit.tsx = shared page parts (PageHeader, PillTabs, StatCard, Panel, BarRow, Split…)
                 screens/<area>/ (dashboard, health, fitness, records, care, sharing, inbox, library, settings,
                 public); forms/ = dialogs. STATUS.md lists what's built and what's missing
scripts/         write-icons.py (icons + the Icon registry), icon-masters.json, shoot.py
backend/         Java 25 + Spring Boot 4.1 API (Maven wrapper), docs/ 01–12; see "Backend" below
```

## Commands
```
npm install                 # from repo root only (never inside apps/*); REQUIREMENTS.md = every tool/package + version
npm run web                 # :5173  every sidebar route, /health/*, /records/*, /welcome, /create-account
npm run web:build && npm run typecheck
npm run tokens              # after editing figma-variables.json
cd backend && ./mvnw verify                       # backend: all tests (needs Postgres) + coverage
cd backend && ./mvnw spring-boot:run              # API on :8000 (/docs = Swagger UI)
python3 scripts/shoot.py <base-url> <outdir> <w> <h> /path:name ...   # 1440x1024
```
Env: `VITE_API_URL`. Unset = mock data. Launch configs in
`.claude/launch.json`: `api` (the packaged jar on :8000), `web-live` (web portal against it).

## Conventions
- No raw colours/sizes/fonts in components: use `var(--vl-*)` / `tokenVar(token)` / `.vl-<text-style>`.
- Icons: never redraw. `scripts/icon-masters.json` holds every `Icon / *` component's paths (24px) exactly as
  Figma exports them; a size is the same geometry with the stroke widened to stay 1.9px on screen. To use a
  new name/size add it to `USES` in `scripts/write-icons.py` and run it — it writes the SVGs and regenerates
  `apps/web/src/components/icons.gen.ts`. New Figma icon → re-export the masters first. Python on Windows: set
  `PYTHONUTF8=1` (the default codec is cp1252) and write files with `newline="\n"`.
- Screens do no formatting; add fields to view-models in `packages/api/src/views/*.ts`.
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
4:14 web fitness · 4:15 web records · 195:2 proposed entry forms. Icon components live on 2:298.

Web shell: sidebar 260, top bar 64, content px32 pt28 gap24. Below 1280px it follows the Tablet 1024 frames
(19 — Responsive Variants, 147:5476): 72px icon sidebar, content px24, Split/Dashboard rails drop under the
main column as 282px cards (`@media (max-width: 1279px)` in each module). Layout checks: `web-mock` launch
config (sample data on :5174), so a sweep of every route doesn't hit the API's rate limit.

## Documentation
README.md (start here), STATUS.md, apps/web/README.md, packages/api/README.md, backend/README.md, backend/docs/README.md.
Keep them current when behaviour changes.

## Status
Done (Oct 2026, every frame in the Figma file, compared against Figma screenshots): web portal (all 13 sidebar
items, 9 My Health tabs, Lab Results, Document Viewer, Create Account, public homepage at /welcome). Mock data drives everything. Sign-in (`apps/web/src/auth.ts`): Supabase Auth when `VITE_SUPABASE_URL` +
`VITE_SUPABASE_PUBLISHABLE_KEY` are set (production, Vercel), else the API's local email/password accounts.
Backend (Java) is complete through phase 8 and every screen except Messages reads live data (23 Oct 2026).
**Next:** owner decides D1–D16 → test sign-in and uploads on the live site → phase 10 leftovers (LoadCheck and
`scripts/restore-drill.sh` against the real project). Phase 9 (phone sync) is cancelled with the mobile app.

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
- Values computed from mock data differ from some Figma copy: Goals "across 6 areas" (Figma 4) and the
  consistency caption (26 of 35, Sundays weakest; Figma 27, Thursdays); Appointments "3 upcoming · next visit
  Tue 15 Sep" (Figma "2 upcoming · tomorrow"; 15 Sep 2026 is a Tuesday and today is Sun 13 Sep); Nutrition
  date "Sunday, 13 September" (Figma Saturday 19); Devices last sync "3 minutes" (Figma 12); Mental "6 of 7
  days logged" (Figma 5); notifications show the same 7 items on web and mobile (the frames showed 6 and 5).
- Copy written for data (no Figma text): the Family note uses "their own privacy centre" (Figma "her"),
  Create Account "Entries don't match yet." helper, mobile Emergency "Show QR" reveals the web QR card.
- (Fixed) Workouts/Activity distances follow Settings › Units (Figma mixed km and miles).

## Backend (Java; phases 1–8 built)
`backend/` is one **Spring Boot 4.1 / Java 25** service (modular monolith), built with the Maven wrapper.
It was first written in Python (FastAPI) through phase 5b and ported on 23 Oct 2026 at the owner's request
(same schema, paths and JSON); the Python code is gone. Docs: `backend/README.md`, `backend/docs/01–12`
(11 = every library and version, 12 = test report). One README per package in `src/main/java/com/vitallink/api`.
- Packages: `core` (settings, db, security, audit, consent, storage, userdata, web), `account`, `health`
  (incl. My Health overview + 7 tabs), `training`, `nutrition`, `sharing` (shares, access log, privacy,
  family, all `/users/{id}` views), `care`, `goals`, `records`, `notifications`, `library`, `jobs`, `ops`, `tools`.
- Plain SQL with Spring `JdbcClient` + HikariCP; Flyway `V1–V20` in `src/main/resources/db/migration`
  (V1–V6 = the old Alembic 0001–0006); 38 tables in the private `vitallink` schema; RLS on everywhere.
- One transaction per `/v1` request (`RequestTransactionFilter`, rolls back on any error status).
  `AuditLog` writes in its own transaction. Consent: pure `Consent.decide()` + `ConsentGate`; every
  `/users/{id}` handler takes a `ConsentGate` (guardrail test); shared routes are GET-only.
- Auth: Nimbus JOSE; production verifies Supabase JWKS, local/test HS256 dev tokens. Files: `FileStorage`
  = Supabase Storage in production, signed local files (`/local-storage/{token}`) in dev/test.
- Each module implements `UserData` (export queries + delete hook) for `GET /me/export` and `DELETE /me`.
- Tests: 286 JUnit + MockMvc tests on real Postgres (`<db>_test`), 80.5% line coverage; `SystemCheck`
  (47-step black-box test over HTTP) runs in CI against the packaged jar. `./mvnw spotless:apply` formats.
- Tools (local only): `DevToken <email>`, `DemoSeed` (demo@example.com, 90 days in every module),
  `PhoneSim <url> [--hours N]` (uploads like the phone app through ingest), `SetPassword <email>` (password from
  `VITALLINK_PASSWORD`), `SystemCheck <url>` — run with `./mvnw -q compile exec:java -Dexec.mainClass=com.vitallink.api.tools.<Tool>`.
- Local: Postgres and Mailpit via `docker compose up` in WSL; `.env` `DATABASE_URL` may be `postgresql://…` or JDBC
  (use 127.0.0.1, not localhost). Live web: `VITE_API_URL=http://localhost:8000` + dev token in
  the signed-in session (`vl.token` in localStorage with Remember me, else sessionStorage) or `VITE_DEV_TOKEN` in
  `apps/web/.env.local` for tools. Sign-in: `POST /v1/auth/sign-up`, `/v1/auth/sign-in` (`account/PasswordAuth`,
  BCrypt, `credentials` table V15, 5 wrong → 15-min lock; local/test only, tokens are dev-token shaped, 12 h;
  production = Supabase). Demo account password: `SetPassword demo@example.com` with `VITALLINK_PASSWORD`.
  Without a session the shell redirects to /sign-in (and on a 401). Forgot password (proposed Figma 216:122,
  216:208, 216:294; routes /forgot-password, /reset-password?token=): `POST /v1/auth/forgot-password` (always
  202; single-use 30-min link, SHA-256 in `password_resets` V16, ≤3/hour) emails through `core/Mailer` (SMTP;
  Mailpit in docker compose, inbox http://localhost:8025), `POST /v1/auth/reset-password` signs in. Google:
  `POST /v1/auth/google` verifies a Google ID token (`account/GoogleSignIn`, Google JWKS, GOOGLE_CLIENT_ID;
  `GET /v1/auth/config` tells the page) and counts as email confirmation; the page renders Google's own button
  when configured. Apple isn't offered (paid developer programme), and its button was removed at the owner's request (Figma 24:2 still shows it). Nightly: `POST /internal/jobs/nightly` with `X-Job-Secret`
  (`.github/workflows/nightly.yml`).
- Decisions applied by default (owner hasn't decided): D2 (QR on the phone), D4 (doctor/family/other),
  D5 (user confirms appointments), D6 (labs typed in), D9 (no push), D14 (no reviewer names). Messages (D1)
  and dependents (D3) are not built. Compliance: FTC Health Breach Notification Rule (not HIPAA).
- Stress test (1 to 2 Nov 2026, docs/12 §12.5): `RequestTransactionFilter` admits at most pool size minus a
  fifth at once (an audit entry needs a second connection; else the pool deadlocks), queues the rest 15 s,
  then 503 `busy`; `ErrorHandler` maps bad query encoding and NUL/out-of-range input to 422 and unique
  races to 409. Lists keyed by user-entered names use the id or position.
- Production setup (2 Nov 2026): Supabase project `gfjiaihdnzyvffonyzar` (us-east-1; migrations V1–V20 applied,
  private `records` bucket; user tokens ES256 via JWKS); web on Vercel `https://vital-link-flax.vercel.app`
  (`vercel.json` rewrites every path to index.html); API on Render `https://vitallink-9p4d.onrender.com`; email through Brevo. Supabase sign-up with email
  confirmation keeps the optional profile details in localStorage (`vl.signup.pending`) until the emailed link
  signs the person in, then PATCHes /me/account. "Signed in recently" (delete account) uses the token's `amr`
  timestamp, since Supabase refreshes `iat` hourly. Launch config `web-supabase` (:5175) runs the portal in Supabase mode.
  Google sign-in: OAuth client ID `775096923930-s5ogureo5mm6n822lqft9be091istahi.apps.googleusercontent.com`
  (public; goes in Supabase › Providers › Google with the client secret, which is never kept in this repo).
- Batch of 6 Nov 2026 (owner's request; proposed Figma page 195:2): Connected Devices and Privacy left the sidebar and
  the profile menu and open inside Settings (`SettingsFrame` keeps its section menu on screen; no Figma frame for that
  layout; other Figma frames' sidebars still show both).
  Settings › Account (232:2247: email and Change email 232:2637, sign-in methods, member since, Sign out everywhere).
  Security › Two-step verification (232:2446, set up 232:2670, off 232:2705, sign-in code step 232:2789), Supabase
  TOTP. The API refuses a password-only (aal1) token with 403 `mfa_required` once the account has a verified factor
  (`WebConfig.requireSecondFactor`, `IdentityAdmin.hasSecondFactor`, kept 60 s, fails open) and the shell sends that
  to the code step. These need the hosted sign-in, so local accounts see a line saying so. Appointments › Search
  Doctors (232:2733): `GET /me/doctors/search`, `care/DoctorSearch`, the public NPI Registry (free, no key, only the
  search words are sent, Texas only, city = practice location, everyday words mapped to registry terms); Add Visit
  opens Book Appointment filled in. The route avoids the guardrail's banned word "provider" and is a read-only
  directory lookup. Two-step and Change email were checked against a fake Supabase in the browser
  (`web-supabase-mock` launch config), not a real project. Google sign-ups take their name from `given_name`/
  `family_name` or `full_name`. The Apple button is gone.
- Google Health (12 Nov 2026, owner chose it over Withings/Polar/Oura): Fitbit and Pixel Watch through the Google
  Health API (the Fitbit Web API shut down 2 Nov 2026). `health/GoogleHealth`, `ConnectionsController`
  (`/me/connections/google-health/start|finish|sync`, DELETE), V21 `device_connections` (refresh token sealed by
  `core/security/Sealer`, CONNECTION_KEY; local/test derive from DEV_JWT_SECRET), source `google_health`, nightly
  sync. Web: Add a device › "Fitbit or Pixel Watch" (no Figma frame), callback page `/devices/google/callback`.
  Needs GOOGLE_HEALTH_CLIENT_SECRET + CONNECTION_KEY on Render, the Google Health API enabled and the callback URL
  added to the OAuth client. Checked against a fake Google in `GoogleHealthTest`, not the real API.
- Android helper (12 Nov 2026, owner's request): `android/` "VitalLink Sync", Java (one Kotlin file,
  `HealthConnectBridge.kt`, because the Health Connect client is Kotlin-only), Health Connect → 5-minute
  buckets (`Buckets.kt` mirrors the server's import rules and catalogue ranges) → `POST /v1/phone/ingest`. Linking:
  Connected Devices › Add › Android phone shows a code (`POST /me/phones/code`, 10 min, one use) → the app calls
  `POST /v1/phone/link` and gets an upload-only token (`Authorization: Phone <token>`, `PhoneCaller`, V22
  `phone_links`, hashes only). The phone's device card is named by the link id, so Disconnect unlinks it. No Android
  SDK on this machine: `.github/workflows/android.yml` runs the unit tests and builds the debug APK (artifact).
- iPhone Shortcut (12 Nov 2026, owner chose it over a native iOS app): Connected Devices › Add › iPhone makes an
  upload-only token (`POST /me/phones/shortcut`) and lists the Shortcut steps (`IphoneDialog`, `SHORTCUT_TYPES`);
  the Shortcut posts `start|metric|value|end|unit|source` lines to `POST /v1/phone/import` (stored as `healthkit`,
  last 36 h of a 48 h window). Not tried on a real iPhone; the Shortcut itself has to be built by hand on the phone.
- Apple Health (Nov 2026): HealthKit has no web API and no native app exists now, so data arrives as the Health
  app's export.zip through Connected Devices › Add › Import (`POST /me/health/import`, exempt from the 2 MB body limit,
  up to 300 MB, streamed; JDK entity caps are lifted because every record's `device` attribute is full of `&lt;` and
  the DTD is off). The Phone app dialog and phone sync panel are gone from the web; `GET /me/sync`, ingest and
  `PhoneSim` remain (Bluetooth uses ingest). Readings take a lot of room against Supabase's 500 MB free database.
- Setup for every sign-up (Nov 2026): `profiles.onboarded_at` (V20; existing accounts with any details count as done),
  `onboarded` on `/me`, `POST /me/onboarding/complete` (Finish or Save and finish later). The shell sends anyone not
  onboarded to /create-account. Someone who arrives without having filled in the form in this browser (Google)
  gets step 1 without email or password (proposed Figma 235:15), name pre-filled, Terms required, then steps 2-8.
- Rate limits: `core/web/RateLimiter` (60 ingest / 3,000 other per user per hour → 429). `LoadCheck <url>`
  checks the load targets. Workflows: `backend.yml` (also fails if
  `openapi.gen.ts` is stale), `frontend.yml`, `nightly.yml`, `backup.yml`, `deploy-check.yml`.
- Frontend writes: Mark Taken, Settings Save/theme, notifications read, appointment Cancel
  and Directions, device Disconnect, Document Viewer Download, Export Data, Goal History, and the nine web
  entry forms (Add Reading, Log Food, Log Workout, New Goal, Add Medication, Book Appointment, Reschedule,
  Upload Document, Edit Emergency Info) designed on Figma page 195:2 "15 — Proposed · Entry forms" (owner review pending). Rules/bodies in `packages/api/src/forms.ts`, parts in
  `apps/web/src/components/Form.tsx`, dialogs in `apps/web/src/forms/`; a save calls `notifyChanged()` so
  every screen on view reloads.
- Web tabs/sections designed on 195:2 "Web · Proposed tabs and sections" and built: Workouts › History,
  Training Plans, Exercise Library, Personal Records (`GET /me/training/records`); Nutrition › Week, Meals
  (remove a meal), Hydration (add water); Settings › Data Export, Delete Account (`DELETE /me`). View-models
  in `packages/api/src/views/workout-tabs.ts` and `nutrition-tabs.ts`, screens in `fitness/WorkoutTabs.tsx`,
  `fitness/NutritionTabs.tsx`, `settings/SettingsSections.tsx`. Still placeholders:
  none on web. `npm test` = Vitest for packages/api.
- Sharing UI (Figma 206:1452 Share your data, 206:1516 Manage access): Lab Results "Share with Doctor" and
  Document Viewer "Share" (preset to records), Privacy cards "Manage" (doctors/family list with Stop
  sharing; emergency/devices cards link to their pages). Lab Results tabs are the user's own panels.
- Top bar (Figma 207:1493, 207:1541): search over pages, records + the tests in lab panels, medications,
  appointments and library (`views/search.ts`, index built on first focus); profile menu with sign out
  (ends the session). The shell loads only `/me` (`loadShell`).
- Also working on web: dashboard D/W/M/6M/Y chart ranges (`loadStepsRange`, `loadBloodPressureRange`),
  Health Library article reader `/library/:slug` (Figma 208:3270; `parseArticle` renders the Markdown
  subset as text-only elements), Lab Results Export (CSV with BOM), Emergency Download PDF / Print Card
  (browser print; `@media print` hides the shell and buttons).
- Records › Revoke (records overview shares carry the share `id`), Family › Manage Access / + Add Family
  Member (share dialogs preset to family; the note explains they see you, not the reverse), Workouts › Start
  Session (Log Workout prefilled from the next planned session), Document Viewer › Add a note (Figma
  209:1575; PATCH /me/records/{id} note ≤200; the record's note wins over the lab panel's).
  Privacy › access history Details (Figma 211:1577 Access details: the row's facts and the share it went
  through, with Stop sharing while live; access-log rows carry `share_id`/`share_active`) and Connected
  Devices › Permissions (211:1647: what the device sends, Disconnect). `GET /me/sync` still exists on the API, with no screen. Add a device (213:1667) offers two ways:
  Import health data (213:1701: `POST /me/health/import`, Apple export.zip/xml or CSV `start,metric,value`,
  `backend …/health/HealthImport.java`; Apple data stored as `healthkit` so it merges with sync) and Pair a
  Bluetooth device (213:1754: Web Bluetooth in Chrome/Edge, standard GATT profiles decoded in
  `packages/api/src/bluetooth.ts`, saved through ingest with `platform: "bluetooth"`). Dialogs in
  `apps/web/src/forms/DeviceForms.tsx`. Bluetooth was checked with a stand-in `navigator.bluetooth`, not a real
  device. Edit and delete (Figma 214:2165 Edit Medication — Stop/Restart, Delete; 214:2210 Edit Appointment;
  214:2245 Edit Goal — target, end, Active/Paused, Delete; 214:2324 Edit record details — Delete Record;
  214:2058 Daily targets → PATCH /me/targets) in `apps/web/src/forms/EditForms.tsx`; opened from the row title
  (`EditTitle` in kit: medications, goals), an Edit button (appointment cards, records rows), Document Viewer
  › Edit Details and Goals › Daily Targets; Workouts › History rows have Delete. Rules in forms.ts
  (`checkMedicationEdit`, `checkGoalEdit`, `checkTargets`). Every save is a PATCH/DELETE the API writes to
  Postgres; mock mode only checks. Settings › Change photo / Remove (`POST /me/avatar/upload` → PUT →
  `/me/avatar/confirm`, JPEG/PNG/WebP ≤ 2 MB, `profiles.avatar_key`, V14; `avatar_url` on /me and /me/account,
  shown in the top bar); Profile Save refreshes the top bar, Language is a select, Height parses ft/in or cm,
  Email is read-only. Exercise Library cards open Log exercise (Figma 215:1843: sets of reps/weight/hold time,
  time taken, effort → POST /me/workouts with `sets`). More dialogs (Figma page 15, `apps/web/src/forms/MoreForms.tsx`): Medications row History →
  Dose history with Undo (218:1900, DELETE dose) and Request Refill → Record a refill (218:1934, PATCH
  refills_left/refill_due_on); Nutrition › Targets (218:1966, PATCH /me/nutrition/targets); Log Food "Add it as a
  new food" → Add a food (218:2030, POST /me/foods, opened in a portal; FormDialog stops submit bubbling);
  Lab Results › Add Results and Records › Import Records → Add lab results (218:2098, POST /me/labs); Edit
  Emergency Info has organ donor, preferred hospital and notes (218:2201). Add Reading also logs mood, stress,
  energy (1–5) and mindful minutes (split into 5-minute buckets). `npm run web` uses the API (`VITE_API_URL` in apps/web/.env.local); in mock
  mode a save says plainly that nothing was saved.
  Batch of 31 Oct (Figma 221:2114 Change password, 221:2145 Scan barcode, 221:2168 Edit emergency contact,
  221:2205 Hydration today): Settings › Security → `POST /v1/auth/change-password` (422 wrong_password, not
  401, so the web doesn't sign out; 409 no_password for Google-only accounts); Hydration › Today lists
  `GET /me/hydration` (entries `{id, ml, at}`) with Remove; Edit Emergency Info rows have Edit (PATCH the
  contact); Nutrition › Scan Barcode → `GET /v1/foods/barcode/{code}` (`nutrition/OpenFoodFacts`, foods.barcode
  V17, a found food is saved as the user's and opens Log Food preset); `/me` carries `units` and the Settings
  Units select drives `distance()`/`pace()` in format.ts (Activity, Workouts, History). Nutrition › Recipes
  (Figma 222:2104 tab, 222:2330 New/Edit recipe, 222:2417 Log a recipe): `nutrition/RecipeService`, V18
  `recipes` + `recipe_items` (foods with grams, 1–20 servings; per-serving macros computed on read; each item
  carries its full food), `POST /me/recipes/{id}/log` writes an ordinary meal (macros copied, so edits never
  change past meals). Web: `forms/RecipeForms.tsx`, `RecipesTab` in NutritionTabs.
  Create Account steps 2–8 (proposed Figma 226:2235–226:3348; `screens/public/Onboarding.tsx`): after step 1
  creates the account, Personal Information (sex, units, time zone), Health Profile (daily targets), Body
  Metrics (weight, waist, body fat as readings), Medical Information (conditions/allergies), Emergency Contact
  (+ preferred hospital), Privacy (read-only), Finish (summary). Each saves through the ordinary API and can be
  skipped; Back then Continue never adds the same rows twice.
- Copy rules (owner, 1 Nov 2026): no em dashes in anything a user sees (empty values show "N/A", goal titles
  are "Sleep: 7.5 hours a day"); English only (Spanish removed; V19 limits `profiles.language` to en-US, and
  the language choice is gone from Settings and sign-up). The homepage's navy bands are pinned `.vl-light` and
  their grey text `.vl-dark`, so dark mode doesn't turn them white.
- Not built: stored insights (computed on read), goal_progress table (computed on read).
