// View-models for the tabs and Settings sections designed on Figma page 195:2 ("Web · Proposed tabs and
// sections"): Workouts › History (203:622), Training Plans (203:1226), Exercise Library (203:1805),
// Personal Records (203:2373); Nutrition › Week (204:1068), Meals (204:1545), Hydration (204:1973).
// Display strings only; screens bind them.
import type { ColorToken } from "@vitallink/tokens";
import type { VitalLinkClient } from "../client";
import { clock, dayMonth, hoursMinutes, kg, km, longDate, monthYear, thousands, timeOfDay, weekday } from "../format";
import { SHARE_SCOPES, todayIn, type Option } from "../forms";
import type { StatVM } from "../screens";
import type { ExerciseItem, NutritionDay, PersonalRecord, Share, Workout, WorkoutType } from "../types";

const stat = (key: string, label: string, value: string, unit: string, caption: string, accent: ColorToken): StatVM => ({ key, label, value, unit, caption, accent });
const dayLabel = (iso: string, tz: string) => `${weekday(iso, tz)} ${dayMonth(iso, tz)}`;

// ---------------------------------------------------------------- Workouts › History

const KIND_OF: Record<WorkoutType, "cardio" | "strength" | "mobility"> = {
  walk: "cardio", run: "cardio", cycle: "cardio", swim: "cardio", hiit: "cardio", strength: "strength", yoga: "mobility", other: "cardio",
};
const KIND_COLOR: Record<"cardio" | "strength" | "mobility", ColorToken> = { cardio: "fitnessCardio", strength: "fitnessStrength", mobility: "fitnessMobility" };

export const HISTORY_RANGES: Option[] = [
  { value: "30", label: "30 days" },
  { value: "90", label: "90 days" },
  { value: "365", label: "12 months" },
];
export const HISTORY_TYPES: Option[] = [
  { value: "all", label: "All types" },
  { value: "run", label: "Run" },
  { value: "walk", label: "Walk" },
  { value: "cycle", label: "Cycle" },
  { value: "strength", label: "Strength" },
  { value: "other", label: "Other" },
];
const matchesType = (w: Workout, type: string) =>
  type === "all" || w.type === type || (type === "other" && !["run", "walk", "cycle", "strength"].includes(w.type));

export interface HistoryRowVM {
  id: string;
  title: string;
  icon: WorkoutType;
  iconColor: ColorToken;
  /** "Wed 28 Oct · 7:05 AM · 6.2 km · 6:46 /km · 412 kcal" */
  caption: string;
  duration: string;
  metric: string;
}
export interface HistoryVM {
  stats: StatVM[];
  groups: { label: string; rows: HistoryRowVM[] }[];
  empty: string | null;
}

export async function loadWorkoutHistory(api: VitalLinkClient, days: number, type: string): Promise<HistoryVM> {
  const [me, all] = await Promise.all([api.me(), api.workouts(days)]);
  const tz = me.timezone;
  const list = all.filter((w) => matchesType(w, type)).sort((a, b) => b.started_at.localeCompare(a.started_at));
  const span = HISTORY_RANGES.find((r) => r.value === String(days))?.label ?? `${days} days`;
  const period = `last ${span}`;
  const minutes = list.reduce((n, w) => n + w.duration_s / 60, 0);
  const metres = list.reduce((n, w) => n + (w.distance_m ?? 0), 0);
  const kcal = list.reduce((n, w) => n + (w.kcal ?? 0), 0);
  const groups: HistoryVM["groups"] = [];
  for (const w of list) {
    const label = monthYear(w.started_at, tz).toUpperCase();
    let g = groups.find((x) => x.label.startsWith(label));
    if (!g) groups.push((g = { label, rows: [] }));
    const pace = w.distance_m && w.distance_m > 0 && (w.type === "run" || w.type === "walk") ? w.duration_s / (w.distance_m / 1000) : null;
    g.rows.push({
      id: w.id,
      title: w.title,
      icon: w.type,
      iconColor: KIND_COLOR[KIND_OF[w.type]],
      caption: [
        dayLabel(w.started_at, tz),
        timeOfDay(w.started_at, tz),
        w.distance_m ? km(w.distance_m) : null,
        pace ? `${clock(pace)} /km` : null,
        w.kcal !== null ? `${thousands(w.kcal)} kcal` : null,
      ]
        .filter(Boolean)
        .join(" · "),
      duration: `${Math.round(w.duration_s / 60)} min`,
      metric: w.avg_hr !== null ? `avg ${w.avg_hr} BPM` : KIND_OF[w.type] === "mobility" ? "Recovery" : "",
    });
  }
  for (const g of groups) g.label = `${g.label} · ${g.rows.length} SESSION${g.rows.length === 1 ? "" : "S"}`;
  return {
    stats: [
      stat("sessions", "Sessions", String(list.length), "", period, "goalOnTrack"),
      stat("time", "Training time", hoursMinutes(minutes), "", period, "fitnessCardio"),
      stat("distance", "Distance", (metres / 1000).toFixed(1), "km", "walks, runs and rides", "dataActivity"),
      stat("energy", "Active energy", thousands(kcal), "kcal", period, "fitnessStrength"),
    ],
    groups,
    empty: list.length ? null : `No ${type === "all" ? "" : `${HISTORY_TYPES.find((t) => t.value === type)?.label.toLowerCase()} `}sessions in the ${period}.`,
  };
}

