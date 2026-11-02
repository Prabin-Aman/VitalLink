// My Health view-models, one per tab (web 77:512 … 81:834; mobile 86:56, 87:374).
import type { ColorToken } from "@vitallink/tokens";
import { need, type VitalLinkClient } from "../client";
import { ago, dayMonth, daysFrom, hoursMinutes, longDate, thousands, timeOfDay, weekday } from "../format";
import type { ChipVM, MetricCardVM, StatVM } from "./shared";
import type { Insight, Reading, SleepNight } from "../types";
import type { LegendVM } from "./fitness";

// ---------------------------------------------------------------- shared

const META: Record<string, { label: string; accent: ColorToken }> = {
  steps: { label: "Steps", accent: "dataActivity" },
  walking_distance: { label: "Walking Distance", accent: "dataActivity" },
  flights: { label: "Flights Climbed", accent: "dataActivity" },
  active_energy: { label: "Active Energy", accent: "dataActivity" },
  exercise_minutes: { label: "Exercise Minutes", accent: "dataHeartRate" },
  walking_speed: { label: "Walking Speed", accent: "dataActivity" },
  heart_rate: { label: "Heart rate", accent: "dataHeartRate" },
  resting_hr: { label: "Resting Heart Rate", accent: "dataHeartRate" },
  hrv: { label: "Heart Rate Variability", accent: "dataHeartRate" },
  walking_hr: { label: "Walking HR", accent: "dataHeartRate" },
  recovery_hr: { label: "Recovery HR", accent: "goalAchieved" },
  vo2max: { label: "Cardio Fitness", accent: "dataHeartRate" },
  blood_pressure: { label: "Blood Pressure", accent: "dataBloodPressure" },
  spo2: { label: "Blood Oxygen", accent: "dataBloodOxygen" },
  spo2_low: { label: "Lowest overnight", accent: "goalAchieved" },
  respiratory_rate: { label: "Respiratory Rate", accent: "dataRespiratory" },
  breathing_variability: { label: "Breathing variability", accent: "goalAchieved" },
  temperature: { label: "Temperature", accent: "dataTemperature" },
  wrist_temp: { label: "Wrist Temperature", accent: "dataTemperature" },
  glucose: { label: "Blood glucose", accent: "dataGlucose" },
  weight: { label: "Weight", accent: "dataWeight" },
  bmi: { label: "BMI", accent: "dataWeight" },
  body_fat: { label: "Body Fat", accent: "dataWeight" },
  muscle_mass: { label: "Muscle mass", accent: "goalAchieved" },
  waist: { label: "Waist", accent: "goalAchieved" },
  sleep: { label: "Time Asleep", accent: "dataSleep" },
  deep_sleep: { label: "Deep Sleep", accent: "dataSleep" },
  sleep_quality: { label: "Sleep quality", accent: "goalAtRisk" },
  screen_time: { label: "Screen Time", accent: "dataNutrition" },
  pickups: { label: "Phone Pickups", accent: "dataNutrition" },
  first_pickup: { label: "First pickup", accent: "dataNutrition" },
  notifications: { label: "Notifications", accent: "rangeBorderline" },
  late_use: { label: "After 10 PM", accent: "rangeHigh" },
  longest_use: { label: "Longest use", accent: "dataNutrition" },
  mindful_minutes: { label: "Mindful Minutes", accent: "dataSleep" },
  headphone_audio: { label: "Headphone Audio", accent: "dataRespiratory" },
  mood: { label: "Mood this week", accent: "goalAchieved" },
  time_outdoors: { label: "Time outdoors", accent: "fitnessOutdoor" },
};

const PERIOD: Record<NonNullable<Reading["period"]>, string> = { overnight: "Overnight", last_night: "Last night", today: "Today", now: "Just now" };

// Continuously synced activity totals read as "12 min ago"; spot readings show their time.
const CONTINUOUS = new Set(["steps", "walking_distance", "flights", "active_energy", "exercise_minutes"]);

