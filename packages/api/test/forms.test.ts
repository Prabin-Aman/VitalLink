import { describe, expect, it } from "vitest";
import {
  addDays,
  checkAppointment,
  checkContact,
  checkExerciseLog,
  checkFood,
  checkGoal,
  checkGoalEdit,
  checkLabPanel,
  checkLabTest,
  checkMedication,
  checkPhoto,
  checkMedicationEdit,
  checkNewFood,
  checkNutritionTargets,
  checkTargets,
  checkNote,
  checkReading,
  checkRefill,
  checkSet,
  checkSignUp,
  checkUpload,
  checkWorkout,
  dateLabel,
  dateTimeIn,
  foodEstimate,
  goalEditForm,
  medicationForm,
  mock,
  parseHeight,
  passwordNeeds,
  setSummary,
  timeIn,
  targetsForm,
  titleFromFile,
  todayIn,
  toE164,
  zonedIso,
} from "../src";

const TZ = "America/Chicago";
const body = <T>(c: { ok: true; body: T } | { ok: false; error: string }) => {
  if (!c.ok) throw new Error(`expected ok, got: ${c.error}`);
  return c.body;
};
const error = (c: { ok: boolean; error?: string }) => (c.ok ? null : c.error);

describe("time in the account's zone", () => {
  it("uses the zone's offset on that date, not the browser's", () => {
    expect(zonedIso("2026-10-27", "08:40", TZ)).toBe("2026-10-27T08:40:00-05:00"); // daylight time
    expect(zonedIso("2026-12-01", "09:00", TZ)).toBe("2026-12-01T09:00:00-06:00"); // standard time
    expect(zonedIso("2026-10-27", "08:40", "Asia/Kathmandu")).toBe("2026-10-27T08:40:00+05:45");
    expect(zonedIso("2026-07-01", "00:05", "UTC")).toBe("2026-07-01T00:05:00+00:00");
  });

  it("gives today and a 5-minute-aligned time in the zone", () => {
    const now = Date.parse("2026-10-28T04:58:00Z"); // 23:58 the evening before in Dallas
    expect(todayIn(TZ, now)).toBe("2026-10-27");
    expect(timeIn(TZ, now)).toBe("23:55");
    expect(todayIn("Asia/Kathmandu", now)).toBe("2026-10-28");
  });

  it("round-trips an instant to the zone's date and time", () => {
    expect(dateTimeIn("2026-11-04T20:30:00Z", TZ)).toEqual({ date: "2026-11-04", time: "14:30" });
  });
});

describe("Add Reading", () => {
  const f = { metric: "blood_pressure", value: "121", value2: "79", date: "2026-10-01", time: "08:40" };

  it("sends blood pressure as a systolic and a diastolic reading at the same moment", () => {
    expect(body(checkReading(f, TZ))).toEqual([
      { metric: "bp_systolic", value: 121, at: "2026-10-01T08:40:00-05:00" },
      { metric: "bp_diastolic", value: 79, at: "2026-10-01T08:40:00-05:00" },
    ]);
  });

  it("refuses impossible or future values with a message", () => {
    expect(error(checkReading({ ...f, value2: "130" }, TZ))).toBe("Diastolic should be lower than systolic.");
    expect(error(checkReading({ ...f, value: "" }, TZ))).toMatch(/Systolic should be between/);
    expect(error(checkReading({ ...f, metric: "heart_rate", value: "300" }, TZ))).toBe("Heart rate should be between 25 and 250 bpm.");
    expect(error(checkReading({ ...f, date: "2099-01-01" }, TZ))).toBe("That time is in the future.");
  });

  it("accepts decimals and thousands separators for single readings", () => {
    expect(body(checkReading({ ...f, metric: "weight", value: "72.4" }, TZ))[0]).toMatchObject({ metric: "weight", value: 72.4 });
  });
});

describe("Log Food", () => {
  const food = { kcal: 165, protein_g: 31, carbs_g: 0, fat_g: 3.6 };
  it("needs a picked food and a sensible amount", () => {
    const f = { slot: "lunch", foodId: null, grams: "120", date: "2026-10-01", time: "12:30" };
    expect(error(checkFood(f, TZ))).toBe("Search for a food and pick it from the list.");
    expect(error(checkFood({ ...f, foodId: "x", grams: "0" }, TZ))).toMatch(/Amount should be/);
    expect(body(checkFood({ ...f, foodId: "x" }, TZ))).toEqual({
      slot: "lunch",
      eaten_at: "2026-10-01T12:30:00-05:00",
      items: [{ food_id: "x", quantity_g: 120 }],
    });
  });
  it("estimates macros from per-100 g values", () => {
    expect(foodEstimate(food, "120")).toBe("≈ 198 kcal · 37 g protein · 0 g carbs · 4 g fat");
    expect(foodEstimate(food, "")).toBeNull();
  });
});

