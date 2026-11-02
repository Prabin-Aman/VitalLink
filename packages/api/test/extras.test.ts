import { describe, expect, it } from "vitest";
import { checkBarcode, checkPasswordChange, distance, loadHydration, loadSettings, pace, VitalLinkClient } from "../src";

describe("Settings › Security › Change password", () => {
  const ok = { current: "Old-pass-1234!", next: "Brand-new-pass-9!", confirm: "Brand-new-pass-9!" };
  it("needs the current password, a strong new one typed twice, and a change", () => {
    expect(checkPasswordChange(ok)).toEqual({ ok: true, body: { current: ok.current, next: ok.next } });
    expect(checkPasswordChange({ ...ok, current: "" })).toMatchObject({ ok: false });
    expect(checkPasswordChange({ ...ok, next: "short", confirm: "short" })).toMatchObject({ ok: false, error: expect.stringContaining("12 characters") });
    expect(checkPasswordChange({ ...ok, confirm: "Brand-new-pass-8!" })).toMatchObject({ ok: false, error: expect.stringContaining("don't match") });
    expect(checkPasswordChange({ current: ok.next, next: ok.next, confirm: ok.next })).toMatchObject({ ok: false, error: expect.stringContaining("different") });
  });
});

describe("Nutrition › Scan Barcode", () => {
  it("takes 8 to 14 digits, ignoring spaces and dashes", () => {
    expect(checkBarcode("5 201054 017593")).toEqual({ ok: true, body: "5201054017593" });
    expect(checkBarcode("0-12345-67890-5")).toEqual({ ok: true, body: "012345678905" });
    expect(checkBarcode("1234567")).toMatchObject({ ok: false });
    expect(checkBarcode("12345abc90")).toMatchObject({ ok: false });
  });
});

describe("Settings › Units", () => {
  it("shows distance and pace in km or miles", () => {
    expect(distance(10000, "metric")).toBe("10.0 km");
    expect(distance(10000, "imperial")).toBe("6.2 mi");
    expect(pace(341, "metric")).toBe("5:41 /km");
    expect(pace(341, "imperial")).toBe("9:09 /mi");
    expect(pace(359.6)).toBe("6:00 /km"); // never "5:60"
  });
  it("offers the units setting next to height", async () => {
    const v = await loadSettings(new VitalLinkClient());
    expect(v.fields.find((f) => f.key === "units")).toMatchObject({ kind: "select", value: "metric" });
  });
});

describe("Hydration › Today", () => {
  it("lists today's entries newest first with a total", async () => {
    const v = await loadHydration(new VitalLinkClient());
    expect(v.entries[0]).toEqual({ id: "w4", amount: "500 ml", when: "2:40 PM" });
    expect(v.entriesLine).toBe("4 entries · 2.1 L. Remove takes an entry off today's total.");
  });
});

describe("Nutrition › Recipes", () => {
  const oats = { id: "f-oats", name: "Rolled oats", brand: null, serving_g: 40, kcal: 379, protein_g: 13.2, carbs_g: 67.7, fat_g: 6.5, fibre_g: 10.1 };
  it("needs a name, whole servings and at least one food", async () => {
    const { checkRecipe, checkIngredient } = await import("../src");
    const items = [{ food: oats, grams: 100 }];
    expect(checkRecipe({ name: " Oats ", servings: "2", notes: "", items })).toMatchObject({ ok: true, body: { name: "Oats", servings: 2, items: [{ food_id: "f-oats", quantity_g: 100 }] } });
    expect(checkRecipe({ name: "", servings: "2", notes: "", items })).toMatchObject({ ok: false });
    expect(checkRecipe({ name: "Oats", servings: "1.5", notes: "", items })).toMatchObject({ ok: false });
    expect(checkRecipe({ name: "Oats", servings: "2", notes: "", items: [] })).toMatchObject({ ok: false, error: "Add at least one food." });
    expect(checkIngredient(null, "100")).toMatchObject({ ok: false });
    expect(checkIngredient(oats, "0")).toMatchObject({ ok: false });
  });
  it("estimates a serving and logs servings in quarters", async () => {
    const { recipeEstimate, checkRecipeLog } = await import("../src");
    expect(recipeEstimate([{ food: oats, grams: 100 }], "2")).toBe("≈ 190 kcal · 7 g protein · 34 g carbs · 3 g fat a serving (50 g)");
    expect(checkRecipeLog({ slot: "breakfast", servings: "1.5", date: "2026-09-13", time: "07:40" }, "America/Chicago")).toMatchObject({ ok: true, body: { servings: 1.5, eaten_at: "2026-09-13T07:40:00-05:00" } });
    expect(checkRecipeLog({ slot: "breakfast", servings: "1.3", date: "2026-09-13", time: "07:40" }, "America/Chicago")).toMatchObject({ ok: false });
  });
  it("lists recipes with their serving", async () => {
    const { loadRecipes } = await import("../src");
    const v = await loadRecipes(new VitalLinkClient());
    expect(v.rows[0]).toMatchObject({ title: "Chicken & rice bowl", caption: "2 servings · 3 ingredients · 370 g a serving", kcal: "426 kcal a serving" });
  });
});

describe("Goals follow Settings › Units", () => {
  const metrics = [
    { code: "distance", label: "Distance", unit: "m", source: "health", kind: "daily_total", area: "activity" },
    { code: "weight", label: "Weight", unit: "kg", source: "health", kind: "target_value", area: "body" },
  ] as import("../src").GoalMetricOption[];
  it("types distance in miles and weight in lb when imperial", async () => {
    const { checkGoal, goalUnit } = await import("../src");
    const f = { metric: "distance", direction: "at_least", target: "3", starts: "2026-11-01", ends: "" };
    expect(goalUnit(metrics[0], "imperial")).toMatchObject({ unit: "mi" });
    expect(checkGoal(f, metrics, "imperial")).toMatchObject({ ok: true, body: { title: "Distance: 3 mi a day", target: 4828.032 } });
    expect(checkGoal({ ...f, metric: "weight", direction: "at_most", target: "150" }, metrics, "imperial")).toMatchObject({ ok: true, body: { title: "Weight: At most 150 lb", target: 68.039 } });
    expect(checkGoal(f, metrics)).toMatchObject({ ok: true, body: { title: "Distance: 3 km a day", target: 3000 } });
  });
  it("keeps the stored target when an imperial goal's target isn't changed", async () => {
    const { checkGoalEdit, goalEditForm } = await import("../src");
    const g = { id: "g", title: "Weight: At most 150 lb", short_title: "Weight", area: "body", kind: "target_value", metric: "weight", comparator: "at_most", target: 68.04, baseline: null, starts_on: "2026-11-01", ends_on: null, lifecycle: "active", achieved_at: null } as import("../src").GoalDetail;
    const f = goalEditForm(g, metrics, "imperial");
    expect(f.target).toBe("150");
    expect(checkGoalEdit(f, g, metrics, "imperial")).toMatchObject({ ok: true, body: { target: 68.04 } });
    expect((checkGoalEdit(f, g, metrics, "imperial") as { body: object }).body).not.toHaveProperty("title");
  });
});