/** "12 min ago" (activity, within the hour), a time today, "Yesterday", else "11 Sep". */
function when(r: Reading, now: string, tz: string): string {
  if (r.period) return PERIOD[r.period];
  if (!r.at) return "";
  const mins = (Date.parse(now) - Date.parse(r.at)) / 60000;
  if (CONTINUOUS.has(r.metric) && mins >= 0 && mins < 60) return ago(r.at, now);
  const d = daysFrom(r.at, now, tz);
  if (d === 0) return timeOfDay(r.at, tz);
  if (d === -1) return "Yesterday";
  return dayMonth(r.at, tz).replace(/^0/, "");
}

/** Value and unit as the cards show them. */
function show(r: Reading, tz: string): { value: string; unit: string } {
  if (r.unit === "duration") return { value: hoursMinutes(r.value), unit: "" };
  if (r.unit === "time" && r.at) {
    const [t, ampm] = timeOfDay(r.at, tz).split(" ");
    return { value: t, unit: ampm };
  }
  if (r.metric === "blood_pressure") return { value: `${r.value}/${r.value2}`, unit: r.unit };
  if (r.metric === "wrist_temp") return { value: `${r.value >= 0 ? "+" : "−"}${Math.abs(r.value).toFixed(1)}`, unit: r.unit };
  if (r.metric === "breathing_variability") return { value: "Low", unit: "" };
  const value = Number.isInteger(r.value) ? thousands(r.value) : String(r.value);
  return { value, unit: r.unit };
}

const okChip = (label: string): ChipVM => ({ label, fg: "rangeNormal", bg: "rangeNormalBg" });

const card = (r: Reading, now: string, tz: string): MetricCardVM => {
  const m = META[r.metric] ?? { label: r.metric, accent: "dataComparison" as ColorToken };
  const v = show(r, tz);
  return { key: r.metric, label: m.label, shortLabel: m.label, value: v.value, unit: v.unit, accent: m.accent, chip: okChip(r.assessment), when: when(r, now, tz) };
};

type StatSpec = { metric: string; label?: string; accent?: ColorToken; caption?: "assessment" | "assessment+when" };

const stat = (readings: Reading[], s: StatSpec, now: string, tz: string): StatVM => {
  const r = readings.find((x) => x.metric === s.metric);
  const m = META[s.metric] ?? { label: s.metric, accent: "dataComparison" as ColorToken };
  // Live accounts don't have every reading: show a dash rather than a made-up number.
  if (!r) return { key: s.metric, label: s.label ?? m.label, value: "N/A", unit: "", caption: "No data yet", accent: s.accent ?? m.accent };
  const v = show(r, tz);
  const w = when(r, now, tz);
  return {
    key: r.metric,
    label: s.label ?? m.label,
    value: v.value,
    unit: v.unit,
    // Relative words read lower-case mid-sentence ("Normal · overnight"); times keep AM/PM.
    caption: s.caption === "assessment+when" && w ? `${r.assessment} · ${/^\d/.test(w) ? w : w.toLowerCase()}` : r.assessment,
    accent: s.accent ?? m.accent,
  };
};

export interface InsightVM {
  text: string;
  color: ColorToken;
}

const insight = (i: Insight): InsightVM => ({ text: i.text, color: i.tone === "positive" ? "goalAchieved" : "goalAtRisk" });

export interface LineSeriesVM {
  values: number[];
  color: ColorToken;
  /** Value at the top and bottom of the plot. */
  domain: [number, number];
}

export interface LineChartVM {
  series: LineSeriesVM[];
  band: [number, number] | null;
  legend: LegendVM[];
}

const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

// ---------------------------------------------------------------- overview

export interface HealthOverviewVM {
  subtitle: string;
  shortSubtitle: string;
  /** Mobile 86:56: three shorter sections with abbreviated labels. */
  mobileSections: { title: string; to: string; cards: MetricCardVM[] }[];
  rings: { label: string; value: string; goal: string; fraction: number; color: ColorToken }[];
  ringsSource: string;
  sections: { title: string; sources: string; cards: MetricCardVM[] }[];
  sources: { name: string; data: string; color: ColorToken }[];
  insights: InsightVM[];
}

