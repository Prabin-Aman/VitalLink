// Entry forms (Figma page "15 — Proposed · Entry forms"): option lists, validation and request bodies.
// Screens only bind inputs; every rule about what the API accepts lives here, shared by web and mobile.
import type { GoalMetricOption } from "./types";

export type Option<T extends string = string> = { value: T; label: string };
/** A form either has a body to send or a message to show next to the actions. */
export type Checked<T> = { ok: true; body: T } | { ok: false; error: string };
const fail = (error: string) => ({ ok: false as const, error });

// ---------------------------------------------------------------- time in the account's zone

const PARTS = (tz: string) =>
  new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" });

function wall(ms: number, tz: string) {
  const p = Object.fromEntries(PARTS(tz).formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  return { y: +p.year, mo: +p.month, d: +p.day, h: +p.hour, mi: +p.minute, s: +p.second };
}

/** Minutes the zone is ahead of UTC at that instant (Dallas in summer: -300). */
function offsetMinutes(ms: number, tz: string) {
  const w = wall(ms, tz);
  return Math.round((Date.UTC(w.y, w.mo - 1, w.d, w.h, w.mi, w.s) - ms) / 60000);
}

/** "2026-10-27" + "08:40" in America/Chicago → "2026-10-27T08:40:00-05:00". */
export function zonedIso(date: string, time: string, tz: string): string {
  const [y, mo, d] = date.split("-").map(Number);
  const [h, mi] = time.split(":").map(Number);
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  let off = offsetMinutes(guess, tz);
  off = offsetMinutes(guess - off * 60000, tz);
  const sign = off < 0 ? "-" : "+";
  const abs = Math.abs(off);
  const two = (n: number) => String(n).padStart(2, "0");
  return `${date}T${two(h)}:${two(mi)}:00${sign}${two(Math.floor(abs / 60))}:${two(abs % 60)}`;
}

const two = (n: number) => String(n).padStart(2, "0");
/** Today in the zone, "YYYY-MM-DD". */
export const todayIn = (tz: string, now = Date.now()) => {
  const w = wall(now, tz);
  return `${w.y}-${two(w.mo)}-${two(w.d)}`;
};
/** Now in the zone, "HH:MM", rounded down to 5 minutes (readings and buckets sit on 5-minute marks). */
export const timeIn = (tz: string, now = Date.now()) => {
  const w = wall(now, tz);
  return `${two(w.h)}:${two(w.mi - (w.mi % 5))}`;
};
/** The zone's date and time of an ISO instant, for prefilling a form. */
export const dateTimeIn = (iso: string, tz: string) => {
  const w = wall(Date.parse(iso), tz);
  return { date: `${w.y}-${two(w.mo)}-${two(w.d)}`, time: `${two(w.h)}:${two(w.mi)}` };
};

const num = (s: string) => {
  const t = s.replace(/,/g, "").trim();
  return t === "" ? null : Number(t);
};
const between = (v: number | null, lo: number, hi: number) => v !== null && Number.isFinite(v) && v >= lo && v <= hi;
const blankToNull = (s: string) => (s.trim() === "" ? null : s.trim());

/** A US number typed any way ("(214) 555-0148") as E.164; other inputs must already start with +. */
export function toE164(raw: string): string | null {
  const digits = raw.replace(/[^\d+]/g, "");
  if (/^\+\d{8,15}$/.test(digits)) return digits;
  const d = digits.replace(/\D/g, "");
  if (d.length === 10) return `+1${d}`;
  if (d.length === 11 && d.startsWith("1")) return `+${d}`;
  return null;
}

// ---------------------------------------------------------------- Add Reading

export interface ReadingMetric { value: string; label: string; unit: string; min: number; max: number; pair?: boolean }
/** Metrics a person may type in (the catalogue's manual_allowed), with its value limits. */
export const READING_METRICS: ReadingMetric[] = [
  { value: "blood_pressure", label: "Blood pressure", unit: "mmHg", min: 60, max: 260, pair: true },
  { value: "heart_rate", label: "Heart rate", unit: "bpm", min: 25, max: 250 },
  { value: "oxygen_saturation", label: "Blood oxygen", unit: "%", min: 50, max: 100 },
  { value: "glucose", label: "Blood glucose", unit: "mg/dL", min: 20, max: 600 },
  { value: "body_temperature", label: "Temperature", unit: "°C", min: 30, max: 45 },
  { value: "weight", label: "Weight", unit: "kg", min: 20, max: 400 },
  { value: "body_fat", label: "Body fat", unit: "%", min: 2, max: 75 },
  { value: "waist", label: "Waist", unit: "cm", min: 10, max: 250 },
];

export interface ReadingForm { metric: string; value: string; value2: string; date: string; time: string }
/** One call per reading: blood pressure is a systolic and a diastolic reading at the same time. */
export function checkReading(f: ReadingForm, tz: string): Checked<{ metric: string; value: number; at: string }[]> {
  const m = READING_METRICS.find((x) => x.value === f.metric);
  if (!m) return fail("Pick a measurement.");
  const at = zonedIso(f.date, f.time, tz);
  if (Date.parse(at) > Date.now() + 5 * 60000) return fail("That time is in the future.");
  const v = num(f.value);
  if (m.pair) {
    const d = num(f.value2);
    if (!between(v, 60, 260)) return fail("Systolic should be between 60 and 260 mmHg.");
    if (!between(d, 30, 160)) return fail("Diastolic should be between 30 and 160 mmHg.");
    if (d! >= v!) return fail("Diastolic should be lower than systolic.");
    return { ok: true, body: [{ metric: "bp_systolic", value: v!, at }, { metric: "bp_diastolic", value: d!, at }] };
  }
  if (!between(v, m.min, m.max)) return fail(`${m.label} should be between ${m.min} and ${m.max} ${m.unit}.`);
  return { ok: true, body: [{ metric: m.value, value: v!, at }] };
}

// ---------------------------------------------------------------- Log Food

export const MEAL_SLOTS: Option[] = [
  { value: "breakfast", label: "Breakfast" },
  { value: "lunch", label: "Lunch" },
  { value: "dinner", label: "Dinner" },
  { value: "snack", label: "Snack" },
];
export interface FoodForm { slot: string; foodId: string | null; grams: string; date: string; time: string }
export function checkFood(f: FoodForm, tz: string): Checked<object> {
  if (!f.foodId) return fail("Search for a food and pick it from the list.");
  const g = num(f.grams);
  if (!between(g, 1, 5000)) return fail("Amount should be between 1 and 5,000 grams.");
  return { ok: true, body: { slot: f.slot, eaten_at: zonedIso(f.date, f.time, tz), items: [{ food_id: f.foodId, quantity_g: g }] } };
}
/** "≈ 410 kcal · 38 g protein · 18 g carbs · 21 g fat" for an amount of a food (macros are per 100 g). */
export function foodEstimate(food: { kcal: number; protein_g: number; carbs_g: number; fat_g: number }, grams: string) {
  const g = num(grams);
  if (!between(g, 1, 5000)) return null;
  const k = g! / 100;
  return `≈ ${Math.round(food.kcal * k).toLocaleString("en-US")} kcal · ${Math.round(food.protein_g * k)} g protein · ${Math.round(food.carbs_g * k)} g carbs · ${Math.round(food.fat_g * k)} g fat`;
}

// ---------------------------------------------------------------- Log Workout

export const WORKOUT_TYPES: Option[] = [
  { value: "walk", label: "Walk" },
  { value: "run", label: "Run" },
  { value: "cycle", label: "Cycle" },
  { value: "strength", label: "Strength" },
  { value: "swim", label: "Swim" },
  { value: "hiit", label: "HIIT" },
  { value: "yoga", label: "Yoga" },
  { value: "other", label: "Other" },
];
export const EFFORT: Option[] = [{ value: "", label: "Not set" }, ...Array.from({ length: 10 }, (_, i) => ({ value: String(i + 1), label: `${i + 1} of 10` }))];
export interface WorkoutForm { type: string; title: string; date: string; time: string; minutes: string; km: string; effort: string; avgHr: string }
export function checkWorkout(f: WorkoutForm, tz: string): Checked<object> {
  if (!f.title.trim()) return fail("Give the workout a title.");
  const min = num(f.minutes);
  if (!between(min, 1, 1440)) return fail("Duration should be between 1 and 1,440 minutes.");
  const km = num(f.km);
  if (km !== null && !between(km, 0, 1000)) return fail("Distance should be under 1,000 km.");
  const hr = num(f.avgHr);
  if (hr !== null && !between(hr, 25, 250)) return fail("Average heart rate should be between 25 and 250 bpm.");
  const startedAt = zonedIso(f.date, f.time, tz);
  if (Date.parse(startedAt) > Date.now()) return fail("That start time is in the future.");
  const kind = f.type === "strength" ? "strength" : f.type === "yoga" ? "mobility" : "cardio";
  return {
    ok: true,
    body: {
      type: f.type,
      kind,
      title: f.title.trim(),
      started_at: startedAt,
      duration_s: Math.round(min! * 60),
      ...(km !== null ? { distance_m: Math.round(km * 1000) } : {}),
      ...(f.effort ? { rpe: Number(f.effort) } : {}),
      ...(hr !== null ? { avg_hr: Math.round(hr) } : {}),
      source: "manual",
    },
  };
}

// ---------------------------------------------------------------- Add Medication

export const DOSE_FORMS: Option[] = [
  { value: "tablet", label: "Tablet" },
  { value: "capsule", label: "Capsule" },
  { value: "liquid", label: "Liquid" },
  { value: "inhaler", label: "Inhaler" },
  { value: "injection", label: "Injection" },
  { value: "topical", label: "Topical" },
  { value: "other", label: "Other" },
];
/** ISO weekdays, Monday = 1. */
export const WEEKDAYS: Option[] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((label, i) => ({ value: String(i + 1), label }));
export interface MedicationForm { name: string; form: string; strength: string; times: string[]; days: string[]; prescriber: string; refills: string; instructions: string }
export function checkMedication(f: MedicationForm): Checked<object> {
  if (!f.name.trim()) return fail("Enter the medication's name.");
  if (!f.strength.trim()) return fail("Enter the strength, e.g. 500 mg.");
  if (f.times.length === 0) return fail("Add at least one dose time.");
  if (f.days.length === 0) return fail("Pick at least one day.");
  const refills = num(f.refills);
  if (refills !== null && !between(refills, 0, 99)) return fail("Refills left should be between 0 and 99.");
  return {
    ok: true,
    body: {
      name: f.name.trim(),
      form: f.form,
      strength: f.strength.trim(),
      times: [...f.times].sort(),
      // Every day is the default; only a partial week is sent.
      ...(f.days.length < 7 ? { days: f.days.map(Number).sort() } : {}),
      prescriber: blankToNull(f.prescriber),
      ...(refills !== null ? { refills_left: refills } : {}),
      instructions: blankToNull(f.instructions),
    },
  };
}
/** "08:00" → "8:00 AM" for the dose-time chips. */
export const timeLabel = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return `${((h + 11) % 12) + 1}:${two(m)} ${h < 12 ? "AM" : "PM"}`;
};

