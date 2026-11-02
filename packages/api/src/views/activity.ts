// My Health › Activity: steps, workouts and movement.
import type { ColorToken } from "@vitallink/tokens";
import type { VitalLinkClient } from "../client";
import { distance as dist, distanceUnit, distanceValue, thousands, weekdayTime, type Units } from "../format";
import type { Workout, WorkoutType } from "../types";
import { type BarVM, stepBars } from "./dashboard";
import type { StatVM } from "./shared";

// ---------------------------------------------------------------- activity


export interface WorkoutVM {
  id: string;
  title: string;
  icon: WorkoutType;
  iconColor: ColorToken;
  when: string;
  duration: string;
  distance: string;
  kcal: string;
  hr: string;
  /** One-line summary used on mobile. */
  summary: string;
}

export interface ActivityVM {
  total: string;
  dailyAverage: string;
  goal: number;
  goalLabel: string;
  bars: BarVM[];
  stats: StatVM[];
  /** null until the backend serves workouts and goals (phase 5). */
  workouts: WorkoutVM[] | null;
  goals: { id: string; label: string; progress: string; fraction: number; color: ColorToken }[] | null;
  streaks: { label: string; value: string }[];
}

const WORKOUT_COLOR: Record<WorkoutType, ColorToken> = {
  walk: "fitnessOutdoor",
  run: "fitnessCardio",
  cycle: "fitnessCardio",
  strength: "fitnessStrength",
  swim: "fitnessCardio",
  hiit: "fitnessCardio",
  yoga: "fitnessMobility",
  other: "iconDefault",
};

const GOAL_COLOR: Record<string, ColorToken> = {
  steps: "dataActivity",
  exercise_minutes: "dataHeartRate",
  stand_hours: "dataBloodOxygen",
  distance: "dataActivity",
};

const workoutVM = (w: Workout, tz: string, units?: Units): WorkoutVM => {
  const when = weekdayTime(w.started_at, tz);
  const duration = `${Math.round(w.duration_s / 60)} min`;
  const distance = w.distance_m === null ? "N/A" : dist(w.distance_m, units);
  const kcal = w.kcal === null ? "N/A" : `${thousands(w.kcal)} kcal`;
  return {
    id: w.id,
    title: w.title,
    icon: w.type,
    iconColor: WORKOUT_COLOR[w.type],
    when,
    duration,
    distance,
    kcal,
    hr: w.avg_hr === null ? "N/A" : `avg ${w.avg_hr} BPM`,
    summary: [when, duration, w.distance_m === null ? null : distance, w.kcal === null ? null : kcal]
      .filter(Boolean)
      .join(" · "),
  };
};

export async function loadActivity(api: VitalLinkClient): Promise<ActivityVM> {
  const [me, steps, distance, flights, energy, exercise, workouts, goals, streaks, targets] = await Promise.all([
    api.me(),
    api.series("steps", 7),
    api.series("distance", 7, "week"),
    api.series("flights_climbed", 7, "week"),
    api.series("active_energy", 7, "week"),
    api.series("exercise_minutes", 7, "week"),
    api.workouts(7),
    api.goals(),
    api.streaks(),
    api.targets(),
  ]);
  const goal = targets.daily_steps_goal;
  const total = steps.points.reduce((a, p) => a + p.value, 0);
  const days = steps.points.length || 1;
  const met = steps.points.filter((p) => p.value >= goal).length;
  // The last 7 days can span two calendar weeks, so add up every weekly bucket.
  const one = (s: typeof distance) => s.points.reduce((a, p) => a + p.value, 0);

  return {
    total: thousands(total),
    dailyAverage: thousands(total / days),
    goal,
    goalLabel: thousands(goal),
    bars: stepBars(steps.points, goal, me.timezone),
    stats: [
      { key: "distance", label: "Distance", value: distanceValue(one(distance), me.units), unit: distanceUnit(me.units), caption: "this week", accent: "dataActivity" },
      { key: "flights", label: "Flights", value: thousands(one(flights)), unit: "floors", caption: "this week", accent: "dataActivity" },
      { key: "energy", label: "Active energy", value: thousands(one(energy)), unit: "kcal", caption: "this week", accent: "dataHeartRate" },
      { key: "exercise", label: "Exercise", value: thousands(one(exercise)), unit: "min", caption: "this week", accent: "dataHeartRate" },
      {
        key: "goal-days",
        label: "Goal days met",
        value: String(met),
        unit: `/ ${days}`,
        caption: met >= Math.ceil(days * 0.7) ? "on target" : "below target",
        accent: met >= Math.ceil(days * 0.7) ? "goalAchieved" : "goalAtRisk",
      },
    ],
    workouts: workouts && workouts.map((w) => workoutVM(w, me.timezone, me.units)),
    goals: goals && goals.map((g) => ({
      id: g.id,
      label: g.label,
      progress: `${g.current.toLocaleString("en-US")} / ${g.target.toLocaleString("en-US")}${g.unit ? ` ${g.unit}` : ""}`,
      fraction: Math.max(0, Math.min(1, g.current / g.target)),
      color: GOAL_COLOR[g.metric] ?? "dataActivity",
    })),
    streaks: (streaks ?? []).map((s) => ({ label: s.label, value: `${s.days} days` })),
  };
}
