// Shared view-model pieces: chips, stat cards, metric cards.
import type { ColorToken } from "@vitallink/tokens";
import { dayMonth, weekday } from "../format";
import type { RangeStatus } from "../types";

export interface ChipVM {
  label: string;
  fg: ColorToken;
  bg: ColorToken;
}

export const rangeChip = (r: RangeStatus, label?: string): ChipVM => {
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

export const scoreLabel = (s: number) => (s >= 80 ? "Good" : s >= 60 ? "Fair" : "Low");

export interface StatVM {
  key: string;
  label: string;
  value: string;
  unit: string;
  caption: string;
  accent: ColorToken;
}

export const stat = (key: string, label: string, value: string, unit: string, caption: string, accent: ColorToken): StatVM => ({ key, label, value, unit, caption, accent });

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

/** "Tue 03 Nov" — a session's or a day's label in lists. */
export const dayLabel = (iso: string, tz: string) => `${weekday(iso, tz)} ${dayMonth(iso, tz)}`;
