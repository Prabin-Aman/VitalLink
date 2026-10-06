"""Nutrition: food catalogue, meals with copied macros, water, targets, the day view."""

import uuid
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.modules.nutrition.catalogue import FOODS, food_id

CHICAGO = ZoneInfo("America/Chicago")


def _today(hour: int, minute: int = 0) -> str:
    return datetime.combine(datetime.now(CHICAGO).date(), time(hour, minute), CHICAGO).isoformat()


@pytest.fixture
def user(client, make_token):
    headers = make_token()
    client.get("/v1/me", headers=headers)
    return headers


def _search(client, headers, q):
    return client.get(f"/v1/foods/search?q={q}", headers=headers).json()


@pytest.mark.anyio
async def test_food_catalogue_matches_database(pg):
    rows = await pg.fetch("SELECT id, name, kcal, protein_g FROM vitallink.foods WHERE source = 'seeded'")
    stored = {(r["id"], r["name"], float(r["kcal"]), float(r["protein_g"])) for r in rows}
    assert stored == {(food_id(n), n, float(k), float(p)) for n, k, p, *_ in FOODS}


def test_search_finds_seeded_and_own_foods_only(client, user, make_token):
    assert [f["name"] for f in _search(client, user, "yog")] == ["Greek yoghurt"]
    mine = client.post(
        "/v1/me/foods",
        headers=user,
        json={"name": "Grandma's yoghurt cake", "kcal": 320, "protein_g": 6, "carbs_g": 45, "fat_g": 12},
    )
    assert mine.status_code == 201
    assert [f["name"] for f in _search(client, user, "yog")] == ["Greek yoghurt", "Grandma's yoghurt cake"]
    stranger = make_token()
    assert [f["name"] for f in _search(client, stranger, "yog")] == ["Greek yoghurt"]
    assert _search(client, user, "%25%25") == []  # "%%": wildcards are matched literally


def test_meal_copies_macros_from_foods(client, user):
    yoghurt = _search(client, user, "greek")[0]
    meal = client.post(
        "/v1/me/meals",
        headers=user,
        json={
            "slot": "breakfast",
            "eaten_at": _today(7, 10),
            "items": [
                {"food_id": yoghurt["id"], "quantity_g": 200},
                {"label": "Coffee with milk", "quantity_g": 250, "kcal": 40, "protein_g": 2, "carbs_g": 3, "fat_g": 2},
            ],
        },
    )
    assert meal.status_code == 201, meal.text
    m = meal.json()
    assert (m["name"], m["items"]) == ("Breakfast", ["Greek yoghurt", "Coffee with milk"])
    assert (m["kcal"], m["protein_g"]) == (186, 22)  # 146 + 40, 19.8 + 2


@pytest.mark.parametrize(
    "item",
    [
        {"quantity_g": 100},  # neither a food nor macros
        {"food_id": str(food_id("Banana")), "quantity_g": 100, "kcal": 1},  # macros come from the food
        {"food_id": str(food_id("Banana")), "quantity_g": 0},
        {"label": "x", "quantity_g": 10, "kcal": -5, "protein_g": 0, "carbs_g": 0, "fat_g": 0},
    ],
)
def test_meal_rejects(client, user, item):
    body = {"slot": "snack", "eaten_at": _today(15), "items": [item]}
    assert client.post("/v1/me/meals", headers=user, json=body).status_code == 422


def test_cannot_use_someone_elses_food(client, user, make_token):
    other = make_token()
    client.get("/v1/me", headers=other)
    theirs = client.post(
        "/v1/me/foods", headers=other, json={"name": "Secret", "kcal": 1, "protein_g": 0, "carbs_g": 0, "fat_g": 0}
    ).json()
    body = {"slot": "snack", "eaten_at": _today(15), "items": [{"food_id": theirs["id"], "quantity_g": 50}]}
    assert client.post("/v1/me/meals", headers=user, json=body).status_code == 404


def test_day_view(client, user):
    banana = str(food_id("Banana"))
    client.post(
        "/v1/me/meals",
        headers=user,
        json={"slot": "snack", "eaten_at": _today(0, 30), "items": [{"food_id": banana, "quantity_g": 118}]},
    )
    yesterday = (datetime.now(CHICAGO) - timedelta(days=1)).isoformat()
    client.post(
        "/v1/me/meals",
        headers=user,
        json={"slot": "dinner", "eaten_at": yesterday, "items": [{"food_id": banana, "quantity_g": 1000}]},
    )
    client.post("/v1/me/hydration", headers=user, json={"ml": 500, "at": _today(0, 40)})
    client.patch("/v1/me/nutrition/targets", headers=user, json={"kcal": 2400, "weekly_target_kg": -0.4})

    d = client.get("/v1/me/nutrition/day", headers=user).json()
    assert (d["kcal"], d["carbs_g"], d["fibre_g"]) == (105, 27, 3)  # one banana; yesterday's isn't counted
    assert (d["kcal_goal"], d["weekly_target_kg"], d["water_ml"]) == (2400, -0.4, 500)
    assert d["base_burn_kcal"] is None  # no height, weight, birth date or sex yet
    assert [m["name"] for m in d["meals"]] == ["Snack"]
    assert datetime.fromisoformat(d["date"]).astimezone(CHICAGO).time() == time()


def test_base_burn_needs_a_full_profile(client, user):
    client.patch(
        "/v1/me/account",
        headers=user,
        json={"height_cm": 178, "date_of_birth": "1994-04-17", "sex": "male"},
    )
    client.post("/v1/me/health/manual", headers=user, json={"metric": "weight", "value": 72.4})
    burn = client.get("/v1/me/nutrition/day", headers=user).json()["base_burn_kcal"]
    age = (datetime.now(CHICAGO).date() - datetime(1994, 4, 17).date()).days // 365
    assert burn == round(10 * 72.4 + 6.25 * 178 - 5 * age + 5)


def test_targets_defaults_and_validation(client, user):
    t = client.get("/v1/me/nutrition/targets", headers=user).json()
    assert (t["kcal"], t["water_ml"], t["glass_ml"]) == (2200, 2500, 250)
    assert client.patch("/v1/me/nutrition/targets", headers=user, json={"kcal": 100}).status_code == 422
    assert client.patch("/v1/me/nutrition/targets", headers=user, json={"weekly_target_kg": 3}).status_code == 422


def test_delete_meal_and_water_are_scoped(client, user, make_token):
    meal = client.post(
        "/v1/me/meals",
        headers=user,
        json={"slot": "lunch", "eaten_at": _today(1), "items": [{"food_id": str(food_id("Apple")), "quantity_g": 180}]},
    ).json()
    water = client.post("/v1/me/hydration", headers=user, json={"ml": 300}).json()
    other = make_token()
    assert client.delete(f"/v1/me/meals/{meal['id']}", headers=other).status_code == 404
    assert client.delete(f"/v1/me/hydration/{water['id']}", headers=other).status_code == 404
    assert client.delete(f"/v1/me/meals/{meal['id']}", headers=user).status_code == 204
    assert client.delete(f"/v1/me/hydration/{water['id']}", headers=user).status_code == 204
    assert client.delete(f"/v1/me/meals/{uuid.uuid4()}", headers=user).status_code == 404