const SHORT_LABEL: Record<string, string> = {
  walking_distance: "Distance",
  flights: "Flights",
  resting_hr: "Resting HR",
  hrv: "HRV",
  respiratory_rate: "Resp. Rate",
  pickups: "Pickups",
};
const SHORT_WHEN: Record<string, string> = { Overnight: "O/night", "Just now": "Now" };
const shortAssessment = (s: string) => s.replace("Above average", "Above avg").replace(/(\d+) min$/, "$1m");

const MOBILE_SECTIONS = [
  { title: "Activity", to: "/health/activity", metrics: ["steps", "walking_distance", "flights", "active_energy"] },
  { title: "Heart & respiratory", to: "/health", metrics: ["resting_hr", "hrv", "spo2", "respiratory_rate"] },
  { title: "Sleep & digital", to: "/health/digital", metrics: ["sleep", "deep_sleep", "screen_time", "pickups"] },
];

const shortCard = (m: MetricCardVM): MetricCardVM => ({
  ...m,
  shortLabel: SHORT_LABEL[m.key] ?? m.label,
  when: SHORT_WHEN[m.when] ?? m.when.replace(/^(\d+) min ago$/, "$1m"),
  chip: { ...m.chip, label: shortAssessment(m.chip.label) },
});

export async function loadHealthOverview(api: VitalLinkClient): Promise<HealthOverviewVM> {
  const [me, data] = await Promise.all([api.me(), api.healthOverview()]);
  const o = need(data, "My Health");
  const tz = me.timezone;
  const now = api.now();
  const byKey = new Map(o.readings.map((r) => [r.metric, r]));
  const monitors = o.sources.filter((s) => /scale|oximeter|dexcom|monitor/i.test(s.name)).length;
  const ring = (label: string, [v, g]: [number, number], unit: string, color: ColorToken) => ({ label, value: String(v), goal: `/ ${g} ${unit}`, fraction: Math.min(1, v / g), color });
  return {
    subtitle: `${longDate(now, tz)} · syncing from Apple Watch, iPhone and ${monitors} home monitors`,
    shortSubtitle: `Syncing from Apple Watch & iPhone · ${ago(o.rings.synced_at, now)}`,
    rings: [ring("MOVE", o.rings.move, "kcal", "dataActivity"), ring("EXERCISE", o.rings.exercise, "min", "dataHeartRate"), ring("STAND", o.rings.stand, "hr", "dataBloodOxygen")],
    ringsSource: `Synced from ${o.rings.source} · ${ago(o.rings.synced_at, now).replace(" min ", " minutes ")}`,
    sections: o.sections.map((s) => ({ title: s.title, sources: s.sources, cards: s.metrics.map((k) => byKey.get(k)).filter((r): r is Reading => !!r).map((r) => card(r, now, tz)) })),
    mobileSections: MOBILE_SECTIONS.map((s) => ({ title: s.title, to: s.to, cards: s.metrics.map((k) => byKey.get(k)).filter((r): r is Reading => !!r).map((r) => shortCard(card(r, now, tz))) })),
    sources: o.sources.map((s) => ({ name: s.name, data: s.data, color: s.ok ? "rangeNormal" : "rangeBorderline" })),
    insights: o.insights.map(insight),
  };
}

// ---------------------------------------------------------------- vitals

export interface VitalsVM {
  subtitle: string;
  stats: StatVM[];
  bp: LineChartVM;
  latest: { label: string; value: string }[];
  sources: { label: string; value: string; color: ColorToken }[];
}

