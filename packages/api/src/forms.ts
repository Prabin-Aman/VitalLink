// Entry forms (Figma page "15 — Proposed · Entry forms"): option lists, validation and request bodies.
// Screens only bind inputs; every rule about what the API accepts lives here.
import { dayMonth, type Units } from "./format";
import type { FoodHit, GoalDetail, GoalMetricOption, MedicationDetail, NutritionTargets, Targets } from "./types";

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

const DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const utcDay = (date: string) => {
  const [y, mo, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, mo - 1, d));
};
/** "2026-11-03" → "3 Nov", or "Tue 3 Nov" with the weekday. */
export const dateLabel = (date: string, withWeekday = false) => {
  const d = utcDay(date);
  const s = `${d.getUTCDate()} ${MON[d.getUTCMonth()]}`;
  return withWeekday ? `${DOW[d.getUTCDay()]} ${s}` : s;
};
/** "YYYY-MM-DD" n days after `date` (negative goes back). */
export const addDays = (date: string, n: number) => new Date(utcDay(date).getTime() + n * 86400000).toISOString().slice(0, 10);
/** Every date from `from` to `to`, both included; labelled with the weekday, and "Today" for `today`. */
/** Times through the day every `step` minutes, plus `keep` (a prefilled value off the step) in order. */
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

// ---------------------------------------------------------------- Create Account / Sign in

/** What the password still needs (the API's rule: 12+ characters, a number and a symbol), or null. */
export function passwordNeeds(p: string): string | null {
  const missing = [p.length < 12 && "12 characters", !/\d/.test(p) && "a number", !/[^A-Za-z0-9]/.test(p) && "a symbol"].filter(Boolean) as string[];
  return missing.length ? `Still needs ${missing.join(", ").replace(/, ([^,]*)$/, " and $1")}.` : null;
}

export interface PasswordChangeForm { current: string; next: string; confirm: string }
/** Settings › Security › Change password (Figma 221:2114). */
export function checkPasswordChange(f: PasswordChangeForm): Checked<{ current: string; next: string }> {
  if (!f.current) return fail("Enter your current password.");
  const needs = passwordNeeds(f.next);
  if (needs) return fail(`New password: ${needs}`);
  if (f.next !== f.confirm) return fail("The two new passwords don't match.");
  if (f.next === f.current) return fail("Choose a password different from the current one.");
  return { ok: true, body: { current: f.current, next: f.next } };
}

/** Nutrition › Scan Barcode (Figma 221:2145): the digits under the bars (spaces and dashes are ignored). */
export function checkBarcode(code: string): Checked<string> {
  const digits = code.replace(/[\s-]/g, "");
  if (!/^\d{8,14}$/.test(digits)) return fail("A barcode is 8 to 14 digits, as printed under the bars.");
  return { ok: true, body: digits };
}

// ---------------------------------------------------------------- Create Account steps 2–8 (Figma 226:2235–226:3348)

export const SEXES: Option[] = [
  { value: "", label: "Prefer not to say" },
  { value: "female", label: "Female" },
  { value: "male", label: "Male" },
  { value: "other", label: "Other" },
];
export const UNIT_OPTIONS: Option<"imperial" | "metric">[] = [
  { value: "imperial", label: "Imperial (miles, lb)" },
  { value: "metric", label: "Metric (km, kg)" },
];
/** US time zones first; the browser's own zone is added when it isn't one of them. */
export function timeZoneOptions(own: string): Option[] {
  const us: Option[] = [
    { value: "America/New_York", label: "Eastern (New York)" },
    { value: "America/Chicago", label: "Central (Chicago)" },
    { value: "America/Denver", label: "Mountain (Denver)" },
    { value: "America/Phoenix", label: "Mountain (Phoenix, no daylight saving)" },
    { value: "America/Los_Angeles", label: "Pacific (Los Angeles)" },
    { value: "America/Anchorage", label: "Alaska (Anchorage)" },
    { value: "Pacific/Honolulu", label: "Hawaii (Honolulu)" },
  ];
  return us.some((o) => o.value === own) ? us : [{ value: own, label: own.replace(/_/g, " ") }, ...us];
}