describe("Log Workout", () => {
  const f = { type: "run", title: "Morning run", date: "2026-10-01", time: "07:05", minutes: "42", km: "6.2", effort: "7", avgHr: "" };
  it("converts to seconds and metres and picks the session kind", () => {
    expect(body(checkWorkout(f, TZ))).toEqual({
      type: "run",
      kind: "cardio",
      title: "Morning run",
      started_at: "2026-10-01T07:05:00-05:00",
      duration_s: 2520,
      distance_m: 6200,
      rpe: 7,
      source: "manual",
    });
    expect(body(checkWorkout({ ...f, type: "yoga", km: "" }, TZ))).toMatchObject({ kind: "mobility" });
    expect(body(checkWorkout({ ...f, type: "strength", km: "" }, TZ))).not.toHaveProperty("distance_m");
  });
  it("refuses a missing duration or a future start", () => {
    expect(error(checkWorkout({ ...f, minutes: "" }, TZ))).toMatch(/Duration should be/);
    expect(error(checkWorkout({ ...f, date: "2099-01-01" }, TZ))).toBe("That start time is in the future.");
  });
});

describe("Add Medication", () => {
  const f = { name: " Metformin ", form: "tablet", strength: "500 mg", times: ["20:00", "08:00"], days: ["1", "2", "3", "4", "5", "6", "7"], prescriber: "", refills: "", instructions: "" };
  it("sends every day as the default and sorts dose times", () => {
    const b = body(checkMedication(f)) as Record<string, unknown>;
    expect(b).toMatchObject({ name: "Metformin", times: ["08:00", "20:00"], prescriber: null });
    expect(b).not.toHaveProperty("days");
    expect(body(checkMedication({ ...f, days: ["6", "1"] }))).toMatchObject({ days: [1, 6] });
  });
  it("needs a strength, a time and a day", () => {
    expect(error(checkMedication({ ...f, strength: "" }))).toMatch(/strength/);
    expect(error(checkMedication({ ...f, times: [] }))).toMatch(/dose time/);
    expect(error(checkMedication({ ...f, days: [] }))).toMatch(/day/);
  });
});

describe("Book Appointment", () => {
  const f = { clinician: "Dr. Priya Shah", specialty: "", mode: "in_person", date: "2026-11-04", time: "14:30", length: "20", location: "", notes: "" };
  it("needs a location for in-person visits only", () => {
    expect(error(checkAppointment(f, TZ))).toBe("Enter where the visit is.");
    expect(body(checkAppointment({ ...f, mode: "telehealth" }, TZ))).toMatchObject({ starts_at: "2026-11-04T14:30:00-06:00", duration_min: 20, notes: null });
  });
});

describe("New Goal", () => {
  const metrics = mock.goalMetrics;
  it("types sleep in hours and distance in km, and stores the API's units", () => {
    const f = { metric: "sleep", direction: "at_least", target: "7.5", starts: "2026-10-28", ends: "" };
    expect(body(checkGoal(f, metrics))).toMatchObject({ title: "Sleep: 7.5 hours a day", target: 450, area: "recovery", comparator: "at_least" });
    expect(body(checkGoal({ ...f, metric: "distance", target: "5" }, metrics))).toMatchObject({ target: 5000 });
    expect(body(checkGoal({ ...f, metric: "weight", direction: "at_most", target: "70" }, metrics))).toMatchObject({ title: "Weight: At most 70 kg" });
  });
  it("refuses a zero target or an end before the start", () => {
    const f = { metric: "steps", direction: "at_least", target: "0", starts: "2026-10-28", ends: "" };
    expect(error(checkGoal(f, metrics))).toBe("Enter a target above zero.");
    expect(error(checkGoal({ ...f, target: "8000", ends: "2026-10-01" }, metrics))).toMatch(/end date/);
  });
});