export async function loadVitals(api: VitalLinkClient): Promise<VitalsVM> {
  const [me, data] = await Promise.all([api.me(), api.vitals()]);
  const v = need(data, "Vitals");
  const tz = me.timezone;
  const now = api.now();
  const R = v.readings;
  const get = (k: string) => R.find((r) => r.metric === k);
  const line = (k: string, label: string) => {
    const r = get(k);
    if (!r) return { label, value: "N/A" };
    const s = show(r, tz);
    const unit = s.unit === "%" ? "%" : s.unit ? ` ${s.unit}` : "";
    return { label, value: `${s.value}${unit} · ${when(r, now, tz).replace("Yesterday", "yesterday")}` };
  };
  const sys = v.bp30.systolic;
  const dia = v.bp30.diastolic;
  return {
    subtitle: `Every measured value in one place · today at ${timeOfDay(v.measured_at, tz)}`,
    stats: [
      stat(R, { metric: "heart_rate", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "blood_pressure", label: "Blood pressure", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "spo2", label: "Blood oxygen", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "temperature", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "respiratory_rate", label: "Respiratory rate", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "glucose", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "weight", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "bmi", accent: "goalAchieved", caption: "assessment+when" }, now, tz),
    ],
    bp: {
      series: [
        { values: sys, color: "dataBloodPressure", domain: [140, 60] },
        { values: dia, color: "dataHeartRate", domain: [140, 60] },
      ],
      band: [120, 80],
      legend: [
        { label: `Systolic ${sys[sys.length - 1] ?? "N/A"}`, color: "dataBloodPressure" },
        { label: `Diastolic ${dia[dia.length - 1] ?? "N/A"}`, color: "dataHeartRate" },
        { label: "Healthy range", color: "rangeNormal" },
      ],
    },
    latest: [line("heart_rate", "Heart rate"), line("blood_pressure", "Blood pressure"), line("spo2", "Blood oxygen"), line("glucose", "Glucose"), line("weight", "Weight")],
    sources: v.sources.map((s) => ({ label: s.device, value: s.data, color: "goalAchieved" })),
  };
}

// ---------------------------------------------------------------- sleep

export interface SleepVM {
  headline: string;
  detail: string;
  detailColor: ColorToken;
  days: { label: string; total: string; segments: { value: number; color: ColorToken }[] }[];
  legend: LegendVM[];
  stats: StatVM[];
  consistency: { label: string; value: string; color?: ColorToken }[];
  insights: InsightVM[];
}

const STAGES: { key: keyof SleepNight; label: string; color: ColorToken }[] = [
  { key: "awake", label: "Awake", color: "bgMuted" },
  { key: "rem", label: "REM", color: "fitnessMobility" },
  { key: "core", label: "Core", color: "fitnessRecovery" },
  { key: "deep", label: "Deep", color: "dataSleep" },
];

export async function loadSleep(api: VitalLinkClient): Promise<SleepVM> {
  const [me, data] = await Promise.all([api.me(), api.sleep()]);
  const s = need(data, "Sleep");
  const tz = me.timezone;
  const l = s.last ?? { asleep_min: 0, start: "", end: "", goal_min: 480, deep: 0, rem: 0, awake: 0, wakeups: 0, score: 0 };
  const recorded = s.last !== null;
  const short = l.goal_min - l.asleep_min;
  const pct = (m: number) => (l.asleep_min ? `${Math.round((m / l.asleep_min) * 100)}% of night` : "N/A");
  const st = (key: string, label: string, value: string, unit: string, caption: string, accent: ColorToken): StatVM => ({ key, label, value, unit, caption, accent });
  return {
    headline: recorded ? hoursMinutes(l.asleep_min) : "N/A",
    detail: recorded
      ? `asleep · ${timeOfDay(l.start, tz)} to ${timeOfDay(l.end, tz)}${short > 0 ? ` · ${short} min short of your ${l.goal_min / 60} hour goal` : " · goal met"}`
      : "No sleep recorded last night",
    detailColor: recorded && short <= 0 ? "rangeNormal" : "rangeBorderline",
    days: s.week.map((n, i) => ({
      label: DAYS[i],
      total: hoursMinutes(n.deep + n.core + n.rem + n.awake),
      segments: STAGES.map((x) => ({ value: n[x.key], color: x.color })),
    })),
    legend: [...STAGES].reverse().map((x) => ({ label: x.label, color: x.color })),
    stats: [
      st("asleep", "Time asleep", hoursMinutes(l.asleep_min), "", `goal ${l.goal_min / 60}h`, "dataSleep"),
      st("deep", "Deep", hoursMinutes(l.deep), "", pct(l.deep), "dataSleep"),
      st("rem", "REM", hoursMinutes(l.rem), "", pct(l.rem), "fitnessMobility"),
      st("awake", "Awake", String(l.awake), "min", `${l.wakeups} wake-ups`, "goalAtRisk"),
      st("score", "Sleep score", String(l.score), "/ 100", l.score >= 80 ? "good" : l.score >= 60 ? "fair" : "low", "goalAtRisk"),
    ],
    consistency: s.consistency
      ? [
          { label: "Average bedtime", value: s.consistency.bedtime },
          { label: "Average wake", value: s.consistency.wake },
          { label: "Bedtime variance", value: `± ${s.consistency.variance_min} min`, color: s.consistency.variance_min > 30 ? "goalAtRisk" : "goalAchieved" },
          { label: "Most consistent", value: s.consistency.steadiest, color: "goalAchieved" },
        ]
      : [{ label: "Bedtime consistency", value: "Needs 3 nights of sleep data" }],
    insights: s.insights.map(insight),
  };
}