// ---------------------------------------------------------------- Book Appointment / Reschedule

export const VISIT_MODES: Option[] = [
  { value: "in_person", label: "In person" },
  { value: "telehealth", label: "Telehealth" },
];
export const VISIT_LENGTHS: Option[] = [15, 20, 30, 45, 60, 90].map((m) => ({ value: String(m), label: `${m} min` }));
export interface AppointmentForm { clinician: string; specialty: string; mode: string; date: string; time: string; length: string; location: string; notes: string }
export function checkAppointment(f: AppointmentForm, tz: string): Checked<object> {
  if (!f.clinician.trim()) return fail("Enter who the visit is with.");
  if (f.mode === "in_person" && !f.location.trim()) return fail("Enter where the visit is.");
  return {
    ok: true,
    body: {
      clinician: f.clinician.trim(),
      specialty: f.specialty.trim(),
      starts_at: zonedIso(f.date, f.time, tz),
      duration_min: Number(f.length),
      mode: f.mode,
      location: f.location.trim(),
      notes: blankToNull(f.notes),
    },
  };
}
export function checkReschedule(date: string, time: string, tz: string): Checked<string> {
  const at = zonedIso(date, time, tz);
  if (Date.parse(at) < Date.now()) return fail("Pick a time that hasn't passed yet.");
  return { ok: true, body: at };
}

