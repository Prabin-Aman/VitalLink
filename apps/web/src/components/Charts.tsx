// Charts drawn from data with token colours. Geometry follows the Figma plots:
// dashboard Activity/BP plots 15:69 and 15:107 (324x150), My Health steps plot 79:1044.
import type { BarVM } from "@vitallink/api";
import type { ColorToken } from "@vitallink/tokens";
import { tokenVar } from "./Icon";
import s from "./Charts.module.css";

export function ScoreRing({ score, size = 112, stroke = 10 }: { score: number; size?: number; stroke?: number }) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const frac = Math.max(0, Math.min(1, score / 100));
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={`Health score ${score} of 100`}>
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        stroke={tokenVar("rangeNormal")}
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeDasharray={`${c * frac} ${c}`}
        transform={`rotate(-90 ${size / 2} ${size / 2})`}
      />
    </svg>
  );
}

/** Dashboard "Activity This Week": 7 touching bars, 1px per 100 steps, baseline 126px down a 150px plot. */
/** Dashboard steps bars. `perPx` steps per pixel: 100 for the designed week; other ranges fit the plot. */
/** The plot is 126px tall (Charts.module.css): a busier week than the design's scale shrinks to fit. */
const PLOT_H = 126;

export function WeekBars({ bars, perPx = 100 }: { bars: BarVM[]; perPx?: number }) {
  const scale = Math.max(perPx, ...bars.map((b) => b.value / PLOT_H));
  return (
    <div>
      <div className={s.weekPlot} style={bars.length > 7 ? { gap: 3 } : undefined} role="img" aria-label={bars.map((b) => `${b.label.replace(/-\d+$/, "")} ${b.valueLabel} steps`).join(", ")}>
        {bars.map((b) => (
          <div key={b.label} className={s.weekCol}>
            <div
              className={s.weekBar}
              style={{ height: Math.max(2, b.value / scale), background: tokenVar(b.metGoal ? "dataActivity" : "bgBrandMuted") }}
            />
          </div>
        ))}
      </div>
      <div className={s.weekAxis} style={bars.length > 7 ? { gap: 3 } : undefined}>
        {bars.map((b) => (
          <span key={b.label} className="vl-caption">
            {b.shortLabel}
          </span>
        ))}
      </div>
    </div>
  );
}