// ---------------------------------------------------------------- heart

export interface HeartVM {
  value: string;
  detail: string;
  chart: LineChartVM;
  stats: StatVM[];
  zones: { key: string; label: string; range: string; time: string; fraction: number; color: ColorToken }[];
  alerts: { label: string; value: string; color: ColorToken }[];
}

const ZONE_COLOR: ColorToken[] = ["intensityLight", "intensityLight", "intensityModerate", "intensityVigorous", "intensityPeak"];

export async function loadHeart(api: VitalLinkClient): Promise<HeartVM> {
  const [me, data] = await Promise.all([api.me(), api.heart()]);
  const h = need(data, "Heart");
  const tz = me.timezone;
  const now = api.now();
  const R = h.readings;
  const maxZone = Math.max(1, ...h.zones.map((z) => z.minutes));
  const d = h.resting?.delta_90d ?? 0;
  return {
    value: h.resting ? String(h.resting.value) : "N/A",
    detail: !h.resting
      ? "No resting heart rate yet"
      : d === 0
        ? "level with your 90 day average"
        : `${d < 0 ? "down" : "up"} ${Math.abs(d)} BPM from your 90 day average${d < 0 ? ", typical of improving aerobic fitness" : ""}`,
    chart: {
      series: [{ values: h.resting?.trend ?? [], color: "dataHeartRate", domain: [75, 45] }],
      band: [68, 52],
      legend: [
        { label: "Resting heart rate", color: "dataHeartRate" },
        { label: "Typical healthy range", color: "rangeNormal" },
      ],
    },
    stats: [
      stat(R, { metric: "resting_hr", label: "Resting HR" }, now, tz),
      stat(R, { metric: "hrv", label: "HRV" }, now, tz),
      stat(R, { metric: "walking_hr" }, now, tz),
      stat(R, { metric: "vo2max", label: "Cardio fitness", accent: "goalAchieved" }, now, tz),
      stat(R, { metric: "recovery_hr" }, now, tz),
    ],
    // Bars share one scale: the longest zone fills 34% of the track (Figma 133:1543).
    zones: h.zones.map((z) => ({
      key: String(z.zone),
      label: `Zone ${z.zone} · ${z.name}`,
      range: z.range,
      time: hoursMinutes(z.minutes, true),
      fraction: (z.minutes / maxZone) * 0.34,
      color: ZONE_COLOR[z.zone - 1],
    })),
    alerts: h.alerts.map((a) => ({ ...a, color: "goalAchieved" })),
  };
}

// ---------------------------------------------------------------- respiratory

export interface RespiratoryVM {
  stats: StatVM[];
  chart: LineChartVM;
  aerobic: { label: string; value: string; color?: ColorToken }[];
  alerts: { label: string; value: string; color: ColorToken }[];
}