// ---------------------------------------------------------------- Workouts › Personal Records

export interface PersonalRecordsVM {
  stats: StatVM[];
  rows: { name: string; when: string; value: string }[];
}
const recordValue = (r: PersonalRecord) =>
  r.time_s !== null ? clock(r.time_s) : r.weight_kg !== null ? `${kg(r.weight_kg)} × ${r.reps ?? 1}` : "—";

export async function loadPersonalRecords(api: VitalLinkClient): Promise<PersonalRecordsVM> {
  const [me, records] = await Promise.all([api.me(), api.personalRecords()]);
  const tz = me.timezone;
  const monthStart = `${todayIn(tz, Date.parse(api.now())).slice(0, 8)}01`;
  const lifts = records.filter((r) => r.weight_kg !== null);
  const heaviest = lifts.reduce<PersonalRecord | null>((b, r) => (b === null || r.weight_kg! > b.weight_kg! ? r : b), null);
  const five = records.find((r) => r.exercise === "5 km run");
  const thisMonth = records.filter((r) => todayIn(tz, Date.parse(r.achieved_at)) >= monthStart).length;
  return {
    stats: [
      stat("records", "Records", String(records.length), "", `across ${records.length} exercises and runs`, "goalAchieved"),
      stat("month", "This month", String(thisMonth), "new", `since 1 ${dayMonth(api.now(), tz).slice(3)}`, "goalOnTrack"),
      stat("heaviest", "Heaviest lift", heaviest ? kg(heaviest.weight_kg!).replace(" kg", "") : "—", heaviest ? "kg" : "", heaviest ? `${heaviest.exercise} × ${heaviest.reps}` : "no lifts yet", "fitnessStrength"),
      stat("fastest", "Fastest 5 km", five ? clock(five.time_s!) : "—", "", five ? dayMonth(five.achieved_at, tz) : "no 5 km run yet", "fitnessCardio"),
    ],
    rows: records.map((r) => ({ name: r.exercise, when: `Set ${dayLabel(r.achieved_at, tz)}`, value: recordValue(r) })),
  };
}

// ---------------------------------------------------------------- Workouts › Exercise Library

export const MUSCLE_LABEL: Record<ExerciseItem["primary_muscle"], string> = {
  legs: "Legs", back: "Back", chest: "Chest", shoulders: "Shoulders", arms: "Arms", core: "Core", glutes: "Glutes", full_body: "Full body",
};
export interface ExerciseLibraryVM {
  muscles: Option[];
  exercises: { id: number; name: string; muscle: string; muscleKey: string; search: string }[];
  count: string;
}
export async function loadExerciseLibrary(api: VitalLinkClient): Promise<ExerciseLibraryVM> {
  const list = [...(await api.exercises())].sort((a, b) => a.name.localeCompare(b.name));
  const present = (Object.keys(MUSCLE_LABEL) as ExerciseItem["primary_muscle"][]).filter((m) => list.some((e) => e.primary_muscle === m));
  return {
    muscles: [{ value: "all", label: "All" }, ...present.map((m) => ({ value: m, label: MUSCLE_LABEL[m] }))],
    exercises: list.map((e) => ({ id: e.id, name: e.name, muscle: MUSCLE_LABEL[e.primary_muscle], muscleKey: e.primary_muscle, search: e.name.toLowerCase() })),
    count: `${list.length} exercises`,
  };
}
/** Search by name and filter by muscle group. */
export const filterExercises = (vm: ExerciseLibraryVM, query: string, muscle: string) => {
  const q = query.trim().toLowerCase();
  return vm.exercises.filter((e) => (muscle === "all" || e.muscleKey === muscle) && (!q || e.search.includes(q)));
};

