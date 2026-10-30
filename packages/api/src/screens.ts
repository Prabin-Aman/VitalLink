// Screen view-models: everything a screen renders, already formatted.
// Web and mobile render the same view-model; only layout differs.
import type { ColorToken } from "@vitallink/tokens";
import { VitalLinkClient } from "./client";
import {
  dayKey,
  dayMonth,
  greeting,
  longDate,
  metresToMiles,
  monthYear,
  relativeDay,
  shortDateTime,
  thousands,
  timeOfDay,
  weekday,
  weekdayTime,
} from "./format";
import { todayIn } from "./forms";
import { shareRow } from "./views/tabs";
import type { AccessLogEntry, LabResult, RangeStatus, Series, Share, Workout, WorkoutType } from "./types";

// ---------------------------------------------------------------- shared

export interface ChipVM {
  label: string;
  fg: ColorToken;
  bg: ColorToken;
}

const rangeChip = (r: RangeStatus, label?: string): ChipVM => {
  const map: Record<RangeStatus, [string, ColorToken, ColorToken]> = {
    normal: ["Normal", "rangeNormal", "rangeNormalBg"],
    borderline: ["Borderline", "rangeBorderline", "rangeBorderlineBg"],
    high: ["High", "rangeHigh", "rangeHighBg"],
    low: ["Low", "rangeLow", "rangeLowBg"],
    "no-data": ["No data", "rangeNoData", "bgSubtle"],
  };
  const [l, fg, bg] = map[r];
  return { label: label ?? l, fg, bg };
};

const scoreLabel = (s: number) => (s >= 80 ? "Good" : s >= 60 ? "Fair" : "Low");

// ---------------------------------------------------------------- dashboard

export interface MetricCardVM {
  key: string;
  label: string;
  shortLabel: string;
  value: string;
  unit: string;
  accent: ColorToken;
  chip: ChipVM;
  when: string;
}

export interface BarVM {
  label: string;
  shortLabel: string;
  value: number;
  valueLabel: string;
  metGoal: boolean;
}

export interface DashboardVM {
  greeting: string;
  firstName: string;
  initials: string;
  fullName: string;
  dateLabel: string;
  score: number | null;
  scoreLabel: string;
  scoreDelta: number;
  metrics: MetricCardVM[];
  steps: { goal: number; bars: BarVM[] };
  bloodPressure: { systolic: number[]; diastolic: number[]; latestSys: number; latestDia: number } | null;
  medications: { id: string; title: string; detail: string; taken: boolean }[] | null;
  appointment: { with: string; when: string } | null;
  streak: { days: number; longest: number } | null;
  lab: { name: string; value: string; chip: ChipVM; note: string } | null;
}

const METRIC_META: Record<string, { label: string; short: string; accent: ColorToken }> = {
  heart_rate: { label: "Heart Rate", short: "Heart Rate", accent: "dataHeartRate" },
  blood_pressure: { label: "Blood Pressure", short: "Blood Pressure", accent: "dataBloodPressure" },
  oxygen_saturation: { label: "Blood Oxygen", short: "Blood Oxygen", accent: "dataBloodOxygen" },
  weight: { label: "Weight", short: "Weight", accent: "dataWeight" },
  glucose: { label: "Blood Glucose", short: "Glucose", accent: "dataGlucose" },
};

/** Unit labels where the design spells a unit differently from the API's code. */
const UNIT_LABEL: Record<string, string> = { bpm: "BPM" };

/** One bar per day, labelled by the bucket's weekday (the range ends today, whatever day that is). */
const stepBars = (points: { bucket: string; value: number }[], goal: number, tz: string): BarVM[] => {
  return points.map((p) => ({
    label: weekday(p.bucket, tz),
    shortLabel: weekday(p.bucket, tz)[0],
    value: p.value,
    valueLabel: String(Math.round(p.value)),
    metGoal: p.value >= goal,
  }));
};

// ---------------------------------------------------------------- dashboard chart ranges (D / W / M / 6M / Y)

export const CHART_RANGES = ["D", "W", "M", "6M", "Y"];
const RANGE: Record<string, { days: number; interval: Series["interval"]; title: string; per: string }> = {
  D: { days: 1, interval: "hour", title: "Activity Today", per: "by hour" },
  W: { days: 7, interval: "day", title: "Activity This Week", per: "" },
  M: { days: 30, interval: "day", title: "Activity · Last 30 Days", per: "" },
  "6M": { days: 182, interval: "week", title: "Activity · Last 6 Months", per: "daily average by week" },
  Y: { days: 365, interval: "month", title: "Activity · Last 12 Months", per: "daily average by month" },
};

export interface StepsRangeVM {
  title: string;
  caption: string;
  bars: BarVM[];
  /** Steps per pixel of bar height (100 for the designed week; others fit the 126px plot). */
  perPx: number;
}