export async function loadRespiratory(api: VitalLinkClient): Promise<RespiratoryVM> {
  const [me, data] = await Promise.all([api.me(), api.respiratory()]);
  const x = need(data, "Respiratory");
  const tz = me.timezone;
  const now = api.now();
  const R = x.readings;
  const change = x.aerobic ? x.aerobic.current - x.aerobic.six_months_ago : 0;
  return {
    stats: [
      stat(R, { metric: "spo2", label: "Blood oxygen", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "respiratory_rate", label: "Respiratory rate", caption: "assessment+when" }, now, tz),
      stat(R, { metric: "vo2max", label: "Cardio fitness", accent: "goalAchieved" }, now, tz),
      stat(R, { metric: "spo2_low" }, now, tz),
      stat(R, { metric: "breathing_variability" }, now, tz),
    ],
    chart: {
      series: [{ values: x.overnight_spo2, color: "dataBloodOxygen", domain: [100, 92] }],
      band: [100, 95],
      legend: [
        { label: "Blood oxygen", color: "dataBloodOxygen" },
        { label: "Typical range", color: "rangeNormal" },
      ],
    },
    aerobic: x.aerobic
      ? [
          { label: "Current VO₂max", value: String(x.aerobic.current), color: "goalAchieved" },
          { label: "6 months ago", value: String(x.aerobic.six_months_ago) },
          { label: "Change", value: `${change >= 0 ? "+" : "−"}${Math.abs(change).toFixed(1)}`, color: change >= 0 ? "goalAchieved" : "goalAtRisk" },
          { label: "Category", value: x.aerobic.category, color: "goalAchieved" },
        ]
      : [{ label: "Current VO₂max", value: "N/A" }],
    alerts: x.alerts.map((a) => ({ ...a, color: "goalAchieved" })),
  };
}

// ---------------------------------------------------------------- body

export interface BodyVM {
  stats: StatVM[];
  chart: LineChartVM;
  chartCaption: string;
  measurements: { name: string; current: string; previous: string; change: string; changeColor: ColorToken; taken: string }[];
  goal: { now: string; target: string; fraction: number; caption: string };
}

export async function loadBody(api: VitalLinkClient): Promise<BodyVM> {
  const [me, data] = await Promise.all([api.me(), api.body()]);
  const b = need(data, "Body composition");
  const tz = me.timezone;
  const now = api.now();
  const R = b.readings;
  const w = b.weekly.weight;
  const f = b.weekly.body_fat;
  const span = (vals: number[]): [number, number] => {
    const hi = Math.max(...vals);
    const lo = Math.min(...vals);
    const pad = (hi - lo) * 0.6;
    return [hi + pad * 0.4, lo - pad];
  };
  const lost = w.length ? w[0] - w[w.length - 1] : 0;
  const g = b.body_fat_goal;
  const toGo = g ? g.current - g.target : 0;
  return {
    stats: [
      stat(R, { metric: "weight" }, now, tz),
      stat(R, { metric: "bmi", accent: "goalAchieved" }, now, tz),
      stat(R, { metric: "body_fat", label: "Body fat", accent: "goalAtRisk" }, now, tz),
      stat(R, { metric: "muscle_mass" }, now, tz),
      stat(R, { metric: "waist" }, now, tz),
    ],
    chart: {
      series: [
        { values: w, color: "dataWeight", domain: span(w) },
        { values: f, color: "macroFat", domain: span(f) },
      ],
      band: null,
      legend: [
        { label: "Weight (kg)", color: "dataWeight" },
        { label: "Body fat (%)", color: "macroFat" },
      ],
    },
    chartCaption: !w.length
      ? "Weigh in to see your trend."
      : b.muscle_change_kg > 0 && lost > 0
        ? `Weight down ${Math.abs(lost).toFixed(1)} kg while muscle mass rose ${b.muscle_change_kg} kg, so the change is mostly fat.`
        : `Weight ${lost >= 0 ? "down" : "up"} ${Math.abs(lost).toFixed(1)} kg over ${w.length} weeks.`,
    measurements: b.measurements.map((m) => {
      const d = m.current - m.previous;
      return {
        name: m.name,
        current: `${m.current} ${m.unit}`,
        previous: `${m.previous} ${m.unit}`,
        change: `${d > 0 ? "+" : d < 0 ? "−" : "±"}${Math.abs(d)} ${m.unit}`,
        changeColor: "goalAchieved",
        taken: dayMonth(m.taken_at, tz).replace(/^0/, ""),
      };
    }),
    goal: g
      ? {
          now: `${g.current}% now`,
          target: `target ${g.target}%`,
          fraction: g.start === g.target ? 1 : Math.max(0, Math.min(1, (g.start - g.current) / (g.start - g.target))),
          caption:
            toGo <= 0
              ? "Target reached."
              : `${toGo.toFixed(1)} points to go${g.weeks_to_go ? ` · at the current rate, about ${g.weeks_to_go} weeks.` : "."}`,
        }
      : { now: "N/A", target: "No body fat goal", fraction: 0, caption: "Set a body fat goal in Goals to track it here." },
  };
}

