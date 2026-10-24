# Goals

Goals over health metrics, nutrition totals and workouts, with progress and status computed on read,
the goals overview (achievements, step-goal streak, 5 weeks of consistency), today's progress and streaks.

- `POST/GET /me/goals`, `GET/PATCH/DELETE /me/goals/{id}`, `GET /me/goals/metrics`
- `GET /me/goals/overview`, `GET /me/goals/today`, `GET /me/streaks`

Table: `goals`. The nightly job marks reached target goals achieved.