// ---------------------------------------------------------------- New Goal

export const GOAL_AREAS: Option<GoalMetricOption["area"]>[] = [
  { value: "activity", label: "Activity" },
  { value: "training", label: "Training" },
  { value: "nutrition", label: "Nutrition" },
  { value: "recovery", label: "Recovery" },
  { value: "body", label: "Body" },
  { value: "heart", label: "Heart" },
  { value: "mind", label: "Mind" },
];
export const DIRECTIONS: Option[] = [
  { value: "at_least", label: "At least" },
  { value: "at_most", label: "At most" },
];
/** How a goal's target is typed: the unit shown, the period, and the factor to the API's unit. */
export function goalUnit(m: GoalMetricOption): { unit: string; per: string; factor: number } {
  const per = m.kind === "daily_total" ? "a day" : m.kind === "weekly_count" ? "a week" : "";
  if (m.unit === "m") return { unit: "km", per, factor: 1000 };
  if (m.unit === "sleep_min") return { unit: "hours", per, factor: 60 };
  if (m.unit === "count") return { unit: m.code === "steps" ? "steps" : "", per, factor: 1 };
  return { unit: m.unit, per, factor: 1 };
}
export interface GoalForm { metric: string; direction: string; target: string; starts: string; ends: string }
export function checkGoal(f: GoalForm, metrics: GoalMetricOption[]): Checked<object> {
  const m = metrics.find((x) => x.code === f.metric);
  if (!m) return fail("Pick what to track.");
  const t = num(f.target);
  if (t === null || !(t > 0)) return fail("Enter a target above zero.");
  if (f.ends && f.ends <= f.starts) return fail("The end date should be after the start.");
  const u = goalUnit(m);
  const words = [f.direction === "at_most" ? "At most" : "", t.toLocaleString("en-US"), u.unit, u.per].filter(Boolean).join(" ");
  return {
    ok: true,
    body: {
      title: `${m.label} — ${words}`,
      short_title: m.label,
      metric: m.code,
      area: m.area,
      comparator: f.direction,
      target: t * u.factor,
      starts_on: f.starts,
      ...(f.ends ? { ends_on: f.ends } : {}),
    },
  };
}

