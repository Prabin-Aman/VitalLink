# Care

Medications with schedules and a dose log ("due" and "missed" are computed), user-tracked appointments
(no booking), conditions and allergies, and the emergency medical ID with up to 5 contacts.

- `GET /me/medications`, `/medications/today`, `POST/GET/PATCH/DELETE /me/medications[/{id}]`
- `PUT/DELETE /me/medications/{id}/doses/{scheduled_for}` (idempotent)
- `POST/GET /me/appointments`, `PATCH/DELETE /me/appointments/{id}`
- `POST/GET /me/conditions`, `DELETE /me/conditions/{id}`
- `GET/PATCH /me/emergency-profile`, `POST/GET/PATCH/DELETE /me/emergency-contacts[/{id}]`

Tables: `medications`, `medication_schedules`, `dose_logs`, `appointments`, `conditions`,
`emergency_profiles`, `emergency_contacts`.
