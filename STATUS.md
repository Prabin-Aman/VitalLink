# VitalLink: what's built and what's missing (Nov 2026)

The web portal and Java API are live, and every screen except Messages reads and writes real data.

## Needs the owner's decision

| Item | Question |
|---|---|
| **Messages (D1)** | Messaging clinicians may conflict with consumers-only. Live mode says "isn't available yet". Build it or remove the page? |
| **Dependent profiles (D3)** | A child without an account is a new concept. |
| **Hospitals on Privacy** | The "Hospitals with access" card comes from the design, and conflicts with consumers-only. Likely to remove. |
| **"HIPAA-aligned" on Sign In** | Misleading for a non-HIPAA consumer app. |
| **Other decisions (D2, D4 to D14, D16)** | Defaults applied, sign-off pending: [10-open-decisions.md](backend/docs/10-open-decisions.md). |

## Not tested for real

- **File upload on the live site**, which also proves the Supabase secret key works.
- **A real Apple Health export.** Tested with a generated 2.5 million record file at Render's memory limit, not with a
  real iPhone export or on Render's slower CPU.
- **Bluetooth pairing:** tested with a simulated device.
- **Barcode camera:** the browser's BarcodeDetector, not tried on a phone.
- **Live load:** `LoadCheck` and `backend/scripts/restore-drill.sh` have only run locally.

## Known limits

- **Slow first load:** Render's free plan sleeps after about 15 idle minutes, and waking took 65 s when measured.
- **Database space:** Supabase's free plan allows 500 MB, and imported readings use it quickly.
- **Consents aren't recorded:** Create Account's Terms, reminders and research boxes aren't saved anywhere.
- **Android health data:** only CSV or typed readings.
- **No automatic Apple Health sync:** an iPhone Shortcut is the free option if it's wanted.

## Leftovers

- `deploy-check` and `nightly` on GitHub need their repository secrets.
- Figma still has the mobile pages, and older web frames still show the old sidebar and the Apple button.
- A few response types in `packages/api/src/types.ts` carry fields only the mobile app read.

## Removed on purpose

The mobile app and phone sync, Spanish, Sign in with Apple, and share links.