export interface BodyMetricsForm { weight: string; waist: string; bodyFat: string }
/** Body Metrics: today's readings in the API's units (kg, cm, %); blank fields are skipped. */
export function checkBodyMetrics(f: BodyMetricsForm, units: "metric" | "imperial", at: string): Checked<{ metric: string; value: number; at: string }[]> {
  const imperial = units === "imperial";
  const out: { metric: string; value: number; at: string }[] = [];
  const w = num(f.weight), waist = num(f.waist), fat = num(f.bodyFat);
  if (w !== null) {
    const kg = imperial ? w * 0.45359237 : w;
    if (!between(kg, 20, 400)) return fail(imperial ? "Weight should be between 45 and 880 lb." : "Weight should be between 20 and 400 kg.");
    out.push({ metric: "weight", value: Math.round(kg * 10) / 10, at });
  }
  if (waist !== null) {
    const cm = imperial ? waist * 2.54 : waist;
    if (!between(cm, 10, 250)) return fail(imperial ? "Waist should be between 4 and 98 in." : "Waist should be between 10 and 250 cm.");
    out.push({ metric: "waist", value: Math.round(cm * 10) / 10, at });
  }
  if (fat !== null) {
    if (!between(fat, 2, 75)) return fail("Body fat should be between 2 and 75 %.");
    out.push({ metric: "body_fat", value: fat, at });
  }
  return { ok: true, body: out };
}

/** Search doctors: the registry needs a name or a specialty; the city is optional. */
export function checkDoctorSearch(f: { name: string; specialty: string; city: string }): Checked<{ name: string; specialty: string; city: string }> {
  const name = f.name.trim();
  const specialty = f.specialty.trim();
  if (name.length < 2 && specialty.length < 2) return fail("Enter a name or a specialty, at least 2 letters.");
  return { ok: true, body: { name, specialty, city: f.city.trim() } };
}

export interface SignUpForm { first: string; last: string; email: string; phone: string; password: string; confirm: string; zip: string; dob: string; height: string }
/** The sign-up body (POST /v1/auth/sign-up, or Supabase sign-up plus PATCH /me/account for the details). */
export interface SignUpBody {
  email: string;
  password: string;
  first_name: string;
  last_name: string;
  phone?: string;
  zip?: string;
  date_of_birth?: string;
  height_cm?: number;
}
/** The optional details are checked here so the user sees which one is wrong. */
export function checkSignUp(f: SignUpForm, today: string): Checked<SignUpBody> {
  if (!f.first.trim() || !f.last.trim()) return fail("Enter your first and last name.");
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(f.email.trim())) return fail("Enter a valid email address.");
  const needs = passwordNeeds(f.password);
  if (needs) return fail(`Password: ${needs}`);
  if (f.password !== f.confirm) return fail("The two passwords don't match.");
  const details = checkAccountDetails(f, today);
  if (!details.ok) return details;
  return { ok: true, body: { email: f.email.trim().toLowerCase(), password: f.password, ...details.body } };
}

/** Name and the optional details: all of step 1 after Google sign-in, which has no email or password to choose. */
export function checkAccountDetails(f: Pick<SignUpForm, "first" | "last" | "phone" | "zip" | "dob" | "height">, today: string): Checked<Omit<SignUpBody, "email" | "password">> {
  if (!f.first.trim() || !f.last.trim()) return fail("Enter your first and last name.");
  const phone = f.phone.trim() ? toE164(f.phone) : null;
  if (f.phone.trim() && !phone) return fail("Enter a 10-digit mobile number, e.g. (214) 555-0148, or one starting with +.");
  if (f.zip.trim() && !/^\d{5}$/.test(f.zip.trim())) return fail("Enter a 5-digit ZIP code.");
  if (f.dob && (f.dob > today || f.dob < "1900-01-01")) return fail("Check your date of birth.");
  const height = f.height.trim() ? parseHeight(f.height, "imperial") : null;
  if (f.height.trim() && height === null) return fail("Enter a height like 5 ft 10 in or 178 cm.");
  return {
    ok: true,
    body: {
      first_name: f.first.trim(),
      last_name: f.last.trim(),
      ...(phone ? { phone } : {}),
      ...(f.zip.trim() ? { zip: f.zip.trim() } : {}),
      ...(f.dob ? { date_of_birth: f.dob } : {}),
      ...(height !== null ? { height_cm: height } : {}),
    },
  };
}

