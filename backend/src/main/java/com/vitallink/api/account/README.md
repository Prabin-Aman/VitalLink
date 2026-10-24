# Account

Maps a sign-in token to a user (created on first use), holds the profile and daily targets, and runs
export and delete.

- `GET /me`, `GET/PATCH /me/account`, `GET/PATCH /me/targets`
- `GET /me/export`: every module's `UserData` export queries, one JSON download
- `DELETE /me`: needs a sign-in within 10 minutes; revokes and unbinds shares received → module
  `beforeDelete` hooks (stored files) → deletes the user (cascade) → deletes the Supabase identity after commit

Tables: `users`, `profiles`. Classes: `AccountController`, `AccountService`, `DataRightsService`, `CurrentUser`.
