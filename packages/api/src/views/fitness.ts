// Fitness view-models: Workouts, Nutrition, Goals (web 129:1142, 131:100, 131:644;
// mobile 134:2, 134:216, 134:395). Everything here is a display string or a 0–1 fraction.
import type { ColorToken } from "@vitallink/tokens";
import { need, type VitalLinkClient } from "../client";
import { ago, clock, dayMonth, daysFrom, hoursMinutes, kg, km, longDate, pace, relativeDay, thousands, timeOfDay, weekday } from "../format";
import type { StatVM } from "../screens";
import type { GoalStatus, SessionKind, TrainingSession, WorkoutType } from "../types";

export interface LegendVM {
  label: string;
  color: ColorToken;
}

export interface BarRowVM {
  key: string;
  label: string;
  value: string;
  /** Mobile wording where it differs. */
  shortValue: string;
  fraction: number;
  color: ColorToken;
}

const KIND_COLOR: Record<SessionKind, ColorToken> = {
  cardio: "fitnessCardio",
  strength: "fitnessStrength",
  mobility: "fitnessMobility",
};
const KIND_LABEL: Record<SessionKind, string> = { cardio: "Cardio", strength: "Strength", mobility: "Mobility" };
// Stack order from the plot (130:150): mobility sits under the main session.
const STACK: SessionKind[] = ["mobility", "strength", "cardio"];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

// ---------------------------------------------------------------- workouts

export interface LoadDayVM {
  label: string;
  total: string;
  minutes: number;
  rest: boolean;
  segments: { kind: SessionKind; minutes: number; color: ColorToken }[];
}

export interface SessionRowVM {
  id: string;
  title: string;
  icon: WorkoutType;
  iconColor: ColorToken;
  /** Web: "Sat · 7:05 PM · 6 exercises · 24 sets · 8,420 kg" */
  caption: string;
  /** Mobile: "Sat · 48 min · 24 sets · 8,420 kg" */
  summary: string;
  duration: string;
  metric: string;
  zone: string;
}

export interface WorkoutsVM {
  lifetime: string;
  subtitle: string;
  shortSubtitle: string;
  stats: StatVM[];
  mobileStats: StatVM[];
  load: LoadDayVM[];
  legend: LegendVM[];
  sessions: SessionRowVM[];
  muscles: { caption: string; groups: BarRowVM[]; note: string };
  records: { name: string; when: string; value: string }[];
  next: { title: string; detail: string } | null;
}

const sessionRow = (s: TrainingSession, tz: string): SessionRowVM => {
  const day = weekday(s.started_at, tz);
  const mins = `${Math.round(s.duration_s / 60)} min`;
  const vol = s.volume_kg === null ? null : `${thousands(s.volume_kg)} kg`;
  const dist = s.distance_m === null ? null : km(s.distance_m);
  const kcal = s.kcal === null ? null : `${thousands(s.kcal)} kcal`;
  const detail =
    s.kind === "strength"
      ? [`${s.exercises} exercises`, `${s.sets} sets`, vol]
      : s.kind === "mobility"
        ? [s.focus]
        : [dist, s.pace_s_per_km === null ? null : pace(s.pace_s_per_km), kcal];
  const short =
    s.kind === "strength" ? [`${s.sets} sets`, vol] : s.kind === "mobility" ? ["recovery"] : s.type === "run" ? [dist, kcal] : [dist];
  const metric = s.pr
    ? `PR: ${s.pr.exercise} ${kg(s.pr.weight_kg)}`
    : s.rpe !== null
      ? `RPE ${s.rpe}`
      : s.avg_hr !== null
        ? `avg ${s.avg_hr} BPM`
        : "Recovery";
  return {
    id: s.id,
    title: s.title,
    icon: s.type,
    iconColor: KIND_COLOR[s.kind],
    caption: [day, timeOfDay(s.started_at, tz), ...detail].filter(Boolean).join(" · "),
    summary: [day, mins, ...short].filter(Boolean).join(" · "),
    duration: mins,
    metric,
    zone: `Zone ${s.zone}`,
  };
};

// Muscle balance bars (130:275): a full bar is 37 sets over the 14-day window.
const FULL_SETS = 37;
const balanceColor = (f: number): ColorToken => (f >= 0.85 ? "goalAchieved" : f >= 0.5 ? "goalOnTrack" : f >= 0.3 ? "goalAtRisk" : "goalMissed");

