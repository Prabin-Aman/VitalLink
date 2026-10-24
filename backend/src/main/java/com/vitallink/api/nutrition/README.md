# Nutrition

Food catalogue (30 seeded, per 100 g) plus users' own foods, meals whose items copy macros at logging
time, water, targets, and the day view with energy balance (Mifflin-St Jeor).

- `GET /foods/search`, `POST /me/foods`, `POST /me/meals`, `DELETE /me/meals/{id}`
- `POST /me/hydration`, `DELETE /me/hydration/{id}`, `GET /me/nutrition/day`, `GET/PATCH /me/nutrition/targets`

Tables: `foods`, `meal_entries`, `meal_items`, `hydration_logs`, `nutrition_targets`.
