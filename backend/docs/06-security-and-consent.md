# 6. Security, privacy and the consent engine

## 6.1 Consent engine

The consent engine decides whether one person may read another person's data. It has two layers:

- **`decide(viewer, owner_id, scope, grants, now) → Decision`** is a pure function with no database,
  network or framework, so every rule is tested exhaustively.
- **`ConsentGate.check()`** loads the owner's grants for this viewer, calls `decide`, binds the grant
  to the viewer on first allowed use, and writes the audit entry **in its own transaction** before
  returning or raising.

Rules, applied in order:

| # | Condition | Result | HTTP |
|---|---|---|---|
| 1 | Viewer is the owner | Allowed | — |
| 2 | Viewer's email is not confirmed | Treated as having no grant | 404 |
| 3 | No grant from owner to viewer (by bound account id, or by confirmed email if not yet bound) | Refused `no_grant` | 404 (existence hidden) |
| 4 | Grants exist, none covers this scope | Refused `scope_not_granted` | 403 |
| 5 | Covering grant is revoked | Refused `revoked` | 403 |
| 6 | Covering grant starts in the future | Refused `not_yet_valid` | 403 |
| 7 | Covering grant has expired (expiry is exclusive) | Refused `expired` | 403 |
| 8 | A covering grant is live | Allowed; consent id recorded; bound to viewer on first use | 200 |

A grant bound to another account never matches by email again. A route that combines scopes (the
shared dashboard, the family summary) checks each scope, returns only what's allowed, and records
`limited` in the access log.

**Why the audit write has its own transaction.** A refusal raises, and raising rolls back the
request transaction. If the audit row lived in that transaction, refusals (the accesses most worth
recording) would vanish. The `audit_log` trigger rejects `UPDATE`/`DELETE`, so neither a bug nor a
compromised API role can rewrite history.

**Route guardrail.** Every route under `/users/{user_id}/` must declare `Depends(require_scope(...))`.
A test walks the app's routes and fails the build otherwise.

## 6.2 Locking down Supabase

Supabase publishes a REST API over exposed schemas, and the `anon` key that sits inside the mobile
app is public by design. If our tables were reachable that way, anyone could bypass the API, the
consent engine and the audit log. Four locks, all checked in CI against the live project:

1. All tables in the private schema `vitallink`, which is not in the Data API's exposed schemas.
2. Data API switched off for the project. Supabase is used only for Auth and Storage.
3. RLS enabled on every table with **no policies** (deny all), as a second lock.
4. `REVOKE ALL` on the schema, its tables and its default privileges from `anon` and `authenticated`.

Storage works the same way: one private bucket with a 25 MB object limit and a MIME allow-list,
no public or anonymous policies, and access only through URLs the API signs for 5 minutes. The
Supabase service key lives only in Render secrets.

## 6.3 Authentication

- Supabase Auth issues the tokens. Email confirmation is **on**, because shares match on email and an
  unconfirmed address must not be able to claim one. Password, Google and Apple sign-in are supported.
- The API verifies the signature (JWKS, cached 10 min, refetched on an unknown `kid`), issuer,
  audience and expiry on every request.
- MFA: Supabase TOTP (free). Settings › Two-factor shows whether a factor is enrolled. Deleting the
  account and creating a share need `aal2` when MFA is enrolled, and a token issued within the last
  10 minutes either way.
- A signed-out token stays valid until it expires (≤ 1 h). That's an accepted risk.

## 6.4 Threats and controls

| Threat | Control |
|---|---|
| Direct DB reads with the app's public key | 6.2's four locks + CI deployment check |
| Claiming a share by signing up with someone else's email | Email confirmation; unconfirmed matches nothing; grants bind on first use |
| Inheriting a deleted viewer's shares by re-registering their email | Account deletion revokes received grants (`grantee_deleted`); FK `RESTRICT` forces it |
| IDOR on `/me/...` | Every repository query takes `user_id` from the token, never from the path or body; tests try other users' ids |
| IDOR on `/users/{id}/...` | Consent gate on every route (guardrail test); unknown/unshared → 404 |
| Mass assignment | Separate input records per route and unknown JSON fields refused; PATCH bodies go through an allow-list; `user_id`, `file_status`, `bound_at`… never accepted from clients |
| Forged token | Signature, `iss`, `aud`, `exp` checked; dev tokens accepted only when `ENV=local` |
| Leaked document link | Private bucket; 5-minute signed URLs; download only for `attached` files |
| Oversized or disguised upload | Type and size checked before signing, enforced by the bucket, verified at confirm |
| Abusive sync | 5,000-bucket cap, per-user rate limit, plausibility ranges, 5-min future limit |
| SQL injection | ORM / bound parameters only; a lint rule bans `text()` with f-strings |
| Scheduler endpoint abuse | Separate secret, `hmac.compare_digest`; not reachable with a user token |
| Health data in logs | Request and response bodies never logged; logs hold request id, route, status, timing, user id |
| Health data through push providers | No remote push in v1. Reminders are local notifications on the phone. If remote push is added, payloads are generic ("You have a new update") and details load in the app  |
| Emergency ID read by strangers | No public endpoint. The QR code holds the emergency text itself and is generated on the phone  |
| Secrets in the repo | Render / GitHub secrets only; `.env` git-ignored; GitHub secret scanning on |
| Interception | HTTPS only (Render and Supabase) |

## 6.5 Privacy commitments

- **Data minimisation.** No location history, contacts, advertising ids or per-user library reads.
  Sensor data is stored as 5-minute aggregates, never raw samples.
- **Nothing inbound, nothing to third parties.** No other system writes into a user's record, and no
  data goes to analytics or ad services. Sharing only sends data out, read-only, to a person the
  owner names.
- **Export and delete.** `GET /me/export` returns everything. `DELETE /me` removes rows, files and the
  sign-in identity. Audit entries about the user stay, as ids and enums only.
- **Honest insights.** Correlations need ≥ 14 days of data, pass a Benjamini–Hochberg correction and
  are worded as associations, never as causes.
- **Legal context.** Outside HIPAA, inside the FTC Health Breach Notification Rule. An unauthorised
  disclosure counts as a breach, and affected users must be told within 60 days. The audit log tells
  us whose data was touched. Product copy must not claim "HIPAA-aligned" (CLAUDE.md open issue).

## 6.6 Guardrail tests (the constraints, as code)

| Guardrail | Test |
|---|---|
| Consumers only | No route path, table, column or enum value contains `hospital`, `clinic`, `provider`, `admin` (allow-list: `preferred_hospital` text field) |
| No blood features | No table, column, enum value or seed contains `blood_type`, `blood_group`, `donor` (allow-list: `organ_donor`), `blood_bank`; `bp_*`, `glucose`, `spo2` fine; lab entry rejects ABO/Rh analytes |
| Consent | Every `/users/{id}` route has `require_scope`; no write method under `/users/` |
| Catalogue | Every metric maps to exactly one scope and has a range |
| Audit | `UPDATE`/`DELETE` on `audit_log` raises |
| Lockdown | Deployment check: exposed schemas, RLS, grants, bucket privacy, Data API returns nothing with the anon key |
