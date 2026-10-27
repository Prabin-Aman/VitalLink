# 13. Phase 10 review: security, schema, incidents

Run 27 Oct 2026 on the Java backend (docs/09, phase 10, first bullet). Three passes:

- **Security.** Every route under `/v1/me` that takes an id; PATCH bodies; SQL built in code; the error
  handler; signed URLs; token checks; the job secret.
- **Schema.** V1–V10.
- **Incidents.** Ingest, the nightly job, and the record upload and confirm flow (written up in docs/08 §8.8).

Each finding below is fixed and has a regression test that fails without the fix. For the three file
fixes this was confirmed by re-running the tests against the old behaviour. All fixes are in V11
(`V11__review_hardening.sql`) and the code; no earlier migration was edited.

## 13.1 Findings fixed

| # | Severity | Finding | Fix | Test |
|---|---|---|---|---|
| R1 | High | **Email confirmation came from `user_metadata.email_verified`**, which Supabase users can write themselves (`auth.updateUser({data})`). Consent rule 2 ("an unconfirmed email matches no grant") relied on it. Someone holding a token for an address they don't own could set the flag and read shares made out to that address. | Production ignores the flag and asks Supabase's admin API (`GET /auth/v1/admin/users/{id}`). Only `email_confirmed_at` for the same address counts, and anything else (including an outage) means "no". It asks only while the account isn't confirmed. Dev tokens are signed by us, so their flag still counts. | `EmailConfirmationTest`, `IdentityAdminTest` |
| R2 | Low | **Sign-up names from `user_metadata` went into `profiles` unchecked**, past the 1–60 limit that `PATCH /me/account` applies. An over-long email reached the database too. | `TokenVerifier` strips names and cuts them to 60 characters. An email over 254 characters gets 403. V11 adds the same limits as CHECKs. | `TokenClaimsTest`, `ReviewFixesTest.longSignUpNamesAreCutToTheProfileLimit` |
| R3 | Medium | **`JOB_SECRET` had no minimum length.** The `.env.example` value `change-me-too` would have guarded production. | In production a secret under 32 characters counts as none, so the endpoint answers 503 rather than accept a guessable secret. Local runs keep short ones. | `JobSecretTest` |
| R4 | Medium | **The nightly job answered 200 when users failed**, so the workflow stayed green and failures went unseen. The cleanup steps weren't isolated either: one throwing skipped the rest. | Each cleanup runs in its own transaction and try. The summary lists `steps_failed`, and the endpoint answers **500** when any user or step failed. `nightly.yml` uses `--fail-with-body`, so the log shows the summary. | `NightlyJobTest.aRunWithFailuresIsAnErrorNotASuccess` |
| R5 | Medium | **Replacing a record's file deleted the old file when the replacement *started*.** If the new upload was abandoned or rejected, the user lost the original. | A replacement waits in `replacement_key`/`replacement_name`, and the record stays `attached` with the old file. Confirm swaps the two and only then deletes the old file. The nightly job drops replacements older than a day. | `ReviewFixesTest.replacingAFileKeeps…`, `…anAbandonedReplacement…` |
| R6 | Low | **The abandoned-upload cleanup could undo a confirm.** It read the pending rows, then reset each one by id without checking again. A confirm that committed in between got its file wiped. | One `UPDATE … FROM (SELECT … FOR UPDATE)` that re-checks status and key. Confirm updates only the key it checked, and answers 409 if it lost the race. | `ReviewFixesTest.theNightlyCleanupNeverUndoes…` (two connections) |
| R7 | Medium | **Files of deleted records and accounts could stay in Storage for good.** The delete ran after commit, and if Storage was down it only logged "the nightly job retries orphans". Nothing retried. That breaks the promise to delete an account's data. | New `file_deletions` queue, written in the same transaction as the delete. The after-commit delete still runs, and the nightly job deletes whatever is still queued; the step fails (red workflow) until Storage answers. | `ReviewFixesTest.deletedFilesStayQueued…` |
| S1 | Low | **A lab panel's `record_id` could point at another user's record** as far as the database was concerned (the API checked it). | Unique `(id, user_id)` on `health_records`. The FK from `lab_panels (record_id, user_id)` now uses `ON DELETE SET NULL (record_id)`. | `ReviewFixesTest.aLabPanelCanOnlyPointAtItsOwnersRecord` |
| S2 | Low | **Limits only the API enforced:** names, city and language in `profiles`; email shape in `users` and `consents`; time zone length; device name, id and metric count; workout `focus` and `external_id`; non-negative ingest counts (`counts_add_up` alone allows −1 + 1); `meal_items.position`; `conditions.noted_on`; `audit_log.request_id`. | CHECK constraints in V11, matching the API's limits. | `ReviewFixesTest.limitsTheApiAppliesHoldInTheSchemaToo` |
| S3 | Guardrail | Every foreign key already had an index starting with its column (one deliberate exception: `health_readings → metrics`, a catalogue that is never deleted from, where an index would slow every ingest). | New guardrail test so it stays that way. | `DatabaseTest.everyForeignKeyHasAnIndexStartingWithIt` |

Also changed: Spotless now writes LF (`<lineEndings>UNIX</lineEndings>`). On Windows, `spotless:apply`
had been rewriting every file as CRLF.

## 13.2 Checked and fine

- **IDOR/BOLA.** Medications (and doses), appointments, conditions, emergency contacts, goals,
  workouts, meals, hydration, devices, records (upload, confirm, download), labs, shares and
  notifications. Every read or write filters by the caller's `user_id`, or loads the row through an
  owner-scoped lookup first. Ids passed in bodies (`plan_session_id`, `food_id`, lab `record_id`,
  notification ids) are checked against the caller. Another user's id gets 404.
- **Shared routes.** Every `/v1/users/{user_id}` handler takes a `ConsentGate` and is GET-only (existing
  guardrail tests). The shared record download requires the `records` scope and the owner's id.
- **Mass assignment.** `Patch.of` refuses unknown keys, and each service lists its fields. Record bodies
  refuse extra fields (`fail-on-unknown-properties`).
- **Injection.** All values are bound parameters. The SQL that is built in code only joins column names
  from allow-lists in the code (`sets`, `where`, aggregation SQL from the catalogue). `LIKE` searches
  escape wildcards.
- **Error leaks.** Unhandled errors return a fixed message with the request id. Validation errors give
  field locations, never values. Storage failures answer 503 without detail. Supabase response bodies are
  never echoed.
- **Signed URLs.** Keys are `user/record/uuid7` (not guessable), downloads last 5 minutes, local tokens
  are HMAC-signed per key, method and expiry, and file names are URL-encoded.
- **Tokens.** Production accepts only ES256/RS256 from the project's JWKS, with issuer, audience,
  `exp`, `iat` and `sub` required and no clock skew. HS256 is accepted only in local and test, and Render
  refuses to start unless `ENV=production`.

## 13.3 Left as is (noted for the owner)

- `ENV` defaults to `local` when unset. Render is guarded (it won't start), but any other host must
  set `ENV=production`.
- The database doesn't stop a workout's `plan_session_id` or a meal item's `food_id` from naming
  another user's row (those tables have no `user_id` to key on). The API checks both, and tests cover it.
- Stored files are checked by their declared type and size, not their bytes. The download is served
  as an attachment with the stored type, so a mislabelled file can't run in the page.
- Ingest refuses a whole batch for one bad bucket (by design: nothing is half-saved). The phone app
  (phase 9) must drop the buckets the 422 lists and re-send the rest under a new key (docs/08 §8.8).
