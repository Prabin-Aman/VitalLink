# Shared core

Infrastructure every module uses. It knows no module (except `CurrentUser` from account, which the
argument resolver supplies).

| Package / class | Job |
|---|---|
| `Settings` | All config from env vars (`vitallink.*` in application.yml) |
| `ApiError`, `ErrorHandler` | The error envelope `{"error": {code, message, detail, request_id}}` and exception → status mapping |
| `Clock`, `Ids`, `Num`, `Naming`, `Streaks` | Microsecond "now", UUIDv7/v5, half-even rounding, snake_case, day streaks |
| `db.DatabaseConfig` | HikariCP pool from `DATABASE_URL` (JDBC or `postgresql://` form) |
| `db.RequestTransactionFilter` | One transaction per `/v1` request; rolls back on any error status; holds the body until commit |
| `security.TokenVerifier` | Supabase JWKS (ES256/RS256) in production, HS256 dev tokens locally; never mixed |
| `security.DevTokens`, `security.IdentityAdmin` | Dev tokens (local/test only); deleting a Supabase identity on account delete |
| `audit.AuditLog` | The only writer of `audit_log`, in its own transaction (refusals survive the rollback) |
| `consent.Consent` | Pure `decide()` — every rule unit-tested |
| `consent.ConsentGate`, `ConsentGates`, `AccessListener` | Loads grants, decides, binds on first use, audits, tells listeners |
| `storage.*` | `FileStorage`: Supabase Storage in production, signed local files in dev/test |
| `userdata.UserData` | The export/delete hook every module implements |
| `web.WebConfig`, `web.Patch`, `web.RequestIdFilter`, `web.OpenApiConfig` | CORS, `CurrentUser`/`ConsentGate` parameters, PATCH bodies, request ids, OpenAPI |