describe("Upload Document", () => {
  const f = { title: "Chest X-ray", type: "imaging", date: "2026-10-12", source: "" };
  it("accepts only the API's file types and sizes", () => {
    expect(error(checkUpload(f, null, "2026-10-28"))).toBe("Choose a file to upload.");
    expect(error(checkUpload(f, { type: "text/plain", size: 10 }, "2026-10-28"))).toMatch(/PDF, JPG, PNG or HEIC/);
    expect(error(checkUpload(f, { type: "application/pdf", size: 26 * 1024 * 1024 }, "2026-10-28"))).toMatch(/over 25 MB/);
    expect(error(checkUpload({ ...f, date: "2026-11-01" }, { type: "application/pdf", size: 10 }, "2026-10-28"))).toMatch(/future/);
    expect(body(checkUpload(f, { type: "image/png", size: 10 }, "2026-10-28"))).toEqual({ title: "Chest X-ray", type: "imaging", date: "2026-10-12", source: "" });
  });
  it("suggests a title from the file name", () => {
    expect(titleFromFile("chest_xray-report.pdf")).toBe("Chest xray report");
  });
});

describe("Add a note", () => {
  it("trims, clears when blank and caps at 200", () => {
    expect(body(checkNote("  Recheck in 3 months  "))).toEqual({ note: "Recheck in 3 months" });
    expect(body(checkNote("   "))).toEqual({ note: null });
    expect(error(checkNote("x".repeat(201)))).toMatch(/under 200/);
  });
});

describe("Edit Emergency Info", () => {
  it("formats US numbers as E.164", () => {
    expect(toE164("(214) 555-0148")).toBe("+12145550148");
    expect(toE164("1 214 555 0148")).toBe("+12145550148");
    expect(toE164("+9779812345678")).toBe("+9779812345678");
    expect(toE164("555-0148")).toBeNull();
  });
  it("adds a contact only when one is typed, and checks it", () => {
    expect(checkContact({ name: "", relation: "", phone: "" })).toBeNull();
    expect(error(checkContact({ name: "Sam", relation: "", phone: "" })!)).toMatch(/related/);
    expect(body(checkContact({ name: "Sam Carter", relation: "Brother", phone: "(214) 555-0199" })!)).toEqual({ name: "Sam Carter", relation: "Brother", phone: "+12145550199" });
  });
});

describe("Edit Medication, Edit Goal, Daily targets", () => {
  const med = { id: "m1", name: "Metformin", form: "tablet", strength: "500 mg", instructions: null, prescriber: null, refills_left: 3, refill_due_on: null, status: "active" as const, started_on: "2026-01-01", stopped_on: null, frequency: "Twice daily", schedule: [{ time: "20:00:00", days: [1, 3] }, { time: "08:00:00", days: [1, 3] }] };
  it("prefills from the stored schedule and always sends the days", () => {
    const f = medicationForm(med);
    expect(f).toMatchObject({ times: ["08:00", "20:00"], days: ["1", "3"], prescriber: "", refills: "3" });
    expect(body(checkMedicationEdit({ ...f, days: ["1", "2", "3", "4", "5", "6", "7"] }))).toMatchObject({ days: [1, 2, 3, 4, 5, 6, 7], refills_left: 3, prescriber: "" });
  });

  const metrics = mock.goalMetrics;
  const goal = { id: "g1", title: "Sleep: 7.5 hours a day", short_title: "Sleep", area: "recovery" as const, kind: "daily_total" as const, metric: "sleep", comparator: "at_least" as const, target: 450, baseline: null, starts_on: "2026-10-01", ends_on: null, lifecycle: "active" as const, achieved_at: null };
  it("edits a goal's target in the units it was typed in, and renames it", () => {
    const f = goalEditForm(goal, metrics);
    expect(f).toEqual({ target: "7.5", ends: "", status: "active" });
    expect(body(checkGoalEdit({ ...f, target: "8", status: "paused" }, goal, metrics))).toEqual({ title: "Sleep: 8 hours a day", target: 480, ends_on: null, lifecycle: "paused" });
    expect(error(checkGoalEdit({ ...f, ends: "2026-09-01" }, goal, metrics))).toMatch(/end date/);
    // An achieved goal keeps its lifecycle; an unchanged target keeps the title.
    expect(body(checkGoalEdit(f, { ...goal, lifecycle: "achieved" }, metrics))).toEqual({ target: 450, ends_on: null });
  });

  it("checks daily targets and stores sleep in minutes", () => {
    const f = targetsForm(mock.targets);
    expect(f.sleep).toBe("8");
    expect(body(checkTargets({ ...f, steps: "12,000", sleep: "7.5" }))).toMatchObject({ daily_steps_goal: 12000, sleep_goal_min: 450 });
    expect(error(checkTargets({ ...f, steps: "500" }))).toMatch(/Steps should be/);
  });
});

