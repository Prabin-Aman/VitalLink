// Display formatting shared by web and mobile. All times render in the user's zone.

const fmt = (tz: string, opts: Intl.DateTimeFormatOptions) =>
  new Intl.DateTimeFormat("en-US", { timeZone: tz, ...opts });

export const timeOfDay = (iso: string, tz: string) =>
  fmt(tz, { hour: "numeric", minute: "2-digit", hour12: true }).format(new Date(iso));

const parts = (iso: string, tz: string) => {
  const p = fmt(tz, { weekday: "long", day: "numeric", month: "long", year: "numeric" })
    .formatToParts(new Date(iso));
  const get = (t: string) => p.find((x) => x.type === t)?.value ?? "";
  return { weekday: get("weekday"), day: get("day"), month: get("month"), year: get("year") };
};

/** "Sunday, 13 September" */
export const longDate = (iso: string, tz: string) => {
  const p = parts(iso, tz);
  return `${p.weekday}, ${p.day} ${p.month}`;
};

/** "13 Sep · 9:12 AM" — day zero-padded as in the designs ("02 Sep"). */
export const shortDateTime = (iso: string, tz: string) => {
  const p = parts(iso, tz);
  return `${p.day.padStart(2, "0")} ${p.month.slice(0, 3)} · ${timeOfDay(iso, tz)}`;
};

/** "Sun · 7:12 AM" */
export const weekdayTime = (iso: string, tz: string) =>
  `${parts(iso, tz).weekday.slice(0, 3)} · ${timeOfDay(iso, tz)}`;

export const dayKey = (iso: string, tz: string) =>
  fmt(tz, { year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(iso));

export const greeting = (iso: string, tz: string) => {
  const h = Number(fmt(tz, { hour: "numeric", hour12: false }).format(new Date(iso)));
  if (h < 12) return "Good Morning";
  if (h < 18) return "Good Afternoon";
  return "Good Evening";
};

export const thousands = (n: number) => Math.round(n).toLocaleString("en-US");

export const metresToMiles = (m: number) => (m / 1609.344).toFixed(1);

/** "3h 12m"; under an hour "48 min" (or "48m" when `compact`). */
export const hoursMinutes = (minutes: number, compact = false) => {
  const m = Math.round(minutes);
  const h = Math.floor(m / 60);
  if (!h) return compact ? `${m}m` : `${m} min`;
  return `${h}h ${String(m % 60).padStart(2, "0")}m`;
};

/** "Sat" */
export const weekday = (iso: string, tz: string) => parts(iso, tz).weekday.slice(0, 3);

/** "06 Sep" */
export const dayMonth = (iso: string, tz: string) => {
  const p = parts(iso, tz);
  return `${p.day.padStart(2, "0")} ${p.month.slice(0, 3)}`;
};

/** "11 Sep 2026" */
export const dayMonthYear = (iso: string, tz: string) => {
  const p = parts(iso, tz);
  return `${p.day.padStart(2, "0")} ${p.month.slice(0, 3)} ${p.year}`;
};

/** "11 September 2026" */
export const longDayMonthYear = (iso: string, tz: string) => {
  const p = parts(iso, tz);
  return `${p.day} ${p.month} ${p.year}`;
};

/** "September 2026" */
export const monthYear = (iso: string, tz: string) => {
  const p = parts(iso, tz);
  return `${p.month} ${p.year}`;
};

const DAY_MS = 86_400_000;
/** Whole calendar days from `now` to `iso` in the user's zone (negative = past). */
export const daysFrom = (iso: string, now: string, tz: string) => {
  const midnight = (s: string) => Date.parse(`${dayKey(s, tz).replace(/(\d+)\/(\d+)\/(\d+)/, "$3-$1-$2")}T00:00:00Z`);
  return Math.round((midnight(iso) - midnight(now)) / DAY_MS);
};

/** "Today", "Tomorrow", "Yesterday", else "Sat". */
export const relativeDay = (iso: string, now: string, tz: string) => {
  const d = daysFrom(iso, now, tz);
  if (d === 0) return "Today";
  if (d === 1) return "Tomorrow";
  if (d === -1) return "Yesterday";
  return weekday(iso, tz);
};

/** Elapsed time: "18 min ago", "2 h ago", "1 d ago", "1 w ago" (`style: "short"` → "2h", "1d"; `"long"` → "2 weeks ago"). */
export const ago = (iso: string, now: string, style: "spaced" | "short" | "long" = "spaced") => {
  const mins = Math.max(0, Math.round((Date.parse(now) - Date.parse(iso)) / 60_000));
  const [n, unit, long] =
    mins < 60 ? [mins, "min", "minute"] :
    mins < 1440 ? [Math.round(mins / 60), "h", "hour"] :
    mins < 10080 ? [Math.round(mins / 1440), "d", "day"] :
    [Math.round(mins / 10080), "w", "week"];
  if (style === "short") return `${n}${unit === "min" ? "m" : unit}`;
  if (style === "long") return `${n} ${long}${n === 1 ? "" : "s"} ago`;
  return `${n} ${unit} ago`;
};

export const km = (m: number) => `${(m / 1000).toFixed(1)} km`;

/** "5:41 /km" */
export const pace = (sPerKm: number) => `${Math.floor(sPerKm / 60)}:${String(Math.round(sPerKm % 60)).padStart(2, "0")} /km`;

/** "27:42" */
export const clock = (s: number) => `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, "0")}`;

/** "92.5 kg" — one decimal only when needed. */
export const kg = (v: number) => `${Number.isInteger(v) ? v : v.toFixed(1)} kg`;

export const percent = (fraction: number) => `${Math.round(fraction * 100)}%`;

/** "1994-04-17" (a calendar date, no time zone) → "17/04/1994". */
export const calendarDate = (ymd: string) => {
  const [y, m, d] = ymd.slice(0, 10).split("-");
  return `${d}/${m}/${y}`;
};

/** "+12145550148" → "(214) 555-0148"; other countries stay in E.164. */
export const phone = (e164: string) => {
  const us = /^\+1(\d{3})(\d{3})(\d{4})$/.exec(e164);
  return us ? `(${us[1]}) ${us[2]}-${us[3]}` : e164;
};

/** BCP-47 tag → the name shown in Settings. */
export const languageName = (tag: string) => ({ "en-US": "English (US)", "es-US": "Español (EE. UU.)" })[tag] ?? tag;

/** Height for the user's units: "178 cm" or "5 ft 10 in". */
export const height = (cm: number, units: "metric" | "imperial") => {
  if (units === "metric") return `${thousands(cm)} cm`;
  const inches = Math.round(cm / 2.54);
  return `${Math.floor(inches / 12)} ft ${inches % 12} in`;
};
