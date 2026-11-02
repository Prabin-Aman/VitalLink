// Workouts tabs (Figma 195:2): History (203:622), Training Plans (203:1226), Exercise Library (203:1805),
// Personal Records (203:2373).
import type { ColorToken } from "@vitallink/tokens";
import type { VitalLinkClient } from "../client";
import { clock, dayMonth, distance, distanceUnit, distanceValue, hoursMinutes, kg, monthYear, pace, thousands, timeOfDay } from "../format";
import { type Option, todayIn } from "../forms";
import type { ExerciseItem, PersonalRecord, Workout, WorkoutType } from "../types";
import { dayLabel, stat, type StatVM } from "./shared";

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
    const perKm = w.distance_m && w.distance_m > 0 && (w.type === "run" || w.type === "walk") ? w.duration_s / (w.distance_m / 1000) : null;
    g.rows.push({
      id: w.id,
      title: w.title,
      icon: w.type,
      iconColor: KIND_COLOR[KIND_OF[w.type]],
      caption: [
        dayLabel(w.started_at, tz),
        timeOfDay(w.started_at, tz),
        w.distance_m ? distance(w.distance_m, me.units) : null,
        perKm ? pace(perKm, me.units) : null,
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
      stat("distance", "Distance", distanceValue(metres, me.units), distanceUnit(me.units), "walks, runs and rides", "dataActivity"),
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
  r.time_s !== null ? clock(r.time_s) : r.weight_kg !== null ? `${kg(r.weight_kg)} × ${r.reps ?? 1}` : "N/A";

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
      stat("heaviest", "Heaviest lift", heaviest ? kg(heaviest.weight_kg!).replace(" kg", "") : "N/A", heaviest ? "kg" : "", heaviest ? `${heaviest.exercise} × ${heaviest.reps}` : "no lifts yet", "fitnessStrength"),
      stat("fastest", "Fastest 5 km", five ? clock(five.time_s!) : "N/A", "", five ? dayMonth(five.achieved_at, tz) : "no 5 km run yet", "fitnessCardio"),
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