/** Steps for a range. Weekly and monthly buckets show the daily average, so the goal colour still means "met". */
export async function loadStepsRange(api: VitalLinkClient, range: string): Promise<StepsRangeVM> {
  const r = RANGE[range] ?? RANGE.W;
  const [me, targets, series] = await Promise.all([api.me(), api.targets(), api.series("steps", r.days, r.interval)]);
  const tz = me.timezone;
  const goal = targets.daily_steps_goal;
  const nowMs = Date.parse(api.now());
  const days = (p: { bucket: string }, i: number, n: number) => {
    if (r.interval === "week") return i === n - 1 ? Math.max(1, Math.ceil((nowMs - Date.parse(p.bucket)) / 86400000)) : 7;
    if (r.interval === "month") {
      const d = new Date(p.bucket);
      const inMonth = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).getUTCDate();
      return i === n - 1 ? Math.max(1, Math.ceil((nowMs - Date.parse(p.bucket)) / 86400000)) : inMonth;
    }
    return 1;
  };
  const pts = series.points;
  const bars: BarVM[] = pts.map((p, i) => {
    const value = p.value / days(p, i, pts.length);
    const label =
      r.interval === "hour" ? timeOfDay(p.bucket, tz) : r.interval === "month" ? monthYear(p.bucket, tz).slice(0, 3) : r.interval === "week" ? dayMonth(p.bucket, tz) : weekday(p.bucket, tz);
    const every = r.interval === "hour" ? 6 : range === "M" ? 5 : range === "6M" ? 4 : 1;
    return {
      label: `${label}-${i}`,
      shortLabel: i % every === 0 ? (r.interval === "hour" ? label.replace(":00 ", " ").replace(/ (AM|PM)/, (m) => m.trim()[0].toLowerCase()) : r.interval === "day" && range === "W" ? label[0] : label) : "",
      value,
      valueLabel: String(Math.round(value)),
      metGoal: r.interval === "hour" ? false : value >= goal,
    };
  });
  const max = Math.max(1, ...bars.map((b) => b.value));
  return {
    title: r.title,
    caption: `Steps · ${r.per || `daily goal ${thousands(goal)}`}`,
    bars,
    perPx: range === "W" ? 100 : max / 110,
  };
}

export async function loadBloodPressureRange(api: VitalLinkClient, range: string) {
  const r = RANGE[range] ?? RANGE.W;
  const bp = await api.bloodPressure(r.days, r.interval);
  return { systolic: bp.systolic.points.map((p) => p.value), diastolic: bp.diastolic.points.map((p) => p.value) };
}

export async function loadDashboard(api: VitalLinkClient): Promise<DashboardVM> {
  const [me, dash, steps, targets, bp, bpLatest, meds, appt, streaks, lab] = await Promise.all([
    api.me(),
    api.dashboard(),
    api.series("steps", 7),
    api.targets(),
    api.bloodPressure(),
    api.bloodPressureLatest(),
    api.medications(),
    api.nextAppointment(),
    api.streaks(),
    api.latestLab(),
  ]);
  const tz = me.timezone;
  const now = dash.generated_at;
  const name = me.display_name ?? me.email.split("@")[0];
  const stepGoal = targets.daily_steps_goal;

  const metrics: MetricCardVM[] = dash.metrics.map((m) => {
    const meta = METRIC_META[m.metric] ?? { label: m.metric, short: m.metric, accent: "dataComparison" as ColorToken };
    const sameDay = m.latest_at && dayKey(m.latest_at, tz) === dayKey(now, tz);
    const value =
      m.metric === "blood_pressure" && bpLatest
        ? `${bpLatest.systolic}/${bpLatest.diastolic}`
        : m.latest === null ? "—" : String(m.latest);
    return {
      key: m.metric,
      label: meta.label,
      shortLabel: meta.short,
      value,
      unit: UNIT_LABEL[m.unit] ?? m.unit,
      accent: meta.accent,
      // Weight shows a trend word rather than a clinical range, as in the design.
      chip: rangeChip(m.range, m.metric === "weight" ? "Steady" : undefined),
      when: !m.latest_at ? "" : sameDay ? timeOfDay(m.latest_at, tz) : "Yesterday",
    };
  });

  return {
    greeting: greeting(now, tz),
    firstName: name.split(" ")[0],
    fullName: name,
    initials: name.split(" ").map((s) => s[0]).join("").slice(0, 2).toUpperCase(),
    dateLabel: longDate(now, tz),
    score: dash.health_score,
    scoreLabel: dash.health_score === null ? "" : scoreLabel(dash.health_score),
    scoreDelta: dash.health_score_delta_7d ?? 0,
    metrics,
    steps: { goal: stepGoal, bars: stepBars(steps.points, stepGoal, tz) },
    bloodPressure: bpLatest
      ? {
          systolic: bp.systolic.points.map((p) => p.value),
          diastolic: bp.diastolic.points.map((p) => p.value),
          latestSys: bpLatest.systolic,
          latestDia: bpLatest.diastolic,
        }
      : null,
    medications: meds && meds.map((m) => {
      const time = timeOfDay(m.at, tz);
      return {
        id: m.id,
        title: [m.name, m.dosage].filter(Boolean).join(" "),
        detail: m.status === "taken" ? `Taken · ${time}` : m.status === "missed" ? `Missed · ${time}` : m.status === "skipped" ? `Skipped · ${time}` : `Due ${time}`,
        taken: m.status === "taken",
      };
    }),
    appointment: appt && {
      with: [appt.clinician, appt.specialty].filter(Boolean).join(" · "),
      when: [`${relativeDay(appt.starts_at, now, tz)}, ${timeOfDay(appt.starts_at, tz)}`, appt.location.replace(/, Dallas$/, "")]
        .filter(Boolean)
        .join(" · "),
    },
    streak: streaks
      ? { days: streaks[0]?.days ?? 0, longest: streaks.find((s) => s.label.startsWith("Longest"))?.days ?? 0 }
      : null,
    lab: lab && labCard(lab.result),
  };
}