/** Blood pressure lines on a fixed clinical scale (40–170 mmHg), normal band 80–120. */
export function BPChart({ systolic, diastolic, width = 324, height = 150 }: { systolic: number[]; diastolic: number[]; width?: number; height?: number }) {
  const lo = 40;
  const hi = 170;
  const y = (v: number) => ((hi - v) / (hi - lo)) * height;
  const x = (i: number, n: number) => (n <= 1 ? 0 : (i / (n - 1)) * width);
  const path = (vals: number[]) => vals.map((v, i) => `${i ? "L" : "M"}${x(i, vals.length).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  return (
    <svg
      width="100%"
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={`Systolic ${systolic.join(", ")}; diastolic ${diastolic.join(", ")}`}
    >
      <rect x={0} y={y(120)} width={width} height={y(80) - y(120)} rx={4} fill={tokenVar("dataReferenceBand")} />
      {[37, 74, 111].map((gy) => (
        <rect key={gy} x={0} y={gy} width={width} height={1} fill={tokenVar("dataGridLine")} />
      ))}
      <path d={path(systolic)} fill="none" stroke={tokenVar("dataBloodPressure")} strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
      <path d={path(diastolic)} fill="none" stroke={tokenVar("dataHeartRate")} strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
      {/* One or two readings (a single day) make no visible line, so they show as points. */}
      {systolic.length < 3 &&
        [...systolic.map((v, i) => [v, i, "dataBloodPressure"] as const), ...diastolic.map((v, i) => [v, i, "dataHeartRate"] as const)].map(([v, i, c]) => (
          <path
            key={`${c}-${i}`}
            d={`M${(systolic.length === 1 ? width / 2 : x(i, systolic.length)).toFixed(1)},${y(v).toFixed(1)} l0.01,0`}
            stroke={tokenVar(c)}
            strokeWidth={9}
            strokeLinecap="round"
            vectorEffect="non-scaling-stroke"
          />
        ))}
    </svg>
  );
}

/**
 * My Health › Activity steps chart: bars with value labels, a goal line and day labels.
 */
export function StepsChart({ bars, goal, goalLabel }: { bars: BarVM[]; goal: number; goalLabel: string; }) {
  const base = 170; // px from plot top to bar baseline
  const max = 14000;
  const h = (v: number) => (Math.min(v, max) / max) * base;
  return (
    <div className={s.steps} role="img" aria-label={`Steps: ${bars.map((b) => `${b.label} ${b.valueLabel}`).join(", ")}; goal ${goalLabel}`}>
      <div className={s.goalLine} style={{ top: base - h(goal) }} />
      <div className={s.stepsBars} style={{ height: base }}>
        {bars.map((b) => (
          <div key={b.label} className={s.stepsCol}>
            <span className={`${s.valueLabel} vl-caption`}>{b.valueLabel}</span>
            <div className={s.stepsBar} style={{ height: h(b.value), background: tokenVar(b.metGoal ? "dataActivity" : "bgBrandMuted") }} />
          </div>
        ))}
      </div>
      <div className={s.stepsAxis}>
        {bars.map((b) => (
          <span key={b.label} className="vl-caption">
            {b.label}
          </span>
        ))}
      </div>
      {/* Figma labels the goal line in-plot, where it collides with bar values; a legend can't collide. */}
      <div className={`${s.goalKey} vl-caption`}>
        <span className={s.goalSwatch} aria-hidden />
        {`Goal ${goalLabel}`}
      </div>
    </div>
  );
}

export interface StackDay {
  label: string;
  /** Label above the bar ("58m", "6.8h"); "Rest"-style label when `rest`. */
  total: string;
  rest?: boolean;
  /** Bottom to top. */
  segments: { value: number; color: ColorToken }[];
}

/**
 * Stacked day bars: Workouts training load (130:150), Sleep stages, Screen time.
 * `scale` is px per unit; bars sit on a baseline `base` px below the plot top.
 */
export function StackedBars({ days, scale, base = 170, height = 215, gap = 2, radius = 5, label }: { days: StackDay[]; scale: number; base?: number; height?: number; gap?: number; radius?: number; label: string }) {
  return (
    <div className={s.stack} style={{ height }} role="img" aria-label={`${label}: ${days.map((d) => `${d.label} ${d.total}`).join(", ")}`}>
      <div className={s.stackPlot} style={{ height: base }}>
        {days.map((d) => (
          <div key={d.label} className={s.stackCol}>
            <span className={`vl-caption ${s.valueLabel}`}>{d.total}</span>
            {d.rest ? (
              <div className={s.rest} />
            ) : (
              <div className={s.stackBar} style={{ gap }}>
                {d.segments.map((seg, i) => (
                  <div key={i} style={{ height: seg.value * scale, background: tokenVar(seg.color), borderRadius: radius }} />
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
      <div className={s.stackAxis}>
        {days.map((d) => (
          <span key={d.label} className="vl-label-m">
            {d.label}
          </span>
        ))}
      </div>
    </div>
  );
}

export interface LineSeries {
  values: number[];
  color: ColorToken;
  /** Values at the top and bottom of the plot. */
  domain: [number, number];
}

/**
 * Trend lines over a 730×190 plot with grid lines at 45/90/135 and an optional
 * shaded reference band (BP 142:1340, resting HR 133:1543, SpO₂ 142:1733).
 */
export function LineChart({ series, band, label, height = 190 }: { series: LineSeries[]; band?: [number, number] | null; label: string; height?: number }) {
  const W = 730;
  const y = (v: number, [top, bottom]: [number, number]) => ((top - v) / (top - bottom)) * height;
  const x = (i: number, n: number) => (n <= 1 ? 0 : (i / (n - 1)) * W);
  const path = (s: LineSeries) => s.values.map((v, i) => `${i ? "L" : "M"}${x(i, s.values.length).toFixed(1)},${y(v, s.domain).toFixed(1)}`).join(" ");
  const d0 = series[0]?.domain;
  return (
    <svg width="100%" height={height} viewBox={`0 0 ${W} ${height}`} preserveAspectRatio="none" role="img" aria-label={label}>
      {band && d0 && <rect x={0} y={y(band[0], d0)} width={W} height={y(band[1], d0) - y(band[0], d0)} rx={4} fill={tokenVar("dataReferenceBand")} />}
      {[45, 90, 135].map((gy) => (
        <rect key={gy} x={0} y={gy} width={W} height={1} fill={tokenVar("dataGridLine")} />
      ))}
      {series.map((s, i) => (
        <path key={i} d={path(s)} fill="none" stroke={tokenVar(s.color)} strokeWidth={2.5} strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
      ))}
    </svg>
  );
}

/** Concentric activity rings (Today's movement 77:512): 13px rings, 3px apart. */
export function Rings({ rings, size = 132, stroke = 13, gap = 3 }: { rings: { fraction: number; color: ColorToken; label: string }[]; size?: number; stroke?: number; gap?: number }) {
  const c = size / 2;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={rings.map((r) => `${r.label} ${Math.round(r.fraction * 100)}%`).join(", ")}>
      {rings.map((r, i) => {
        const radius = c - stroke / 2 - i * (stroke + gap);
        const len = 2 * Math.PI * radius;
        return (
          <g key={r.label}>
            <circle cx={c} cy={c} r={radius} fill="none" stroke={tokenVar("bgMuted")} strokeWidth={stroke} />
            <circle cx={c} cy={c} r={radius} fill="none" stroke={tokenVar(r.color)} strokeWidth={stroke} strokeLinecap="round" strokeDasharray={`${len * r.fraction} ${len}`} transform={`rotate(-90 ${c} ${c})`} />
          </g>
        );
      })}
    </svg>
  );
}

/**
 * Daily bars against a goal (Nutrition › Week 204:1068, Hydration 204:1973): 160px plot, value above each
 * bar, a dashed goal line; bars on target take `color`, the rest bg/brand-muted.
 */
export function GoalBars({ bars, goalFraction, color, label }: { bars: { key: string; label: string; value: string; fraction: number; onTarget: boolean }[]; goalFraction: number; color: ColorToken; label: string }) {
  return (
    <div className={s.goalBars} role="img" aria-label={`${label}: ${bars.map((b) => `${b.label} ${b.value}`).join(", ")}`}>
      <div className={s.goalPlot}>
        <span className={s.goalLine} style={{ bottom: `${goalFraction * 100}%` }} />
        {bars.map((b) => (
          <div key={b.key} className={s.goalCol}>
            <span className={`vl-caption ${s.valueLabel}`}>{b.value}</span>
            <span className={s.goalBar} style={{ height: `${Math.max(2, b.fraction * 100)}%`, background: tokenVar(b.onTarget ? color : "bgBrandMuted") }} />
          </div>
        ))}
      </div>
      <div className={`vl-label-m ${s.goalAxis}`}>
        {bars.map((b) => (
          <span key={b.key}>{b.label}</span>
        ))}
      </div>
    </div>
  );
}
