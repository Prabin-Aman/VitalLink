// Nutrition tabs (Figma 195:2): Week (204:1068), Meals (204:1545), Hydration (204:1973).
import type { VitalLinkClient } from "../client";
import { longDate, thousands, timeOfDay, weekday } from "../format";
import { type Option, todayIn } from "../forms";
import type { NutritionDay, Recipe } from "../types";
import { dayLabel, stat, type StatVM } from "./shared";

// ---------------------------------------------------------------- Nutrition › Week, Meals, Hydration

/** The last 7 local days, oldest first, as "YYYY-MM-DD". */
function lastWeek(tz: string, nowIso: string) {
  const today = Date.parse(`${todayIn(tz, Date.parse(nowIso))}T12:00:00Z`);
  return Array.from({ length: 7 }, (_, i) => new Date(today - (6 - i) * 86400000).toISOString().slice(0, 10));
}
const noonIso = (date: string) => `${date}T12:00:00Z`;
const within = (v: number, goal: number) => goal > 0 && Math.abs(v - goal) <= goal * 0.1;
const litres = (ml: number) => (ml / 1000).toFixed(1);

export interface DayBarVM {
  key: string;
  label: string;
  value: string;
  fraction: number;
  onTarget: boolean;
}
export interface NutritionWeekVM {
  stats: StatVM[];
  bars: DayBarVM[];
  goalFraction: number;
  caption: string;
  days: { key: string; title: string; detail: string; kcal: string }[];
  totals: { label: string; value: string }[];
}

async function week(api: VitalLinkClient) {
  const me = await api.me();
  const tz = me.timezone;
  const dates = lastWeek(tz, api.now());
  const days = await Promise.all(dates.map((d) => api.nutrition(d)));
  return { tz, dates, days };
}

export async function loadNutritionWeek(api: VitalLinkClient): Promise<NutritionWeekVM> {
  const { tz, dates, days } = await week(api);
  const goal = days[days.length - 1].kcal_goal;
  const avg = (f: (d: NutritionDay) => number) => days.reduce((n, d) => n + f(d), 0) / days.length;
  const max = Math.max(goal * 1.15, ...days.map((d) => d.kcal));
  const onTarget = days.filter((d) => within(d.kcal, d.kcal_goal)).length;
  const consumed = days.reduce((n, d) => n + d.kcal, 0);
  return {
    stats: [
      stat("kcal", "Average", thousands(avg((d) => d.kcal)), "kcal", `a day · goal ${thousands(goal)}`, "macroCarbs"),
      stat("protein", "Protein", thousands(avg((d) => d.protein_g)), "g", `a day · goal ${days[6].goals.protein_g} g`, "macroProtein"),
      stat("target", "On target", String(onTarget), "of 7", "days within 10%", "goalOnTrack"),
      stat("water", "Water", litres(avg((d) => d.water_ml)), "L", `a day · goal ${litres(days[6].water_goal_ml)} L`, "dataRespiratory"),
    ],
    bars: days.map((d, i) => ({ key: dates[i], label: weekday(noonIso(dates[i]), tz), value: thousands(d.kcal), fraction: d.kcal / max, onTarget: within(d.kcal, d.kcal_goal) })),
    goalFraction: goal / max,
    caption: `Bars in colour are within 10% of your ${thousands(goal)} kcal goal; the dashed line is the goal.`,
    days: days
      .map((d, i) => ({
        key: dates[i],
        title: dayLabel(noonIso(dates[i]), tz),
        detail: `P ${d.protein_g} g · C ${d.carbs_g} g · F ${d.fat_g} g · water ${litres(d.water_ml)} L`,
        kcal: `${thousands(d.kcal)} kcal`,
      }))
      .reverse(),
    totals: [
      { label: "Consumed", value: `${thousands(consumed)} kcal` },
      { label: "Goal", value: `${thousands(goal * 7)} kcal` },
      { label: consumed <= goal * 7 ? "Under goal" : "Over goal", value: `${thousands(Math.abs(goal * 7 - consumed))} kcal` },
    ],
  };
}