// ---------------------------------------------------------------- Upload Document

export const RECORD_TYPES: Option[] = [
  { value: "lab", label: "Lab result" },
  { value: "prescription", label: "Prescription" },
  { value: "imaging", label: "Imaging" },
  { value: "visit", label: "Visit summary" },
  { value: "vaccination", label: "Vaccination" },
  { value: "procedure", label: "Procedure" },
  { value: "upload", label: "Other document" },
];
export const UPLOAD_TYPES = ["application/pdf", "image/jpeg", "image/png", "image/heic"];
export const UPLOAD_MAX_BYTES = 25 * 1024 * 1024;
export interface UploadForm { title: string; type: string; date: string; source: string }
export function checkUpload(f: UploadForm, file: { type: string; size: number } | null, today: string): Checked<object> {
  if (!file) return fail("Choose a file to upload.");
  if (!UPLOAD_TYPES.includes(file.type)) return fail("Upload a PDF, JPG, PNG or HEIC file.");
  if (file.size > UPLOAD_MAX_BYTES) return fail("That file is over 25 MB.");
  if (!f.title.trim()) return fail("Give the document a title.");
  if (f.date > today) return fail("The document date is in the future.");
  return { ok: true, body: { title: f.title.trim(), type: f.type, date: f.date, source: f.source.trim() } };
}
/** "chest_xray-2026.pdf" → "Chest xray 2026" as a starting title. */
export const titleFromFile = (name: string) => {
  const base = name.replace(/\.[^.]+$/, "").replace(/[_-]+/g, " ").trim();
  return base.charAt(0).toUpperCase() + base.slice(1);
};