export async function loadWorkouts(api: VitalLinkClient): Promise<WorkoutsVM> {
  const [me, data] = await Promise.all([api.me(), api.training()]);
  const t = need(data, "Training");
  const tz = me.timezone;
  const now = api.now();
  const w = t.week;
  const sessionsLine = `${w.sessions} of ${w.target_sessions} sessions`;
  const streakLine = `${w.streak_days} day streak`;

  const stat = (key: string, label: string, value: string, unit: string, caption: string, accent: ColorToken): StatVM => ({ key, label, value, unit, caption, accent });
  const sessions = stat("sessions", "Sessions", String(w.sessions), `/ ${w.target_sessions}`, "this week", "goalOnTrack");
  const streak = stat("streak", "Streak", String(w.streak_days), "days", `longest ${w.longest_streak_days}`, "goalAchieved");
  const time = hoursMinutes(w.minutes);
  const volume = thousands(w.volume_kg);

  return {
    lifetime: thousands(t.total_sessions),
    subtitle: `${sessionsLine} this week · ${streakLine} · synced from ${w.source}`,
    shortSubtitle: `${sessionsLine} · ${streakLine}`,
    stats: [
      sessions,
      stat("time", "Training time", time, "", "this week", "fitnessCardio"),
      stat("volume", "Total volume", volume, "kg", "lifted this week", "fitnessStrength"),
      stat("energy", "Active energy", thousands(w.active_kcal), "kcal", "this week", "dataActivity"),
      streak,
    ],
    mobileStats: [
      sessions,
      stat("time", "Time", time, "", "this week", "fitnessCardio"),
      stat("volume", "Volume", volume, "kg", "lifted", "fitnessStrength"),
      streak,
    ],
    load: w.load.map((day, i) => {
      const segments = STACK.filter((k) => day[k]).map((k) => ({ kind: k, minutes: day[k]!, color: KIND_COLOR[k] }));
      const minutes = segments.reduce((a, s) => a + s.minutes, 0);
      return { label: DAYS[i], minutes, rest: minutes === 0, total: minutes ? `${minutes}m` : "Rest", segments };
    }),
    legend: [
      ...(["cardio", "strength", "mobility"] as SessionKind[]).map((k) => ({ label: KIND_LABEL[k], color: KIND_COLOR[k] })),
      { label: "Rest day", color: "bgMuted" as ColorToken },
    ],
    sessions: t.sessions.map((s) => sessionRow(s, tz)),
    muscles: {
      caption: `Sets logged over ${t.muscles.days} days`,
      groups: t.muscles.groups.map((g) => {
        const fraction = Math.min(1, g.sets / FULL_SETS);
        const value = `${g.sets} sets`;
        return { key: g.group, label: g.group, value, shortValue: value, fraction, color: balanceColor(fraction) };
      }),
      note: t.muscles.note,
    },
    records: t.records.map((r) => {
      const d = daysFrom(r.achieved_at, now, tz);
      return {
        name: r.exercise,
        // This week reads as a weekday; older records as a date.
        when: d > -7 ? weekday(r.achieved_at, tz) : dayMonth(r.achieved_at, tz),
        value: r.time_s !== null ? clock(r.time_s) : `${kg(r.weight_kg ?? 0)} × ${r.reps}`,
      };
    }),
    next: t.next
      ? {
          title: `${t.next.title} · ${t.next.kind}`,
          detail: `${relativeDay(t.next.starts_at, now, tz)} · ${t.next.exercises} exercises · about ${t.next.minutes} min`,
        }
      : null,
  };
}

// ---------------------------------------------------------------- nutrition

export interface MealVM {
  id: string;
  title: string;
  items: string;
  shortItems: string;
  kcal: string;
  macros: string;
}

export interface NutritionVM {
  subtitle: string;
  shortSubtitle: string;
  stats: StatVM[];
  ring: { fraction: number; value: string; goal: string; remaining: string };
  split: { label: string; fraction: number; color: ColorToken }[];
  macros: BarRowVM[];
  meals: MealVM[];
  energy: { label: string; value: string; color: ColorToken }[];
  energyNote: string;
  hydration: { glasses: boolean[]; caption: string };
}

const litres = (ml: number) => (ml / 1000).toFixed(1);