// ---------------------------------------------------------------- Workouts › Training Plans

export interface PlansVM {
  plans: {
    id: string;
    title: string;
    caption: string;
    groups: { label: string; rows: { id: string; title: string; caption: string; minutes: string; icon: WorkoutType; iconColor: ColorToken; status: string }[] }[];
  }[];
  empty: string | null;
}
export async function loadTrainingPlans(api: VitalLinkClient): Promise<PlansVM> {
  const [me, plans] = await Promise.all([api.me(), api.trainingPlans()]);
  const tz = me.timezone;
  const now = api.now();
  const order = (p: { status: string }) => (p.status === "active" ? 0 : 1);
  return {
    plans: [...plans].sort((a, b) => order(a) - order(b)).map((p) => {
      const sessions = [...p.sessions].sort((a, b) => a.starts_at.localeCompare(b.starts_at));
      const row = (s: (typeof sessions)[number], status: string) => {
        const kind = s.kind.toLowerCase() as "cardio" | "strength" | "mobility";
        return {
          id: s.id,
          title: `${s.title} · ${s.kind.charAt(0).toUpperCase()}${s.kind.slice(1).toLowerCase()}`,
          caption: [dayLabel(s.starts_at, tz), timeOfDay(s.starts_at, tz), s.exercises ? `${s.exercises} exercises` : null].filter(Boolean).join(" · "),
          minutes: `${s.minutes} min`,
          icon: (kind === "strength" ? "strength" : kind === "mobility" ? "yoga" : "run") as WorkoutType,
          iconColor: KIND_COLOR[kind] ?? "fitnessCardio",
          status,
        };
      };
      const upcoming = sessions.filter((s) => s.starts_at >= now);
      const earlier = sessions.filter((s) => s.starts_at < now).reverse();
      return {
        id: p.id,
        title: `${p.name} · ${p.status}`,
        caption: `${p.weekly_target_sessions} sessions a week · ${sessions.length} planned`,
        groups: [
          ...(upcoming.length ? [{ label: "COMING UP", rows: upcoming.map((s, i) => row(s, i === 0 ? "Next" : "Planned")) }] : []),
          ...(earlier.length ? [{ label: "EARLIER", rows: earlier.map((s) => row(s, "Past")) }] : []),
        ],
      };
    }),
    empty: plans.length ? null : "No training plan yet. Plans you set up appear here with their sessions for each week.",
  };
}

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
}
export async function loadHydration(api: VitalLinkClient): Promise<HydrationVM> {
  const { tz, dates, days } = await week(api);
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
  };
}

// ---------------------------------------------------------------- Privacy › Manage access (Figma 206:1516)

const KIND_LABEL: Record<Share["grantee_kind"], string> = { doctor: "Doctor", family: "Family", other: "Other", app: "App", hospital: "Hospital" };
export interface ManageAccessVM {
  title: string;
  rows: { id: string; who: string; line: string; until: string }[];
  empty: string | null;
}
/** Active shares, optionally only doctors or family (the Privacy Centre card that opened it). */
export async function loadManageAccess(api: VitalLinkClient, kind: "doctor" | "family" | null): Promise<ManageAccessVM> {
  const [me, shares] = await Promise.all([api.me(), api.shares()]);
  const tz = me.timezone;
  const list = shares.filter((s) => s.active && (kind === null || s.grantee_kind === kind)).sort((a, b) => a.grantee_label.localeCompare(b.grantee_label));
  const scopes = (s: Share) => SHARE_SCOPES.filter((x) => s.scopes.includes(x.value)).map((x) => x.label).join(", ");
  return {
    title: kind === "doctor" ? "Doctors with access" : kind === "family" ? "Family access" : "Everyone with access",
    rows: list.map((s) => ({
      id: s.id,
      who: s.grantee_label,
      line: `${KIND_LABEL[s.grantee_kind]} · ${scopes(s)}`,
      until: s.expires_at ? `Until ${dayMonth(s.expires_at, tz).replace(/^0/, "")} ${todayIn(tz, Date.parse(s.expires_at)).slice(0, 4)}` : "No end date",
    })),
    empty: list.length ? null : "No one has access right now.",
  };
}