export interface MealsDayVM {
  days: Option[];
  date: string;
  title: string;
  meals: { id: string; title: string; items: string; kcal: string }[];
  total: string | null;
  empty: string | null;
}
export async function loadMealsDay(api: VitalLinkClient, date: string | null): Promise<MealsDayVM> {
  const me = await api.me();
  const tz = me.timezone;
  const dates = lastWeek(tz, api.now());
  const pick = date ?? dates[dates.length - 1];
  const d = await api.nutrition(pick);
  const meals = [...d.meals].sort((a, b) => a.eaten_at.localeCompare(b.eaten_at));
  return {
    days: dates.map((x) => ({ value: x, label: `${weekday(noonIso(x), tz)} ${Number(x.slice(8))}` })),
    date: pick,
    title: `Meals on ${longDate(noonIso(pick), tz)}`,
    meals: meals.map((m) => ({ id: m.id, title: `${m.name} · ${timeOfDay(m.eaten_at, tz)}`, items: m.items.join(", "), kcal: `${thousands(m.kcal)} kcal` })),
    total: meals.length ? `${thousands(d.kcal)} kcal · ${d.protein_g} g protein · ${d.carbs_g} g carbs · ${d.fat_g} g fat` : null,
    empty: meals.length ? null : "Nothing logged on this day.",
  };
}

export interface HydrationVM {
  stats: StatVM[];
  bars: DayBarVM[];
  goalFraction: number;
  glasses: { ml: number; label: string }[];
  note: string;
  /** Today's entries, newest first (Figma 221:2205). */
  entries: { id: string; amount: string; when: string }[];
  entriesLine: string;
}
export async function loadHydration(api: VitalLinkClient): Promise<HydrationVM> {
  const [{ tz, dates, days }, entries] = await Promise.all([week(api), api.waterToday()]);
  const today = days[days.length - 1];
  const goal = today.water_goal_ml;
  const max = Math.max(goal * 1.2, ...days.map((d) => d.water_ml));
  const left = Math.max(0, goal - today.water_ml);
  const avg = days.reduce((n, d) => n + d.water_ml, 0) / days.length;
  return {
    stats: [
      stat("today", "Today", litres(today.water_ml), `/ ${litres(goal)} L`, left ? `${litres(left)} L to go` : "goal reached", "dataRespiratory"),
      stat("glasses", "Glasses", String(Math.round(today.water_ml / today.glass_ml)), "", `of ${today.glass_ml} ml today`, "dataRespiratory"),
      stat("avg", "7-day average", litres(avg), "L", "a day", "goalOnTrack"),
      stat("target", "On target", String(days.filter((d) => d.water_ml >= d.water_goal_ml).length), "of 7", "days at goal", "goalAchieved"),
    ],
    bars: days.map((d, i) => ({ key: dates[i], label: weekday(noonIso(dates[i]), tz), value: `${litres(d.water_ml)} L`, fraction: d.water_ml / max, onTarget: d.water_ml >= d.water_goal_ml })),
    goalFraction: goal / max,
    glasses: [
      { ml: today.glass_ml, label: `+ Glass · ${today.glass_ml} ml` },
      { ml: 500, label: "+ Bottle · 500 ml" },
      { ml: 750, label: "+ Large · 750 ml" },
    ],
    note: `Your water goal is ${litres(goal)} L a day. Water from food and drinks you don't log isn't counted.`,
    entries: entries.map((e) => ({ id: e.id, amount: `${thousands(e.ml)} ml`, when: timeOfDay(e.at, tz) })),
    entriesLine: entries.length
      ? `${entries.length} ${entries.length === 1 ? "entry" : "entries"} · ${litres(entries.reduce((n, e) => n + e.ml, 0))} L. Remove takes an entry off today's total.`
      : "Nothing logged yet today.",
  };
}

// ---------------------------------------------------------------- Nutrition › Recipes (Figma 222:2104)

export interface RecipeRowVM {
  id: string;
  title: string;
  /** "2 servings · 3 ingredients · 370 g a serving" */
  caption: string;
  kcal: string;
  recipe: Recipe;
}
export interface RecipesVM {
  rows: RecipeRowVM[];
  empty: string | null;
}

export async function loadRecipes(api: VitalLinkClient): Promise<RecipesVM> {
  const recipes = await api.recipes();
  const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
  return {
    rows: recipes.map((r) => ({
      id: r.id,
      title: r.name,
      caption: [plural(r.servings, "serving"), plural(r.items.length, "ingredient"), `${Math.round(r.per_serving.grams)} g a serving`].join(" · "),
      kcal: `${thousands(r.per_serving.kcal)} kcal a serving`,
      recipe: r,
    })),
    empty: recipes.length ? null : "No recipes yet. Add a dish you make often and log it in one step.",
  };
}