/** Items that fit the mobile row (~26 characters), as in frame 134:216. */
const shortList = (items: string[]) => {
  const out: string[] = [];
  for (const it of items) {
    if (out.length && [...out, it].join(", ").length > 26) break;
    out.push(it);
  }
  return out.join(", ");
};

export async function loadNutrition(api: VitalLinkClient): Promise<NutritionVM> {
  const [me, data] = await Promise.all([api.me(), api.nutrition()]);
  const n = need(data, "Nutrition");
  const tz = me.timezone;
  const left = n.kcal_goal - n.kcal;
  const kcal = thousands(n.kcal);
  const goal = thousands(n.kcal_goal);

  const energy = { protein: n.protein_g * 4, carbs: n.carbs_g * 4, fat: n.fat_g * 9 };
  const energyTotal = energy.protein + energy.carbs + energy.fat;
  const pct = (v: number) => Math.round((v / energyTotal) * 100);

  const bar = (key: string, label: string, v: number, g: number, color: ColorToken): BarRowVM => ({
    key,
    label,
    value: `${v} g of ${g} g`,
    shortValue: `${v} / ${g} g`,
    fraction: Math.min(1, v / g),
    color,
  });
  const net = n.kcal - (n.base_burn_kcal ?? 0) - n.active_burn_kcal;
  const weeklyKg = (Math.abs(net) * 7) / 7700;
  const onTarget = Math.sign(net) === Math.sign(n.weekly_target_kg) && weeklyKg >= Math.abs(n.weekly_target_kg) * 0.9;
  const glasses = Math.round(n.water_goal_ml / n.glass_ml);
  const drunk = Math.round(n.water_ml / n.glass_ml);

  return {
    subtitle: `${longDate(n.date, tz)} · ${kcal} of ${goal} kcal · ${thousands(left)} remaining`,
    shortSubtitle: `${kcal} of ${goal} kcal · ${thousands(left)} left`,
    stats: [
      { key: "kcal", label: "Calories", value: kcal, unit: `/ ${goal}`, caption: `${thousands(left)} remaining`, accent: "dataNutrition" },
      { key: "protein", label: "Protein", value: String(n.protein_g), unit: "g", caption: `goal ${n.goals.protein_g} g`, accent: "macroProtein" },
      { key: "carbs", label: "Carbs", value: String(n.carbs_g), unit: "g", caption: `goal ${n.goals.carbs_g} g`, accent: "macroCarbs" },
      { key: "fat", label: "Fat", value: String(n.fat_g), unit: "g", caption: `goal ${n.goals.fat_g} g`, accent: "macroFat" },
      { key: "water", label: "Water", value: litres(n.water_ml), unit: "L", caption: `goal ${litres(n.water_goal_ml)} L`, accent: "dataBloodOxygen" },
    ],
    ring: { fraction: Math.min(1, n.kcal / n.kcal_goal), value: kcal, goal: `/ ${goal}`, remaining: `${thousands(left)} kcal remaining` },
    split: [
      { label: `Protein ${pct(energy.protein)}%`, fraction: energy.protein / energyTotal, color: "macroProtein" },
      { label: `Carbs ${pct(energy.carbs)}%`, fraction: energy.carbs / energyTotal, color: "macroCarbs" },
      { label: `Fat ${pct(energy.fat)}%`, fraction: energy.fat / energyTotal, color: "macroFat" },
    ],
    macros: [
      bar("protein", "Protein", n.protein_g, n.goals.protein_g, "macroProtein"),
      { ...bar("carbs", "Carbohydrate", n.carbs_g, n.goals.carbs_g, "macroCarbs") },
      bar("fat", "Fat", n.fat_g, n.goals.fat_g, "macroFat"),
      bar("fibre", "Fibre", n.fibre_g, n.goals.fibre_g, "macroFibre"),
    ],
    meals: n.meals.map((m) => {
      const items = m.items.join(", ");
      return {
        id: m.id,
        title: `${m.name} · ${timeOfDay(m.eaten_at, tz)}`,
        items: items[0].toUpperCase() + items.slice(1),
        shortItems: shortList(m.items),
        kcal: `${m.kcal} kcal`,
        macros: `${m.protein_g}P · ${m.carbs_g}C · ${m.fat_g}F`,
      };
    }),
    energy: [
      { label: "Consumed", value: `${kcal} kcal`, color: "dataNutrition" },
      { label: "Base burn", value: n.base_burn_kcal === null ? "—" : `${thousands(n.base_burn_kcal)} kcal`, color: "textSecondary" },
      { label: "Active burn", value: `${thousands(n.active_burn_kcal)} kcal`, color: "dataActivity" },
      { label: "Net", value: `${net < 0 ? "−" : "+"}${thousands(Math.abs(net))} kcal`, color: onTarget ? "goalAchieved" : "goalAtRisk" },
    ],
    energyNote: n.base_burn_kcal === null
      ? "Add your height, weight, birth date and sex in Settings to estimate your energy balance."
      : `A ${thousands(Math.abs(net))} kcal ${net < 0 ? "deficit" : "surplus"} is ${onTarget ? "on track" : "off track"} for your ${Math.abs(n.weekly_target_kg)} kg per week target.`,
    hydration: {
      glasses: Array.from({ length: glasses }, (_, i) => i < drunk),
      caption: `${litres(n.water_ml)} L of ${litres(n.water_goal_ml)} L · ${drunk} of ${glasses} glasses`,
    },
  };
}