// ---------------------------------------------------------------- Add Reading

export interface ReadingMetric { value: string; label: string; unit: string; min: number; max: number; pair?: boolean; /** Shown under the field instead of the unit (scores). */ help?: string }
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
  // Check-ins (Mental tab): scores 1–5, and minutes of mindfulness.
  { value: "mood", label: "Mood", unit: "", help: "1 (low) to 5 (great)", min: 1, max: 5 },
  { value: "stress", label: "Stress", unit: "", help: "1 (calm) to 5 (very stressed)", min: 1, max: 5 },
  { value: "energy", label: "Energy", unit: "", help: "1 (drained) to 5 (full of energy)", min: 1, max: 5 },
  { value: "mindful_minutes", label: "Mindful minutes", unit: "minutes", min: 1, max: 180 },
];

export interface ReadingForm { metric: string; value: string; value2: string; date: string; time: string }
/** One call per reading: blood pressure is a systolic and a diastolic reading at the same time. */
function checkOneReading(f: ReadingForm, tz: string): Checked<{ metric: string; value: number; at: string }[]> {
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
  if (!between(v, m.min, m.max)) return fail(`${m.label} should be between ${m.min} and ${m.max}${m.unit ? ` ${m.unit}` : ""}.`);
  return { ok: true, body: [{ metric: m.value, value: v!, at }] };
}
/** One call per reading. The API keeps mindful minutes in 5-minute buckets, so a session is split into them. */
export function checkReading(f: ReadingForm, tz: string): Checked<{ metric: string; value: number; at: string }[]> {
  const c = checkOneReading(f, tz);
  if (!c.ok || f.metric !== "mindful_minutes") return c;
  const first = c.body[0];
  const start = Date.parse(first.at);
  const out: { metric: string; value: number; at: string }[] = [];
  for (let left = first.value, k = 0; left > 0; left -= 5, k++) {
    out.push({ metric: "mindful_minutes", value: Math.min(5, Math.round(left * 10) / 10), at: new Date(start + k * 300000).toISOString() });
  }
  return { ok: true, body: out };
}

// ---------------------------------------------------------------- Record a refill, Nutrition targets, Add a food

export interface RefillForm { refills: string; due: string }
export function checkRefill(f: RefillForm, today: string): Checked<object> {
  const r = num(f.refills);
  if (r === null || !Number.isInteger(r) || !between(r, 0, 99)) return fail("Refills left should be a whole number from 0 to 99.");
  if (f.due && f.due < today) return fail("The next refill date has passed. Pick a later one.");
  return { ok: true, body: { refills_left: r, refill_due_on: f.due || null } };
}

export interface NutritionTargetsForm { kcal: string; protein: string; carbs: string; fat: string; fibre: string; water: string; glass: string; weekly: string }
export const nutritionTargetsForm = (t: NutritionTargets): NutritionTargetsForm => ({
  kcal: String(t.kcal), protein: String(t.protein_g), carbs: String(t.carbs_g), fat: String(t.fat_g),
  fibre: String(t.fibre_g), water: String(t.water_ml), glass: String(t.glass_ml), weekly: String(t.weekly_target_kg),
});
export function checkNutritionTargets(f: NutritionTargetsForm): Checked<NutritionTargets> {
  const v = { kcal: num(f.kcal), protein: num(f.protein), carbs: num(f.carbs), fat: num(f.fat), fibre: num(f.fibre), water: num(f.water), glass: num(f.glass), weekly: num(f.weekly) };
  if (!between(v.kcal, 800, 6000)) return fail("Calories should be between 800 and 6,000 kcal.");
  if (!between(v.protein, 0, 500)) return fail("Protein should be between 0 and 500 g.");
  if (!between(v.carbs, 0, 1000)) return fail("Carbs should be between 0 and 1,000 g.");
  if (!between(v.fat, 0, 400)) return fail("Fat should be between 0 and 400 g.");
  if (!between(v.fibre, 0, 150)) return fail("Fibre should be between 0 and 150 g.");
  if (!between(v.water, 500, 8000)) return fail("Water should be between 500 and 8,000 ml.");
  if (!between(v.glass, 100, 1000)) return fail("Glass size should be between 100 and 1,000 ml.");
  if (!between(v.weekly, -1, 1)) return fail("Weekly weight change should be between -1 and 1 kg.");
  const r = (x: number | null) => Math.round(x!);
  return { ok: true, body: { kcal: r(v.kcal), protein_g: r(v.protein), carbs_g: r(v.carbs), fat_g: r(v.fat), fibre_g: r(v.fibre), water_ml: r(v.water), glass_ml: r(v.glass), weekly_target_kg: Math.round(v.weekly! * 100) / 100 } };
}

