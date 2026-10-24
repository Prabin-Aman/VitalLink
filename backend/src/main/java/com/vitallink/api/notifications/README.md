# Notifications

The in-app feed (no remote push, decision D9). Events (new lab result, shared records opened) and the
nightly job add entries; dedupe keys make repeats harmless. Kept 90 days.

- `GET /me/notifications`, `POST /me/notifications/read`

Table: `notifications`.
