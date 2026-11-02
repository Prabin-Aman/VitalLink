# VitalLink: what's built and what's missing (1 Nov 2026)

The web portal and the Java API are feature-complete for everything in the Figma file. Every web screen
reads and writes the live API; every save is a row in PostgreSQL. What's left falls into the groups below,
roughly in the order it unblocks other work.

## 1. Blocked on accounts or owner decisions

| Item | Why it's blocked | Unblocks |
|---|---|---|
| Group **Supabase** and **Render** accounts (D15) | Nobody has created them yet | Real sign-in, deployment, file storage in production, the nightly job and backups |
| **Supabase sign-in** in the apps | Needs the Supabase project. Until then the web portal signs in with local email/password accounts (local and test only) | Production sign-in, email verification, Settings › Account / Security / Language, changing the email, phone app sign-in |
| **Messages** (D1) | Clinician messaging isn't decided (consumers only) | The Messages screen still shows sample threads in mock mode and "isn't available yet" live |
| **Dependent profiles**: "Emma, 9" (D3) | A child without an account is a new concept | Family for children |
| Other open decisions (D2, D4–D14, D16) | Defaults applied, owner sign-off pending | See `backend/docs/10-open-decisions.md` |

## 1b. Sign-in

- Local email/password accounts, Forgot password (email via Mailpit locally) and Sign in with Google work.
  **Google needs a free OAuth client ID** from Google Cloud Console in `GOOGLE_CLIENT_ID` (backend/.env).
- **Sign in with Apple isn't offered**: it needs Apple's paid developer programme ($99/year).
- Real email in production needs an SMTP provider's free tier (`SMTP_*`).

## 2. Web: the API exists, the screen doesn't use it yet

Done on 31 Oct: lab results entry, mood/stress/energy/mindful check-ins, nutrition targets, custom foods,
dose history with undo, record a refill, and the Emergency ID's organ donor / hospital / notes.

Also done on 31 Oct (Figma 221:2114–221:2205): Settings › Security › Change password, Hydration › Today's
entries with Remove, Edit an emergency contact, Nutrition › Scan Barcode (Open Food Facts) and Settings ›
Units (km or miles for distance and pace on Activity and Workouts; weights stay in kg).

Nutrition › Recipes (Figma 222:2104, 222:2330, 222:2417): build a dish from foods with servings, per-serving
macros, Log servings as a meal, edit, delete (`/me/recipes`, V18).

Nothing on web is waiting on an existing API any more.

## 3. Web: needs a design and/or a backend first

| Button / tab | What it needs |
|---|---|
| Settings › Account; 2FA | Supabase sign-in (email change, 2FA) |
| Appointments › Search Doctors | A provider directory: no free data source chosen; may conflict with consumers-only. Suggest removing |

## 4. Mobile (paused: web is the focus)

- Entry sheets (Add Reading … Edit Emergency Info, Figma 198:317): built but **uncommitted** in
  `apps/mobile/src/sheets/`, `components/Sheet.tsx` and the screens that open them; adds `expo-document-picker`.
- No mobile frames: Sleep, Heart, Body chips, record detail; Workouts and Nutrition chips other than the first.
- None of the web's edit/delete, sharing, devices or import features exist on mobile yet.
- **Phase 9 phone sync**: HealthKit / Health Connect need an Expo development build and real devices. The
  web side is ready (`GET /me/sync`, the phone sync panel); `PhoneSim` stands in until then.

## 5. Not verified on real hardware or at real scale

- **Barcode camera** uses the browser's BarcodeDetector (Chrome on Android/macOS); on Windows desktop
  Chrome the digits are typed. The camera path hasn't been tried on a phone.
- **Bluetooth pairing** was tested with a stand-in `navigator.bluetooth`; try a real strap or cuff in Chrome.
- **Apple Health import** was tested with a generated 476-record export; real exports can be hundreds of MB.
- `LoadCheck` and `scripts/restore-drill.sh` have only run locally, not against the real Supabase project.

## 6. Repository housekeeping

- `DESIGN.md` describes Anthropic's website design, not VitalLink: keep as reference or remove?
- `backend/docs/VitalLink_Presentation_Script.docx` is untracked: commit it or keep it out?
- `design-ref/` is an empty folder (git doesn't track it).

## Where things live

```
apps/web/src/screens/   one folder per area: dashboard, health, fitness, records, care, sharing,
                        inbox, library, settings, public (+ ScreenState, Lists.module.css)
apps/web/src/forms/     dialogs: Health, Fitness, Care, Record, Share, Device, Edit
packages/api/src/views/ view-models, one file per area: shared, dashboard, activity, health, fitness,
                        workout-tabs, nutrition-tabs, care, records, privacy, library, search
packages/api/src/       client.ts, types.ts, forms.ts (form rules), bluetooth.ts, format.ts, mock*
backend/                Spring Boot API: see backend/README.md and backend/docs/01–13
```
