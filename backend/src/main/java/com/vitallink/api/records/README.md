# Records

Health records (metadata plus one optional stored file) and typed-in lab panels with results,
interpretation and previous values. Blood-group results are refused.

- `POST/GET /me/records`, `GET/PATCH/DELETE /me/records/{id}`, `GET /me/records/overview`
- `POST /me/records/{id}/upload` → signed URL; `POST /confirm` → server checks the file; `GET /download`
- `POST/GET /me/labs`, `GET/PUT/DELETE /me/labs/{id}`, `GET /me/labs/latest-result`

Tables: `health_records`, `lab_panels`, `lab_results`. Files: Supabase Storage (private bucket).