describe("Settings › Profile", () => {
  it("reads heights in either system", () => {
    expect(parseHeight("5 ft 10 in", "imperial")).toBe(177.8);
    expect(parseHeight(`5'10"`, "imperial")).toBe(177.8);
    expect(parseHeight("178 cm", "imperial")).toBe(178);
    expect(parseHeight("178", "metric")).toBe(178);
    expect(parseHeight("70", "imperial")).toBe(177.8); // inches
    expect(parseHeight("tall", "metric")).toBeNull();
    expect(parseHeight("400 cm", "metric")).toBeNull();
  });
  it("accepts small JPEG, PNG or WebP photos", () => {
    expect(checkPhoto({ type: "image/png", size: 1000 }).ok).toBe(true);
    expect(error(checkPhoto({ type: "image/gif", size: 1000 }))).toMatch(/JPEG, PNG or WebP/);
    expect(error(checkPhoto({ type: "image/jpeg", size: 3 * 1024 * 1024 }))).toMatch(/over 2 MB/);
  });
});

describe("Log exercise", () => {
  it("needs reps or a hold time for each set", () => {
    expect(body(checkSet({ reps: "10", weight: "60", seconds: "" }))).toEqual({ reps: 10, weight_kg: 60, duration_s: null });
    expect(body(checkSet({ reps: "", weight: "", seconds: "45" }))).toEqual({ reps: null, weight_kg: null, duration_s: 45 });
    expect(error(checkSet({ reps: "", weight: "60", seconds: "" }))).toMatch(/reps, or the time/);
    expect(error(checkSet({ reps: "2.5", weight: "", seconds: "" }))).toMatch(/whole number/);
    expect(setSummary({ reps: 10, weight_kg: 60, duration_s: null })).toBe("10 reps · 60 kg");
  });
  it("saves a strength session named after the exercise, with its sets", () => {
    const f = { date: "2026-10-01", time: "07:00", minutes: "25", effort: "7" };
    const b = body(checkExerciseLog("Barbell Bench Press", f, [{ reps: 10, weight_kg: 60, duration_s: null }, { reps: null, weight_kg: null, duration_s: 45 }], TZ));
    expect(b).toMatchObject({ type: "strength", title: "Barbell Bench Press", duration_s: 1500, rpe: 7, started_at: "2026-10-01T07:00:00-05:00" });
    expect((b as { sets: unknown[] }).sets).toEqual([
      { exercise: "Barbell Bench Press", reps: 10, weight_kg: 60 },
      { exercise: "Barbell Bench Press", duration_s: 45 },
    ]);
    expect(error(checkExerciseLog("Plank", f, [], TZ))).toBe("Add at least one set.");
    expect(error(checkExerciseLog("Plank", { ...f, minutes: "" }, [{ reps: 1, weight_kg: null, duration_s: null }], TZ))).toMatch(/how long/);
  });
});

describe("Create Account", () => {
  const f = { first: "Sudip", last: "Ghorasaini", email: "Sudip@Example.com", phone: "(214) 555-0148", password: "Correct-horse-42", confirm: "Correct-horse-42", zip: "75201", dob: "1998-01-08", height: "5 ft 10 in" };
  it("says what a password still needs", () => {
    expect(passwordNeeds("short")).toBe("Still needs 12 characters, a number and a symbol.");
    expect(passwordNeeds("longenoughpassword1")).toBe("Still needs a symbol.");
    expect(passwordNeeds("Correct-horse-42")).toBeNull();
  });
  it("builds the sign-up body and names the field that's wrong", () => {
    expect(body(checkSignUp(f, "2026-10-31"))).toEqual({ email: "sudip@example.com", password: "Correct-horse-42", first_name: "Sudip", last_name: "Ghorasaini", phone: "+12145550148", zip: "75201", date_of_birth: "1998-01-08", height_cm: 177.8 });
    expect(error(checkSignUp({ ...f, phone: "897895" }, "2026-10-31"))).toMatch(/10-digit mobile/);
    expect(error(checkSignUp({ ...f, zip: "4460" }, "2026-10-31"))).toMatch(/5-digit ZIP/);
    expect(error(checkSignUp({ ...f, height: "tall" }, "2026-10-31"))).toMatch(/height/);
    expect(error(checkSignUp({ ...f, dob: "2030-01-01" }, "2026-10-31"))).toMatch(/date of birth/);
    expect(body(checkSignUp({ ...f, phone: "", zip: "", dob: "", height: "" }, "2026-10-31"))).not.toHaveProperty("phone");
  });
});

