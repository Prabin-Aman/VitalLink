import { describe, expect, it } from "vitest";
import {
  checkAppointment,
  checkContact,
  checkFood,
  checkGoal,
  checkMedication,
  checkReading,
  checkUpload,
  checkWorkout,
  dateTimeIn,
  foodEstimate,
  mock,
  timeIn,
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
    expect(body(checkGoal(f, metrics))).toMatchObject({ title: "Sleep — 7.5 hours a day", target: 450, area: "recovery", comparator: "at_least" });
    expect(body(checkGoal({ ...f, metric: "distance", target: "5" }, metrics))).toMatchObject({ target: 5000 });
    expect(body(checkGoal({ ...f, metric: "weight", direction: "at_most", target: "70" }, metrics))).toMatchObject({ title: "Weight — At most 70 kg" });
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