export interface NewFoodForm { name: string; brand: string; kcal: string; serving: string; protein: string; carbs: string; fat: string; fibre: string }
/** POST /me/foods: values per 100 g, as on a nutrition label. */
export function checkNewFood(f: NewFoodForm): Checked<object> {
  if (!f.name.trim() || f.name.trim().length > 80) return fail("Enter the food's name (up to 80 characters).");
  const kcal = num(f.kcal), protein = num(f.protein), carbs = num(f.carbs), fat = num(f.fat), fibre = num(f.fibre), serving = num(f.serving);
  if (!between(kcal, 0, 900)) return fail("Calories per 100 g should be between 0 and 900.");
  for (const [label, x] of [["Protein", protein], ["Carbs", carbs], ["Fat", fat]] as const) if (!between(x, 0, 100)) return fail(`${label} per 100 g should be between 0 and 100 g.`);
  if (fibre !== null && !between(fibre, 0, 100)) return fail("Fibre per 100 g should be between 0 and 100 g.");
  if (serving !== null && !(serving > 0 && serving <= 5000)) return fail("A serving should be between 1 and 5,000 g.");
  if (protein! + carbs! + fat! + (fibre ?? 0) > 100) return fail("Protein, carbs, fat and fibre add up to more than 100 g per 100 g.");
  return {
    ok: true,
    body: { name: f.name.trim(), ...(f.brand.trim() ? { brand: f.brand.trim() } : {}), kcal, protein_g: protein, carbs_g: carbs, fat_g: fat, ...(fibre !== null ? { fibre_g: fibre } : {}), ...(serving !== null ? { serving_g: serving } : {}) },
  };
}

// ---------------------------------------------------------------- Add lab results (Figma 218:2098)

