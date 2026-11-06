# 10. Open decisions

> **Note (Nov 2026):** the mobile app was removed and phone sync cancelled. Parts below that mention them are historical. See [the index](README.md).

Each item says what the conflict is, what's recommended, and which phase it blocks. The design docs
already assume the recommendation, so if the owner chooses differently, the noted sections change.

## Conflicts with the hard constraints

| # | Question | Why it's a problem | Recommendation | Blocks |
|---|---|---|---|---|
| **D1** | Messages: the Figma frames show threads with clinicians ("Dr. Laura Bennett · Cardiology"). | Messages written by clinicians are **inbound data from providers**. That breaks "consumers only" and "nothing inbound", and it makes VitalLink a care-communication channel with clinical liability. | **Leave Messages out of v1.** The screens keep showing "isn't available yet". If it's wanted later, messages only between two consumer accounts with an active share, text only, no attachments, and never presented as clinical advice. | Nothing (no module until decided) |
| **D2** | Emergency medical ID: a QR code that a first responder scans. | A scan without signing in means an **anonymous public link**. The reviewed design removed those (they leak through forwarding and can't be attributed). | **The QR encodes the emergency text itself**, generated on the phone (name, allergies, conditions, meds, contacts). Scanning shows the text with no server call. "Show on lock screen" stays a device setting. | Phase 7 |
| **D3** | Family "Emma, 9": a child with no account of her own. | A **dependent profile** is a new concept: someone else owns the data, and COPPA questions apply to under-13s. It also doesn't fit the owner = data subject model. | **v1 Family = people who share with me** (consent-based). Dependents need a design + legal review first. | Phase 8 (family) |
| **D4** | Share kinds "hospital" and "app" (desktop Privacy Centre). | "Hospital" breaks consumers only. "App" means a third-party app receiving data, which breaks "nothing to third parties". | **Remove both from the Figma file and from `Share.grantee_kind`.** Keep doctor / family / other. | Phase 4 |

## Gaps where Figma has no frame

| # | Question | Recommendation | Blocks |
|---|---|---|---|
| **D5** | Appointments show "pending" and "confirmed". Who confirms? | The user does. This is a personal tracker, not a booking system: pending = scheduled, not yet confirmed by me. No provider integration. | Phase 7 |
| **D6** | How do lab results get in? No entry frame exists. | v1: a manual entry form (needs a Figma frame) + the demo seed. Parsing PDFs (OCR) later, if ever. | Phase 6 |
| **D7** | Mood and mindful-minutes logging. The Mental tab shows data, but there's no input frame. | A small "log mood" sheet (needs a frame). Mindful minutes come from HealthKit (Android: manual). | Phase 8 |
| **D8** | Digital wellbeing on **iOS**. | Apple's Screen Time API keeps usage data inside a sandboxed extension that can't send it off the device. **Android only** (`UsageStatsManager`). The iOS tab says it isn't available on iPhone. | Phase 9 |
| **D9** | Remote push notifications. | iOS remote push needs the **paid** Apple Developer Program ($99/yr), which breaks "everything free". Payloads would also pass through Apple, Google and Expo. **Use local notifications for reminders and the in-app feed for everything else.** | Phase 7–8 |
| **D10** | Installing on an iPhone. | HealthKit needs a dev build. Free Apple ID provisioning lasts 7 days per install and has capability limits; **verify HealthKit works with a personal team** early in phase 9. TestFlight and the App Store need the paid program. Android has no such limit. | Phase 9 |

## Data and copy

| # | Question | Recommendation |
|---|---|---|
| **D11** | km (Workouts, Records) vs miles (Activity). | Store SI; every screen follows Settings › Units. Default to imperial for Dallas. |
| **D12** | Daily step goal 8,000 (dashboard) vs 10,000 (elsewhere). | One value in `profiles.daily_steps_goal`, default 10,000. |
| **D13** | "Organ donor" on the Emergency ID. | Keep. It's an organ-donor preference, not a blood feature. Confirm with the group. |
| **D14** | Library articles show reviewer names and the Figma copy is placeholder. | Write or license real articles (CC-licensed public-health sources). Set `reviewer` only for a real person who agreed. Drop blood-era articles ("Who can donate…", "Iron-rich foods…" if framed for donors) and the "Blood" category. |
| **D15** | Accounts and platform (carried over from the design doc). | Shared group logins for Supabase, Render and GitHub. Android minimum = 9 (Health Connect app) or 14 (built in). Ask whether the department offers hosting. |
| **D16** | "HIPAA-aligned" copy on sign-in and the homepage. | Remove it. The app is regulated by the FTC Health Breach Notification Rule, not HIPAA. |

## Already decided in this plan

These don't need a decision, but the owner should know they changed from the design doc:

- **10 modules instead of 6.** Records was split into records and care, and sharing, notifications
  and library were added.
- **35 tables instead of 18.**
- **`health_readings` has no surrogate id or unit column.** Its key is its natural key, which cuts
  storage per user-year from about 23 MB to about 13.5 MB.
- **Blood pressure is two metrics** (`bp_systolic`, `bp_diastolic`).
- **Audit refusal reasons are an enum.** The log holds no free text, so nothing needs scrubbing
  when an account is deleted.
- **A viewer's account deletion revokes the shares they received.** A later sign-up with the same
  email can't inherit them.
- **Keepalive runs on an external free monitor, not GitHub Actions** (minutes budget).
- **Reminders are local notifications.** There's no server push.
