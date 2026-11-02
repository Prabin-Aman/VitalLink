// Dashboard — Desktop 1440 (Figma 4:13): the dashboard and its D / W / M / 6M / Y chart ranges.
import type { ColorToken } from "@vitallink/tokens";
import type { VitalLinkClient } from "../client";
import { dayKey, dayMonth, greeting, longDate, monthYear, relativeDay, thousands, timeOfDay, weekday } from "../format";
import type { LabResult, RangeStatus, Series } from "../types";
import { type ChipVM, type MetricCardVM, rangeChip, scoreLabel } from "./shared";

// ---------------------------------------------------------------- dashboard

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
export const stepBars = (points: { bucket: string; value: number }[], goal: number, tz: string): BarVM[] => {
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
        : m.latest === null ? "N/A" : String(m.latest);
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