export interface LabTestForm { name: string; value: string; unit: string; low: string; high: string }
export interface LabTest { name: string; value: number; unit: string; low: number; high: number; decimals: number }
const decimalsOf = (s: string) => Math.min(4, (s.replace(/,/g, "").split(".")[1] ?? "").length);
export function checkLabTest(f: LabTestForm): Checked<LabTest> {
  if (!f.name.trim()) return fail("Enter the test's name, e.g. Hemoglobin.");
  if (/blood\s*(type|group)/i.test(f.name)) return fail("VitalLink doesn't store blood type or blood group.");
  const v = num(f.value), lo = num(f.low), hi = num(f.high);
  if (v === null || !Number.isFinite(v)) return fail("Enter the result's value.");
  if (lo === null || hi === null) return fail("Enter the normal range (low and high) printed on the report.");
  if (lo > hi) return fail("The normal low should be below the normal high.");
  return { ok: true, body: { name: f.name.trim(), value: v, unit: f.unit.trim(), low: lo, high: hi, decimals: Math.max(decimalsOf(f.value), decimalsOf(f.low), decimalsOf(f.high)) } };
}
export interface LabPanelForm { name: string; short: string; date: string; laboratory: string }
export function checkLabPanel(f: LabPanelForm, tests: LabTest[], tz: string, today: string): Checked<object> {
  if (!f.name.trim()) return fail("Enter the panel's name, e.g. Complete Blood Count.");
  if (!f.short.trim() || f.short.trim().length > 20) return fail("Enter a short name of up to 20 characters, e.g. CBC.");
  if (!f.date || f.date > today) return fail("Pick the date the sample was collected.");
  if (tests.length === 0) return fail("Add at least one test result.");
  return {
    ok: true,
    body: { name: f.name.trim(), short_name: f.short.trim(), laboratory: f.laboratory.trim(), collected_at: zonedIso(f.date, "09:00", tz), results: tests },
  };
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

// ---------------------------------------------------------------- Recipes (Figma 222:2330, 222:2417)

export interface RecipeIngredient { food: FoodHit; grams: number }
export interface RecipeForm { name: string; servings: string; notes: string; items: RecipeIngredient[] }
export function checkRecipe(f: RecipeForm): Checked<{ name: string; servings: number; notes: string; items: { food_id: string; quantity_g: number }[] }> {
  if (!f.name.trim()) return fail("Give the recipe a name.");
  if (f.name.trim().length > 60) return fail("Keep the name to 60 characters.");
  const servings = num(f.servings);
  if (!between(servings, 1, 20) || !Number.isInteger(servings)) return fail("Servings should be a whole number from 1 to 20.");
  if (!f.items.length) return fail("Add at least one food.");
  if (f.items.length > 30) return fail("A recipe can have up to 30 foods.");
  if (f.notes.length > 500) return fail("Notes can be up to 500 characters.");
  return { ok: true, body: { name: f.name.trim(), servings: servings!, notes: f.notes.trim(), items: f.items.map((i) => ({ food_id: i.food.id, quantity_g: i.grams })) } };
}
/** Grams for one ingredient row (1 – 5,000), or a message. */
export function checkIngredient(food: FoodHit | null, grams: string): Checked<RecipeIngredient> {
  if (!food) return fail("Search for a food and pick it from the list.");
  const g = num(grams);
  if (!between(g, 1, 5000)) return fail("Grams should be between 1 and 5,000.");
  return { ok: true, body: { food, grams: g! } };
}
/** "≈ 262 kcal · 16 g protein · 38 g carbs · 5 g fat a serving (150 g)", or null with nothing to show. */
export function recipeEstimate(items: RecipeIngredient[], servings: string) {
  const n = num(servings);
  if (!items.length || !between(n, 1, 20)) return null;
  const sum = (k: "kcal" | "protein_g" | "carbs_g" | "fat_g") => items.reduce((t, i) => t + (i.food[k] * i.grams) / 100, 0) / n!;
  const grams = items.reduce((t, i) => t + i.grams, 0) / n!;
  return `≈ ${Math.round(sum("kcal")).toLocaleString("en-US")} kcal · ${Math.round(sum("protein_g"))} g protein · ${Math.round(sum("carbs_g"))} g carbs · ${Math.round(sum("fat_g"))} g fat a serving (${Math.round(grams)} g)`;
}

export interface RecipeLogForm { slot: string; servings: string; date: string; time: string }
export function checkRecipeLog(f: RecipeLogForm, tz: string): Checked<{ slot: string; servings: number; eaten_at: string }> {
  const s = num(f.servings);
  if (!between(s, 0.25, 20) || Math.round(s! * 4) !== s! * 4) return fail("Servings should be from 0.25 to 20, in quarters (e.g. 1.5).");
  return { ok: true, body: { slot: f.slot, servings: s!, eaten_at: zonedIso(f.date, f.time, tz) } };
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
/** Edit Medication: the same rules; days always sent so a full week replaces a partial one. */
export function checkMedicationEdit(f: MedicationForm): Checked<object> {
  const c = checkMedication(f);
  if (!c.ok) return c;
  return { ok: true, body: { ...c.body, days: f.days.map(Number).sort(), refills_left: num(f.refills) ?? 0, prescriber: f.prescriber.trim(), instructions: f.instructions.trim() } };
}
/** A stored medication as the form's fields (times "08:00:00" → "08:00"). */
export function medicationForm(m: MedicationDetail): MedicationForm {
  const days = [...new Set(m.schedule.flatMap((s) => s.days))].sort().map(String);
  return {
    name: m.name,
    form: m.form,
    strength: m.strength,
    times: [...new Set(m.schedule.map((s) => s.time.slice(0, 5)))].sort(),
    days: days.length ? days : WEEKDAYS.map((d) => d.value),
    prescriber: m.prescriber ?? "",
    refills: String(m.refills_left),
    instructions: m.instructions ?? "",
  };
}
/** "08:00" → "8:00 AM" for the dose-time chips. */
export const timeLabel = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return `${((h + 11) % 12) + 1}:${two(m)} ${h < 12 ? "AM" : "PM"}`;
};

// ---------------------------------------------------------------- Import health data (Figma 213:1701)

export const IMPORT_PERIODS: Option[] = [
  { value: "30", label: "Last 30 days" },
  { value: "90", label: "Last 90 days" },
  { value: "365", label: "Last year" },
];
export const IMPORT_TYPES = ".zip,.xml,.csv,application/zip,text/xml,text/csv";
export const IMPORT_MAX_BYTES = 300 * 1024 * 1024;

export function checkImport(file: { name: string; size: number } | null): Checked<true> {
  if (!file) return fail("Choose a file to import.");
  if (!/\.(zip|xml|csv)$/i.test(file.name)) return fail("Choose an Apple Health export (export.zip or export.xml) or a .csv file.");
  if (file.size > IMPORT_MAX_BYTES) return fail("That file is over 300 MB.");
  if (file.size === 0) return fail("That file is empty.");
  return { ok: true, body: true };
}

/** The CSV the import reads: start (ISO with offset, or local "YYYY-MM-DD HH:MM"), catalogue metric, value. */
export const IMPORT_CSV_TEMPLATE = [
  "start,metric,value",
  "2026-10-28 08:40,bp_systolic,121",
  "2026-10-28 08:40,bp_diastolic,79",
  "2026-10-28 08:40,heart_rate,68",
  "2026-10-28 07:15,weight,72.4",
  "2026-10-28T07:15:00-05:00,glucose,104",
  "",
].join("\r\n");

/** "Imported 1,284 readings · 12 Sep – 30 Oct · 1,200 new, 84 unchanged · 3 skipped" */
export function importSummary(r: { readings: number; inserted: number; updated: number; unchanged: number; skipped: number; from: string | null; to: string | null }, tz: string): string {
  const n = (x: number) => x.toLocaleString("en-US");
  const day = (iso: string) => dayMonth(iso, tz).replace(/^0/, "");
  const range = r.from && r.to ? ` · ${day(r.from)}${day(r.from) === day(r.to) ? "" : ` – ${day(r.to)}`}` : "";
  const parts = [r.inserted && `${n(r.inserted)} new`, r.updated && `${n(r.updated)} updated`, r.unchanged && `${n(r.unchanged)} unchanged`].filter(Boolean).join(", ");
  return `Imported ${n(r.readings)} reading${r.readings === 1 ? "" : "s"}${range}${parts ? ` · ${parts}` : ""}${r.skipped ? ` · ${n(r.skipped)} skipped` : ""}`;
}

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
/**
 * How a goal's target is typed: the unit shown, the period, and the factor to the API's unit. Distance,
 * weight and lengths follow Settings › Units, as goal progress does (GoalService.format).
 */
export function goalUnit(m: GoalMetricOption, units: Units = "metric"): { unit: string; per: string; factor: number } {
  const per = m.kind === "daily_total" ? "a day" : m.kind === "weekly_count" ? "a week" : "";
  const imperial = units === "imperial";
  if (m.unit === "m") return imperial ? { unit: "mi", per, factor: 1609.344 } : { unit: "km", per, factor: 1000 };
  if (m.unit === "kg" && imperial) return { unit: "lb", per, factor: 0.45359237 };
  if (m.unit === "cm" && imperial) return { unit: "in", per, factor: 2.54 };
  if (m.unit === "sleep_min") return { unit: "hours", per, factor: 60 };
  if (m.unit === "count") return { unit: m.code === "steps" ? "steps" : "", per, factor: 1 };
  return { unit: m.unit, per, factor: 1 };
}
export interface GoalForm { metric: string; direction: string; target: string; starts: string; ends: string }
export function checkGoal(f: GoalForm, metrics: GoalMetricOption[], units: Units = "metric"): Checked<object> {
  const m = metrics.find((x) => x.code === f.metric);
  if (!m) return fail("Pick what to track.");
  const t = num(f.target);
  if (t === null || !(t > 0)) return fail("Enter a target above zero.");
  if (f.ends && f.ends <= f.starts) return fail("The end date should be after the start.");
  const u = goalUnit(m, units);
  const words = [f.direction === "at_most" ? "At most" : "", t.toLocaleString("en-US"), u.unit, u.per].filter(Boolean).join(" ");
  return {
    ok: true,
    body: {
      title: `${m.label}: ${words}`,
      short_title: m.label,
      metric: m.code,
      area: m.area,
      comparator: f.direction,
      target: Math.round(t * u.factor * 1000) / 1000,
      starts_on: f.starts,
      ...(f.ends ? { ends_on: f.ends } : {}),
    },
  };
}

/** Edit Goal (Figma 214:2245): target, end date and paused/active. The title follows the new target. */
export interface GoalEditForm { target: string; ends: string; status: "active" | "paused" }
export function goalEditForm(g: GoalDetail, metrics: GoalMetricOption[], units: Units = "metric"): GoalEditForm {
  const m = metrics.find((x) => x.code === g.metric);
  const factor = m ? goalUnit(m, units).factor : 1;
  return { target: String(Math.round((g.target / factor) * 100) / 100), ends: g.ends_on ?? "", status: g.lifecycle === "paused" ? "paused" : "active" };
}
export function checkGoalEdit(f: GoalEditForm, g: GoalDetail, metrics: GoalMetricOption[], units: Units = "metric"): Checked<object> {
  const c = checkGoal({ metric: g.metric, direction: g.comparator, target: f.target, starts: g.starts_on, ends: f.ends }, metrics, units);
  if (!c.ok) return c;
  const b = c.body as { title: string; target: number; ends_on?: string };
  // The target as shown (rounded, maybe in miles or lb) left alone keeps the stored value exactly.
  if (f.target.trim() === goalEditForm(g, metrics, units).target) b.target = g.target;
  return {
    ok: true,
    body: {
      // A new target renames the goal the way New Goal names it; otherwise the user's title stays.
      ...(b.target !== g.target ? { title: b.title } : {}),
      target: b.target,
      ends_on: f.ends || null,
      ...(g.lifecycle === "active" || g.lifecycle === "paused" ? { lifecycle: f.status } : {}),
    },
  };
}

/** Daily targets (Figma 214:2058), as typed: sleep in hours; the API stores minutes. */
export interface TargetsForm { steps: string; move: string; exercise: string; stand: string; sleep: string }
export const targetsForm = (t: Targets): TargetsForm => ({
  steps: String(t.daily_steps_goal),
  move: String(t.move_kcal_goal),
  exercise: String(t.exercise_min_goal),
  stand: String(t.stand_hours_goal),
  sleep: String(Math.round((t.sleep_goal_min / 60) * 10) / 10),
});
export function checkTargets(f: TargetsForm): Checked<Targets> {
  const steps = num(f.steps), move = num(f.move), exercise = num(f.exercise), stand = num(f.stand), sleep = num(f.sleep);
  if (!between(steps, 1000, 100000)) return fail("Steps should be between 1,000 and 100,000.");
  if (!between(move, 50, 5000)) return fail("Move should be between 50 and 5,000 kcal.");
  if (!between(exercise, 5, 600)) return fail("Exercise should be between 5 and 600 minutes.");
  if (!between(stand, 1, 24)) return fail("Stand should be between 1 and 24 hours.");
  if (!between(sleep, 3, 15)) return fail("Sleep should be between 3 and 15 hours.");
  return {
    ok: true,
    body: { daily_steps_goal: Math.round(steps!), move_kcal_goal: Math.round(move!), exercise_min_goal: Math.round(exercise!), stand_hours_goal: Math.round(stand!), sleep_goal_min: Math.round(sleep! * 60) },
  };
}

// ---------------------------------------------------------------- Log exercise (Figma 215:1843)

/** One set as typed: reps and/or seconds (a hold like a plank), weight optional. */
export interface SetForm { reps: string; weight: string; seconds: string }
export function checkSet(f: SetForm): Checked<{ reps: number | null; weight_kg: number | null; duration_s: number | null }> {
  const reps = num(f.reps), weight = num(f.weight), seconds = num(f.seconds);
  if (reps === null && seconds === null) return fail("Enter the reps, or the time for a hold.");
  if (reps !== null && !(Number.isInteger(reps) && between(reps, 1, 1000))) return fail("Reps should be a whole number from 1 to 1,000.");
  if (weight !== null && !between(weight, 0, 1000)) return fail("Weight should be between 0 and 1,000 kg.");
  if (seconds !== null && !(Number.isInteger(seconds) && between(seconds, 1, 86400))) return fail("Time should be a whole number of seconds.");
  return { ok: true, body: { reps, weight_kg: weight, duration_s: seconds } };
}
/** "10 reps · 60 kg", "45 s", "12 reps" */
export const setSummary = (s: { reps: number | null; weight_kg: number | null; duration_s: number | null }) =>
  [s.reps !== null ? `${s.reps} reps` : null, s.duration_s !== null ? `${s.duration_s} s` : null, s.weight_kg ? `${s.weight_kg} kg` : null].filter(Boolean).join(" · ");

export interface ExerciseLogForm { date: string; time: string; minutes: string; effort: string }
/** POST /me/workouts body: a strength session named after the exercise, with its sets. */
export function checkExerciseLog(exercise: string, f: ExerciseLogForm, sets: { reps: number | null; weight_kg: number | null; duration_s: number | null }[], tz: string): Checked<object> {
  if (sets.length === 0) return fail("Add at least one set.");
  const minutes = num(f.minutes);
  if (!between(minutes, 1, 1440)) return fail("Enter how long it took, in minutes.");
  const at = zonedIso(f.date, f.time, tz);
  if (Date.parse(at) > Date.now() + 5 * 60000) return fail("That start time is in the future.");
  return {
    ok: true,
    body: {
      type: "strength",
      kind: "strength",
      title: exercise,
      started_at: at,
      duration_s: Math.round(minutes! * 60),
      ...(f.effort ? { rpe: Number(f.effort) } : {}),
      source: "manual",
      sets: sets.map((s) => ({ exercise, ...(s.reps !== null ? { reps: s.reps } : {}), ...(s.weight_kg !== null ? { weight_kg: s.weight_kg } : {}), ...(s.duration_s !== null ? { duration_s: s.duration_s } : {}) })),
    },
  };
}

// ---------------------------------------------------------------- Settings › Profile

export const PHOTO_TYPES = ["image/jpeg", "image/png", "image/webp"];
export function checkPhoto(file: { type: string; size: number } | null): Checked<true> {
  if (!file) return fail("Choose a photo.");
  if (!PHOTO_TYPES.includes(file.type)) return fail("Choose a JPEG, PNG or WebP image.");
  if (file.size > 2 * 1024 * 1024) return fail("That photo is over 2 MB. Choose a smaller one.");
  return { ok: true, body: true };
}

/** "178 cm", "178", "5 ft 10 in", "5'10\"", "5' 10" → centimetres (one decimal); null when it isn't a height. */
export function parseHeight(text: string, units: "metric" | "imperial"): number | null {
  const t = text.trim().toLowerCase();
  const ftIn = t.match(/^(\d)\s*(?:ft|feet|foot|')\s*(?:(\d{1,2}(?:\.\d+)?)\s*(?:in|inches|"|'')?)?$/);
  let cm: number | null = null;
  if (ftIn) cm = (Number(ftIn[1]) * 12 + Number(ftIn[2] ?? 0)) * 2.54;
  else {
    const m = t.match(/^(\d+(?:\.\d+)?)\s*(cm|in|inches)?$/);
    if (m) cm = m[2] === "cm" ? Number(m[1]) : m[2] ? Number(m[1]) * 2.54 : units === "metric" || Number(m[1]) > 96 ? Number(m[1]) : Number(m[1]) * 2.54;
  }
  return cm !== null && cm >= 50 && cm <= 272 ? Math.round(cm * 10) / 10 : null;
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
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(f.email.trim())) return fail("Enter their email address. They sign in with it to see what you share.");
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
