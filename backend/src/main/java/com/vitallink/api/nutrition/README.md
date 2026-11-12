# Nutrition

Food catalogue (30 seeded, per 100 g) plus users' own foods, meals whose items copy macros at logging
time, water, targets, and the day view with energy balance (Mifflin-St Jeor).

- `GET /foods/search`, `POST /me/foods`, `POST /me/meals`, `DELETE /me/meals/{id}`
- `POST /me/hydration`, `DELETE /me/hydration/{id}`, `GET /me/nutrition/day`, `GET/PATCH /me/nutrition/targets`

- `GET /foods/barcode/{code}` (Open Food Facts, remembered in `foods.barcode`), `GET /me/hydration` (today's entries)
- Food search also asks USDA FoodData Central (`FoodDataCentral`, USDA_API_KEY, free, CC0) when your foods, the
  seeded list and USDA foods found before give fewer than 10 matches: Foundation, SR Legacy and survey foods, per
  100 g, energy from 208 (else Atwater 958/957, else kJ), a survey portion as the serving. Each is stored once as a
  shared `usda` food (V23/V24, `foods.fdc_id`), so meals and recipes can use it; answers are cached a day in memory.
  Without a key, or when USDA fails, the search returns VitalLink's own matches. `FoodOut.source` says which.
- Recipes: `GET/POST /me/recipes`, `GET/PATCH/DELETE /me/recipes/{id}`, `POST /me/recipes/{id}/log` (writes a meal)

Tables: `foods`, `meal_entries`, `meal_items`, `hydration_logs`, `nutrition_targets`, `recipes`, `recipe_items`.