describe("Check-ins, refills, nutrition targets, foods and lab results", () => {
  it("logs mood as a 1–5 score and splits a mindful session into 5-minute buckets", () => {
    const f = { metric: "mood", value: "4", value2: "", date: "2026-10-01", time: "21:00" };
    expect(body(checkReading(f, TZ))).toEqual([{ metric: "mood", value: 4, at: "2026-10-01T21:00:00-05:00" }]);
    expect(error(checkReading({ ...f, value: "7" }, TZ))).toBe("Mood should be between 1 and 5.");
    const m = body(checkReading({ ...f, metric: "mindful_minutes", value: "12" }, TZ));
    expect(m.map((r) => r.value)).toEqual([5, 5, 2]);
    expect(m.map((r) => r.at)).toEqual(["2026-10-02T02:00:00.000Z", "2026-10-02T02:05:00.000Z", "2026-10-02T02:10:00.000Z"]);
  });

  it("records a refill", () => {
    expect(body(checkRefill({ refills: "2", due: "2026-11-30" }, "2026-10-31"))).toEqual({ refills_left: 2, refill_due_on: "2026-11-30" });
    expect(body(checkRefill({ refills: "0", due: "" }, "2026-10-31"))).toEqual({ refills_left: 0, refill_due_on: null });
    expect(error(checkRefill({ refills: "1.5", due: "" }, "2026-10-31"))).toMatch(/whole number/);
    expect(error(checkRefill({ refills: "1", due: "2026-10-01" }, "2026-10-31"))).toMatch(/passed/);
  });

  it("checks nutrition targets", () => {
    const f = { kcal: "2,200", protein: "150", carbs: "250", fat: "70", fibre: "30", water: "2500", glass: "250", weekly: "-0.25" };
    expect(body(checkNutritionTargets(f))).toEqual({ kcal: 2200, protein_g: 150, carbs_g: 250, fat_g: 70, fibre_g: 30, water_ml: 2500, glass_ml: 250, weekly_target_kg: -0.25 });
    expect(error(checkNutritionTargets({ ...f, kcal: "500" }))).toMatch(/Calories/);
    expect(error(checkNutritionTargets({ ...f, weekly: "-2" }))).toMatch(/Weekly/);
  });

  it("adds a food from its label", () => {
    const f = { name: " Greek yogurt ", brand: "", kcal: "59", serving: "170", protein: "10", carbs: "3.6", fat: "0.4", fibre: "" };
    expect(body(checkNewFood(f))).toEqual({ name: "Greek yogurt", kcal: 59, protein_g: 10, carbs_g: 3.6, fat_g: 0.4, serving_g: 170 });
    expect(error(checkNewFood({ ...f, protein: "60", carbs: "50" }))).toMatch(/more than 100 g/);
    expect(error(checkNewFood({ ...f, name: "" }))).toMatch(/name/);
  });

  it("types in lab results, never blood type", () => {
    const t = body(checkLabTest({ name: "Hemoglobin", value: "14.2", unit: "g/dL", low: "13", high: "17" }));
    expect(t).toEqual({ name: "Hemoglobin", value: 14.2, unit: "g/dL", low: 13, high: 17, decimals: 1 });
    expect(error(checkLabTest({ name: "Blood type", value: "1", unit: "", low: "0", high: "1" }))).toMatch(/blood type/);
    expect(error(checkLabTest({ name: "Platelets", value: "245", unit: "", low: "400", high: "150" }))).toMatch(/below/);
    const p = body(checkLabPanel({ name: "Complete Blood Count", short: "CBC", date: "2026-10-27", laboratory: "" }, [t], TZ, "2026-10-31"));
    expect(p).toMatchObject({ short_name: "CBC", collected_at: "2026-10-27T09:00:00-05:00", results: [t] });
    expect(error(checkLabPanel({ name: "CBC", short: "CBC", date: "2026-10-27", laboratory: "" }, [], TZ, "2026-10-31"))).toMatch(/at least one/);
  });
});