// ---------------------------------------------------------------- mental

export interface MentalVM {
  stats: StatVM[];
  days: { label: string; mood: string; color: ColorToken }[];
  mindful: { label: string; value: string; color?: ColorToken }[];
  insights: InsightVM[];
}

const MOOD: Record<string, [string, ColorToken, number]> = {
  great: ["Great", "goalAchieved", 4],
  good: ["Good", "goalOnTrack", 3],
  okay: ["Okay", "goalAtRisk", 2],
  low: ["Low", "goalMissed", 1],
};

export async function loadMental(api: VitalLinkClient): Promise<MentalVM> {
  const [me, data] = await Promise.all([api.me(), api.mental()]);
  const m = need(data, "Mental wellbeing");
  const tz = me.timezone;
  const now = api.now();
  const logged = m.mood.filter((x): x is NonNullable<typeof x> => x !== null);
  const avg = logged.reduce((a, x) => a + MOOD[x][2], 0) / (logged.length || 1);
  const overall = Object.values(MOOD).find((x) => x[2] === Math.round(avg))?.[0] ?? "N/A";
  const R = m.readings;
  return {
    stats: [
      { key: "mood", label: "Mood this week", value: overall, unit: "", caption: `${logged.length} of ${m.mood.length} days logged`, accent: "goalAchieved" },
      stat(R, { metric: "mindful_minutes", label: "Mindful minutes", accent: "goalAtRisk" }, now, tz),
      stat(R, { metric: "hrv", label: "HRV" }, now, tz),
      stat(R, { metric: "sleep_quality" }, now, tz),
      stat(R, { metric: "time_outdoors" }, now, tz),
    ],
    days: m.mood.map((x, i) => (x ? { label: DAYS[i], mood: MOOD[x][0], color: MOOD[x][1] } : { label: DAYS[i], mood: "Not logged", color: "bgMuted" })),
    mindful: [
      { label: "Sessions this week", value: String(m.mindful.sessions) },
      { label: "Total minutes", value: `${m.mindful.minutes} min`, color: m.mindful.minutes < m.mindful.goal ? "goalAtRisk" : "goalAchieved" },
      { label: "Weekly goal", value: `${m.mindful.goal} min` },
      { label: "Longest session", value: `${m.mindful.longest} min` },
    ],
    insights: m.insights.map(insight),
  };
}

// ---------------------------------------------------------------- digital wellbeing

export interface DigitalVM {
  /** Mobile 87:374 stats and copy. */
  mobileStats: StatVM[];
  shortDeepSleep: string;
  average: string;
  change: string;
  shortChange: string;
  days: { label: string; total: string; segments: { value: number; color: ColorToken }[] }[];
  legend: LegendVM[];
  stats: StatVM[];
  categories: { key: string; label: string; apps: string; time: string; share: string; fraction: number; color: ColorToken }[];
  deepSleep: { text: string; heavy: string; light: string };
  limits: { name: string; detail: string; on: boolean }[];
}