/** "14.2 g/dL", a chip from the lab's interpretation, "Ref 13–17 g/dL · Previous 14.0 ↑". */
function labCard(r: LabResult): NonNullable<DashboardVM["lab"]> {
  const f = (v: number) => v.toFixed(r.decimals);
  const unit = r.unit ? ` ${r.unit}` : "";
  const range: RangeStatus =
    r.interpretation === "normal" ? "normal" : r.interpretation === "borderline" ? "borderline" : r.value < r.low ? "low" : "high";
  const trend = r.previous === null ? "" : r.value > r.previous ? " ↑" : r.value < r.previous ? " ↓" : " →";
  const previous = r.previous === null ? "" : ` · Previous ${f(r.previous)}${trend}`;
  return {
    name: r.name,
    value: `${f(r.value)}${unit}`,
    chip: rangeChip(range),
    note: `Ref ${f(r.low)}–${f(r.high)}${unit}${previous}`,
  };
}

// ---------------------------------------------------------------- activity

export interface StatVM {
  key: string;
  label: string;
  value: string;
  unit: string;
  caption: string;
  accent: ColorToken;
}

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

const workoutVM = (w: Workout, tz: string): WorkoutVM => {
  const when = weekdayTime(w.started_at, tz);
  const duration = `${Math.round(w.duration_s / 60)} min`;
  const distance = w.distance_m === null ? "—" : `${metresToMiles(w.distance_m)} mi`;
  const kcal = w.kcal === null ? "—" : `${thousands(w.kcal)} kcal`;
  return {
    id: w.id,
    title: w.title,
    icon: w.type,
    iconColor: WORKOUT_COLOR[w.type],
    when,
    duration,
    distance,
    kcal,
    hr: w.avg_hr === null ? "—" : `avg ${w.avg_hr} BPM`,
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
      { key: "distance", label: "Distance", value: metresToMiles(one(distance)), unit: "mi", caption: "this week", accent: "dataActivity" },
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
    workouts: workouts && workouts.map((w) => workoutVM(w, me.timezone)),
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

// ---------------------------------------------------------------- privacy

export interface AccessCardVM {
  key: string;
  label: string;
  value: string;
  caption: string;
  tone: "default" | "brand" | "critical" | "warning";
}

export interface AccessRowVM {
  who: string;
  detail: string;
  accessed: string;
  purpose: string;
  when: string;
  status: ChipVM;
  shortStatus: string;
  /** Access details dialog (Figma 211:1577). */
  details: {
    subtitle: string;
    rows: { label: string; value: string }[];
    /** The share it went through; `active` false once stopped or expired. */
    share: { id: string; who: string; line: string; until: string; active: boolean } | null;
  };
}

export interface PrivacyVM {
  /** Desktop Privacy Centre cards, in design order. */
  cards: AccessCardVM[];
  /** Mobile summary cards. */
  summary: AccessCardVM[];
  log: AccessRowVM[];
}

const surname = (label: string) => label.replace(/^Dr\.\s+/, "").split(" ").slice(-1)[0];

const RESULT: Record<AccessLogEntry["status"], string> = {
  authorised: "Authorised",
  limited: "Limited scope",
  blocked: "Blocked",
};

/** "Sunday 13 Sep 2026 · 9:12 AM" */
const fullWhen = (iso: string, tz: string) => {
  const [weekday, dm] = longDate(iso, tz).split(", ");
  return `${weekday} ${dm.replace(/(\d+) (\w{3})\w*/, "$1 $2")} ${todayIn(tz, Date.parse(iso)).slice(0, 4)} · ${timeOfDay(iso, tz)}`;
};

function accessDetails(e: AccessLogEntry, shares: Share[], tz: string): AccessRowVM["details"] {
  const share = e.share_id ? shares.find((s) => s.id === e.share_id) : undefined;
  const looked = e.status === "blocked" ? "tried to look at" : "looked at";
  const result =
    e.status === "blocked"
      ? `Blocked${e.purpose ? ` — ${e.purpose.toLowerCase()}` : ""}`
      : `${RESULT[e.status]}${share ? " — allowed by the share below" : ""}`;
  return {
    subtitle: `${e.actor} ${looked} your record on ${dayMonth(e.at, tz).replace(/^0/, "")} at ${timeOfDay(e.at, tz)}.`,
    rows: [
      { label: "Who", value: e.actor },
      { label: "Signed in as", value: e.actor_detail },
      { label: "Opened", value: e.accessed },
      ...(e.status !== "blocked" && e.purpose ? [{ label: "Reason", value: e.purpose }] : []),
      { label: "When", value: fullWhen(e.at, tz) },
      { label: "Result", value: result },
    ],
    share: share ? { ...shareRow(share, tz), active: e.share_active && share.active } : null,
  };
}

const statusChip = (s: AccessLogEntry["status"]): [ChipVM, string] => {
  switch (s) {
    case "authorised":
      return [{ label: "Authorised", fg: "rangeNormal", bg: "rangeNormalBg" }, "Authorised"];
    case "limited":
      return [{ label: "Limited scope", fg: "rangeBorderline", bg: "rangeBorderlineBg" }, "Limited"];
    case "blocked":
      return [{ label: "Blocked", fg: "rangeHigh", bg: "rangeHighBg" }, "Blocked"];
  }
};

export async function loadPrivacy(api: VitalLinkClient): Promise<PrivacyVM> {
  const [me, shares, log, settings] = await Promise.all([
    api.me(),
    api.shares(),
    api.accessLog(),
    api.privacySettings(),
  ]);
  const active = shares.filter((s) => s.active);
  const of = (k: Share["grantee_kind"]) => active.filter((s) => s.grantee_kind === k);
  const doctors = of("doctor");
  const hospitals = of("hospital");
  const family = of("family");
  const apps = of("app");
  const appCaption = apps.length
    ? `${apps[0].grantee_label} — ${apps[0].scopes.join(", ")} only`
    : "None connected";
  const emergencyOn = settings?.emergency_on_lock_screen ?? false;
  const devices = settings?.devices;

  const cards: AccessCardVM[] = [
    { key: "doctors", label: "Doctors with access", value: String(doctors.length), caption: doctors.map((d) => surname(d.grantee_label)).join(", "), tone: "default" },
    // Shown because the desktop design includes it; see Share.grantee_kind.
    { key: "hospitals", label: "Hospitals with access", value: String(hospitals.length), caption: hospitals.map((h) => h.grantee_label).join(" · "), tone: "default" },
    { key: "family", label: "Family access", value: String(family.length), caption: family.map((f) => f.grantee_label).join(", "), tone: "brand" },
    { key: "emergency", label: "Emergency access", value: emergencyOn ? "On" : "Off", caption: "Medical ID visible when locked", tone: "critical" },
    { key: "apps", label: "Third-party apps", value: String(apps.length), caption: appCaption, tone: "warning" },
  ];
  if (devices) {
    cards.push({
      key: "devices",
      label: "Connected devices",
      value: String(devices.total),
      caption: `${devices.syncing} syncing · ${devices.overdue} overdue`,
      tone: "default",
    });
  }

  const people = doctors.length + family.length;
  const summary: AccessCardVM[] = [
    // Design copy said "links & family"; anonymous share links were removed in the
    // backend design review, so shares are always with named people.
    { key: "shares", label: "Active shares", value: String(people), caption: "doctors & family", tone: "brand" },
    { key: "family", label: "Family access", value: String(family.length), caption: "profiles", tone: "brand" },
    { key: "apps", label: "Connected apps", value: String(apps.length), caption: apps.length ? `${apps[0].scopes.join(", ")} only` : "none", tone: "warning" },
    { key: "emergency", label: "Emergency access", value: emergencyOn ? "On" : "Off", caption: "ID when locked", tone: "critical" },
  ];

  return {
    cards,
    summary,
    log: log.map((e) => {
      const [chip, short] = statusChip(e.status);
      return {
        who: e.actor,
        detail: e.actor_detail,
        accessed: e.accessed,
        purpose: e.purpose,
        when: shortDateTime(e.at, me.timezone),
        status: chip,
        shortStatus: short,
        details: accessDetails(e, shares, me.timezone),
      };
    }),
  };
}