// ---------------------------------------------------------------- Edit Emergency Info

export const CONDITION_KINDS: Option<"chronic" | "allergy">[] = [
  { value: "chronic", label: "Condition" },
  { value: "allergy", label: "Allergy" },
];
export interface ContactForm { name: string; relation: string; phone: string }
/** null when the contact fields are all empty (nothing to add). */
export function checkContact(f: ContactForm): Checked<{ name: string; relation: string; phone: string }> | null {
  if (!f.name.trim() && !f.relation.trim() && !f.phone.trim()) return null;
  if (!f.name.trim()) return fail("Enter the contact's name.");
  if (!f.relation.trim()) return fail("Enter how they're related to you.");
  const phone = toE164(f.phone);
  if (!phone) return fail("Enter a phone number, e.g. (214) 555-0100.");
  return { ok: true, body: { name: f.name.trim(), relation: f.relation.trim(), phone } };
}

// ---------------------------------------------------------------- Share your data (Figma 206:1452)

export const SHARE_KINDS: Option<"doctor" | "family" | "other">[] = [
  { value: "doctor", label: "Doctor" },
  { value: "family", label: "Family" },
  { value: "other", label: "Other" },
];
export const SHARE_SCOPES: Option<"vitals" | "activity" | "sleep" | "nutrition" | "records">[] = [
  { value: "vitals", label: "Vitals" },
  { value: "activity", label: "Activity" },
  { value: "sleep", label: "Sleep" },
  { value: "nutrition", label: "Nutrition" },
  { value: "records", label: "Records & labs" },
];
export const SHARE_ENDS: Option[] = [
  { value: "7", label: "1 week" },
  { value: "30", label: "1 month" },
  { value: "90", label: "3 months" },
  { value: "date", label: "Pick a date" },
  { value: "none", label: "No end date" },
];
export interface ShareForm { label: string; email: string; kind: string; scopes: string[]; ends: string; until: string; purpose: string }
/** Expiry: N days from now, or the end of the picked day in the account's zone, or none. */
export function checkShare(f: ShareForm, tz: string, now = Date.now()): Checked<object> {
  if (!f.label.trim()) return fail("Enter who you're sharing with.");
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(f.email.trim())) return fail("Enter their email address — they sign in with it to see what you share.");
  if (f.scopes.length === 0) return fail("Pick at least one thing they can see.");
  let expires: string | null = null;
  if (f.ends === "date") {
    if (!f.until) return fail("Pick the last day they can see it.");
    expires = zonedIso(f.until, "23:59", tz);
    if (Date.parse(expires) <= now) return fail("Pick a day after today.");
  } else if (f.ends !== "none") {
    expires = new Date(now + Number(f.ends) * 86400000).toISOString();
  }
  return {
    ok: true,
    body: {
      label: f.label.trim(),
      email: f.email.trim().toLowerCase(),
      kind: f.kind,
      scopes: SHARE_SCOPES.map((x) => x.value).filter((v) => f.scopes.includes(v)),
      purpose: f.purpose.trim() || null,
      ...(expires ? { expires_at: expires } : {}),
    },
  };
}

// ---------------------------------------------------------------- Add a note (Figma 209:1575)

export const NOTE_MAX = 200;
/** A blank note clears it (null). */
export function checkNote(note: string): Checked<{ note: string | null }> {
  const t = note.trim();
  if (t.length > NOTE_MAX) return fail(`Keep the note under ${NOTE_MAX} characters.`);
  return { ok: true, body: { note: t || null } };
}
