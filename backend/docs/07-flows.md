# 7. Key flows

> **Note (Nov 2026):** the mobile app was removed and phone sync cancelled. Parts below that mention them are historical. See [the index](README.md).

## 7.1 Sign-in and first request

```mermaid
sequenceDiagram
  participant App
  participant Auth as Supabase Auth
  participant API
  participant DB
  App->>Auth: email + password, or Google / Apple
  Auth-->>App: access token (1 h) + refresh token
  App->>API: GET /v1/me (Bearer)
  API->>API: verify signature, iss, aud, exp (JWKS cache)
  API->>DB: INSERT users ... ON CONFLICT (auth_subject) DO NOTHING
  API->>DB: INSERT profiles ... ON CONFLICT DO NOTHING
  API->>DB: SELECT user by auth_subject
  API-->>App: Me
```

The app fires several requests at once on first launch. Insert-if-absent lets them all succeed.
The API never sees a password.

## 7.2 Device sync (phone only)

```mermaid
sequenceDiagram
  participant Store as HealthKit / Health Connect
  participant Phone
  participant API
  participant DB
  Phone->>Store: changes since saved anchor
  Store-->>Phone: new, late and deleted samples
  Phone->>Store: merged statistics for the affected 5-min buckets
  Phone->>API: POST /me/health/ingest {key, platform, devices, buckets}
  API->>DB: SELECT ingest_batches by (user, key)
  alt same key seen
    API-->>Phone: 202 first result, replayed true (or 409 if hash differs)
  else new
    API->>API: validate metric, range, bucket alignment, not > 5 min in future
    API->>DB: upsert buckets (new value wins), upsert devices, insert batch row
    API-->>Phone: 202 inserted / updated / unchanged
  end
  Phone->>Phone: save anchor only after 202
```

- **Merged statistics, not raw samples.** iPhone + Watch both count the same steps. HealthKit
  statistics queries and Health Connect aggregate reads merge sources, so nothing doubles.
- **Upsert, don't skip.** Late watch data and user deletions change a bucket that was already sent.
  The new value replaces the old one.
- **Anchor after success.** A failed upload retries with the same key. More than 5,000 buckets → 413,
  and the phone splits the batch.
- **Workouts** sync through `POST /me/workouts` with `source` + `external_id`, so a re-sync updates
  the workout instead of duplicating it.

## 7.3 Sharing and a shared read

```mermaid
sequenceDiagram
  participant Owner
  participant Viewer
  participant API
  participant Gate as ConsentGate
  participant DB
  Owner->>API: POST /me/shares {email, label, kind, scopes, expires}
  API->>DB: insert consent + audit share_granted
  Viewer->>API: GET /me/shared-with-me
  API-->>Viewer: grants for this confirmed account
  Viewer->>API: GET /users/{owner}/health/series/steps
  API->>Gate: check(viewer, owner, activity)
  Gate->>DB: load grants by viewer id or confirmed email
  Gate->>Gate: decide(...)
  Gate->>DB: bind grant to viewer (first use)
  Gate->>DB: audit entry, own transaction
  alt allowed
    API->>DB: read series
    API-->>Viewer: 200 Series
  else refused
    API-->>Viewer: 403 or 404
  end
  Owner->>API: DELETE /me/shares/{id}
  API->>DB: revoked_at = now, audit share_revoked
```

Revocation applies on the viewer's next request, because nothing is cached between requests.

## 7.4 Family view

```mermaid
sequenceDiagram
  participant Viewer
  participant API
  participant Gate as ConsentGate
  participant DB
  Viewer->>API: GET /me/family
  API->>DB: live grants where viewer is grantee
  loop each owner who shares with me
    API->>Gate: check(viewer, owner, vitals)
    API->>Gate: check(viewer, owner, records)
    API->>DB: resting HR if vitals, conditions + meds + next appointment if records
  end
  API-->>Viewer: [me, members with access + summary]
```

Each family card is a real read of someone's data, so it gets an access-log entry
(`authorised` or `limited`). That's intended. Unlike the removed "access check" endpoint, each read
here returns data.

## 7.5 Document upload

```mermaid
sequenceDiagram
  participant App
  participant API
  participant ST as Storage
  App->>API: POST /me/records {type, title, date}
  API-->>App: record (file_status none)
  App->>API: POST /me/records/{id}/upload {name, type, size}
  API->>API: type allowed and size up to 25 MB?
  API-->>App: signed upload URL (5 min), status pending
  App->>ST: PUT file
  App->>API: POST /me/records/{id}/confirm
  API->>ST: stat object (size, type)
  API-->>App: status attached
```

Uploads still pending after 24 h are deleted by the nightly job. Downloads are signed for 5 minutes.

## 7.6 Taking a dose

```mermaid
sequenceDiagram
  participant Phone
  participant API
  participant DB
  Note over Phone: Local notification at 8:00 AM, scheduled on the device from the medication's schedule
  Phone->>API: PUT /me/medications/{id}/doses/2026-09-13T13:00:00Z {status taken}
  API->>DB: upsert dose_logs (medication_id, scheduled_for)
  API-->>Phone: 200 dose
  Note over API,DB: Nightly - doses with no log after 2 h grace become a "missed dose" notification
```

The phone re-schedules local notifications whenever `GET /me/medications` or
`GET /me/appointments` returns something new. Reminders need no server push and work offline.

## 7.7 Nightly job

```mermaid
sequenceDiagram
  participant GH as GitHub Actions (02:00 Central)
  participant API
  participant DB
  GH->>API: GET /ready (retry up to 2 min while Render wakes)
  GH->>API: POST /internal/jobs/nightly (X-Job-Secret)
  API->>DB: users active in the last 30 days
  loop each user, own transaction
    API->>DB: goal progress for closed periods, lifecycle to achieved
    API->>API: correlations + trends (14-day minimum, BH correction)
    API->>DB: replace insights by dedupe key
    API->>DB: notifications - missed doses, goal achieved, device overdue, refill due
  end
  API->>DB: close expired shares (audit), delete stale pending uploads
  API->>DB: delete notifications older than 90 days, ingest batches older than 30
  API-->>GH: 200 {users, insights, notifications, shares_closed, uploads_cleaned, failures}
  GH->>GH: fail the run if failures > 0
```

Every step is safe to run twice: progress is upserted, insights and notifications have dedupe keys,
and cleanup is idempotent.