// ---------------------------------------------------------------- goals

export interface GoalRowVM {
  id: string;
  title: string;
  shortTitle: string;
  caption: string;
  shortCaption: string;
  fraction: number;
  color: ColorToken;
  status: string;
}

export interface GoalsVM {
  subtitle: string;
  shortSubtitle: string;
  stats: StatVM[];
  mobileStats: StatVM[];
  goals: GoalRowVM[];
  consistency: { weeks: boolean[][]; caption: string };
  recent: { title: string; when: string }[];
}

const STATUS: Record<GoalStatus, [string, ColorToken]> = {
  on_track: ["On track", "goalOnTrack"],
  at_risk: ["At risk", "goalAtRisk"],
  achieved: ["Achieved", "goalAchieved"],
  paused: ["Paused", "goalNotStarted"],
};
const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

export async function loadGoals(api: VitalLinkClient): Promise<GoalsVM> {
  const data = need(await api.goalsOverview(), "Goals");
  const now = api.now();
  const count = (s: GoalStatus) => data.goals.filter((g) => g.status === s).length;
  const onTrack = count("on_track");
  const atRisk = count("at_risk");
  const active = data.goals.filter((g) => g.status !== "paused").length;
  const areas = new Set(data.goals.map((g) => g.area)).size;

  const days = data.consistency.flat();
  const met = days.filter(Boolean).length;
  const byDay = WEEKDAYS.map((_, d) => data.consistency.filter((w) => w[d]).length);
  const weakest = WEEKDAYS[byDay.indexOf(Math.min(...byDay))];

  const stat = (key: string, label: string, value: string, unit: string, caption: string, accent: ColorToken): StatVM => ({ key, label, value, unit, caption, accent });
  const s = {
    onTrack: stat("on-track", "On track", String(onTrack), "", "meeting target", "goalOnTrack"),
    atRisk: stat("at-risk", "At risk", String(atRisk), "", "behind pace", "goalAtRisk"),
    achieved: stat("achieved", "Achieved", String(data.achieved_this_month), "", "this month", "goalAchieved"),
  };

  return {
    subtitle: `${active} active goals · ${data.achieved_this_month} achieved this month · ${atRisk} at risk`,
    shortSubtitle: `${active} active · ${onTrack} on track · ${atRisk} at risk`,
    stats: [
      stat("active", "Active goals", String(active), "", `across ${areas} areas`, "goalOnTrack"),
      s.onTrack,
      s.atRisk,
      s.achieved,
      stat("streak", "Longest streak", String(data.longest_streak.days), "days", data.longest_streak.label, "goalAchieved"),
    ],
    mobileStats: [s.onTrack, s.atRisk, s.achieved, stat("streak", "Streak", String(data.longest_streak.days), "days", data.longest_streak.label, "goalAchieved")],
    goals: data.goals.map((g) => {
      const [status, color] = STATUS[g.status];
      return {
        id: g.id,
        title: g.title,
        shortTitle: g.short_title,
        caption: `${g.area} · ${g.detail}`,
        shortCaption: g.short_detail,
        fraction: Math.max(0, Math.min(1, g.progress)),
        color,
        status,
      };
    }),
    consistency: {
      weeks: data.consistency,
      caption: `${met} of ${days.length} days met. ${weakest}s are your weakest day.`,
    },
    recent: data.recent.map((r) => ({ title: r.title, when: ago(r.achieved_at, now, "long") })),
  };
}