const CATEGORY_COLOR: ColorToken[] = ["rangeBorderline", "dataSleep", "dataWeight", "dataActivity", "dataComparison"];
const WORDS = ["no", "one", "two", "three", "four", "five", "six", "seven"];
const FULL_DAY: Record<string, string> = { Mon: "Monday", Tue: "Tuesday", Wed: "Wednesday", Thu: "Thursday", Fri: "Friday", Sat: "Saturday", Sun: "Sunday" };

export async function loadDigital(api: VitalLinkClient): Promise<DigitalVM> {
  const [me, data] = await Promise.all([api.me(), api.digital()]);
  const d = need(data, "Digital wellbeing");
  const tz = me.timezone;
  const now = api.now();
  const R = d.readings;
  const total = d.categories.reduce((a, c) => a + c.minutes, 0) || 1;
  const ds = d.deep_sleep ?? { heavy_min: 0, light_min: 0, heavy_nights: 0, light_nights: 0 };
  const longest = stat(R, { metric: "longest_use" }, now, tz);
  const short = (metric: string, label?: string, accent?: ColorToken): StatVM => {
    const x = stat(R, { metric, label, accent }, now, tz);
    return { ...x, caption: R.find((r) => r.metric === metric)?.short_assessment ?? x.caption };
  };
  const longestAt = R.find((r) => r.metric === "longest_use")?.at;
  return {
    mobileStats: [short("pickups", "Pickups", "rangeBorderline"), short("notifications"), short("late_use"), short("first_pickup")],
    shortDeepSleep: d.deep_sleep
      ? `On your ${WORDS[ds.heavy_nights] ?? ds.heavy_nights} heaviest late-use nights, deep sleep averaged ${hoursMinutes(ds.heavy_min).replace(" min", " minutes")}. On the ${WORDS[ds.light_nights] ?? ds.light_nights} lightest, ${hoursMinutes(ds.light_min)}.`
      : "Needs two weeks of sleep and screen-time data.",
    average: hoursMinutes(d.average_min),
    change: `daily average · ${d.change_min < 0 ? "down" : "up"} ${Math.abs(d.change_min)} min from last week`,
    shortChange: `${d.change_min < 0 ? "down" : "up"} ${Math.abs(d.change_min)} min`,
    days: d.week.map((x, i) => ({
      label: DAYS[i],
      total: `${(x.day_h + x.late_h).toFixed(1)}h`,
      segments: [
        { value: x.late_h, color: "rangeBorderline" },
        { value: x.day_h, color: "dataNutrition" },
      ],
    })),
    legend: [
      { label: "Daytime use", color: "dataNutrition" },
      { label: "After 10 PM", color: "rangeBorderline" },
    ],
    stats: [
      stat(R, { metric: "pickups", label: "Pickups", accent: "rangeBorderline" }, now, tz),
      stat(R, { metric: "first_pickup" }, now, tz),
      stat(R, { metric: "notifications" }, now, tz),
      stat(R, { metric: "late_use" }, now, tz),
      longestAt ? { ...longest, caption: `${FULL_DAY[weekday(longestAt, tz)]} evening` } : longest,
    ],
    categories: d.categories.map((c, i) => ({
      key: c.name,
      label: c.name,
      apps: c.apps,
      time: hoursMinutes(c.minutes, true),
      share: `${Math.round((c.minutes / total) * 100)}%`,
      fraction: c.minutes / total,
      color: CATEGORY_COLOR[i] ?? "dataComparison",
    })),
    deepSleep: d.deep_sleep
      ? {
          text: `On the ${WORDS[ds.heavy_nights] ?? ds.heavy_nights} nights with the most after-10 PM use, deep sleep averaged ${hoursMinutes(ds.heavy_min).replace(" min", " minutes")}. On the ${WORDS[ds.light_nights] ?? ds.light_nights} lightest nights it averaged ${hoursMinutes(ds.light_min)}.`,
          heavy: hoursMinutes(ds.heavy_min, true),
          light: hoursMinutes(ds.light_min, true),
        }
      : { text: "Needs two weeks of sleep and screen-time data.", heavy: "N/A", light: "N/A" },
    limits: d.limits,
  };
}
