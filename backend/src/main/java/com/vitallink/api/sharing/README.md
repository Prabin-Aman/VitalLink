# Sharing

Shares (consents) with named people, the shares I received, the owner's access log, the privacy centre,
the family view, and every consent-checked shared view under `/users/{user_id}/…` (read-only).

- `POST/GET /me/shares`, `DELETE /me/shares/{id}`, `GET /me/shared-with-me`, `GET /me/access-log`
- `GET/PATCH /me/privacy`, `GET /me/family`
- `GET /users/{id}/…`: health dashboard/series/latest/tabs, workouts, training, nutrition day,
  medications, appointments, conditions, records, labs, record download, summary

Table: `consents`. Guardrail tests: every shared route takes a `ConsentGate`, and none writes.
