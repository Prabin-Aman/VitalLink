// Sample data taken from the VitalLink Figma designs (Dallas-area demo user).
// Week shown: Mon 7 – Sun 13 Sep 2026, America/Chicago (UTC−05:00).
import type {
  AccessLogEntry,
  ConditionItem,
  ExerciseItem,
  GoalMetricOption,
  NutritionDay,
  TrainingPlan,
  Dashboard,
  Goal,
  Me,
  Medication,
  Reading,
  Series,
  Share,
  Workout,
} from "./types";
import { account, appointments, conversations, devices, emergency, family, library, medicationsOverview, notifications, sync } from "./mock-data/care";
import { goalsOverview, nutrition, training } from "./mock-data/fitness";
import { bodyTab, digitalTab, healthOverview, heartTab, mentalTab, respiratoryTab, sleepTab, vitalsTab } from "./mock-data/health";
import { labPanels, recordsOverview } from "./mock-data/records";

const day = (d: number) => `2026-09-${String(d).padStart(2, "0")}T00:00:00-05:00`;
const week = [7, 8, 9, 10, 11, 12, 13];

const me: Me = {
  id: "8f6c2e1a-0000-4000-8000-000000000001",
  email: "alex.carter@email.com",
  display_name: "Alex Carter",
  timezone: "America/Chicago",
};

const dashboard: Dashboard = {
  generated_at: "2026-09-13T08:45:00-05:00",
  health_score: 82,
  health_score_delta_7d: 3,
  metrics: [
    { metric: "heart_rate", unit: "bpm", latest: 72, latest_at: "2026-09-13T08:42:00-05:00", avg_7d: 71, range: "normal" },
    { metric: "blood_pressure", unit: "mmHg", latest: null, latest_at: "2026-09-13T08:42:00-05:00", avg_7d: null, range: "normal" },
    { metric: "oxygen_saturation", unit: "%", latest: 98, latest_at: "2026-09-13T08:40:00-05:00", avg_7d: 98, range: "normal" },
    { metric: "weight", unit: "kg", latest: 72.4, latest_at: "2026-09-12T07:30:00-05:00", avg_7d: 72.5, range: "normal" },
    { metric: "glucose", unit: "mg/dL", latest: 92, latest_at: "2026-09-13T07:15:00-05:00", avg_7d: 94, range: "normal" },
  ],
};

const steps: Series = {
  metric: "steps",
  unit: "count",
  interval: "day",
  points: [9240, 6810, 11430, 7520, 12890, 4610, 8412].map((value, i) => ({
    bucket: day(week[i]),
    value,
    samples: 288,
  })),
};

const weekSum = (metric: string, unit: string, total: number): Series => ({
  metric,
  unit,
  interval: "week",
  points: [{ bucket: day(7), value: total, samples: 7 }],
});

const bp = (vals: number[], metric: string): Series => ({
  metric,
  unit: "mmHg",
  interval: "day",
  points: vals.map((value, i) => ({ bucket: day(week[i]), value, samples: 2 })),
});

const workouts: Workout[] = [
  { id: "w4", type: "walk", title: "Outdoor Walk", started_at: "2026-09-13T07:12:00-05:00", duration_s: 42 * 60, distance_m: 3380, kcal: 188, avg_hr: 112 },
  { id: "w3", type: "cycle", title: "Indoor Cycle", started_at: "2026-09-11T18:30:00-05:00", duration_s: 35 * 60, distance_m: 15128, kcal: 412, avg_hr: 138 },
  { id: "w2", type: "strength", title: "Strength Training", started_at: "2026-09-09T19:05:00-05:00", duration_s: 48 * 60, distance_m: null, kcal: 286, avg_hr: 121 },
  { id: "w1", type: "run", title: "Outdoor Run", started_at: "2026-09-08T06:40:00-05:00", duration_s: 28 * 60, distance_m: 5150, kcal: 341, avg_hr: 156 },
];

const goals: Goal[] = [
  { id: "g1", metric: "steps", label: "Daily steps", current: 8412, target: 10000, unit: "" },
  { id: "g2", metric: "exercise_minutes", label: "Exercise minutes", current: 34, target: 40, unit: "" },
  { id: "g3", metric: "stand_hours", label: "Stand hours", current: 9, target: 12, unit: "" },
  { id: "g4", metric: "distance", label: "Weekly distance", current: 24.8, target: 30, unit: "mi" },
];

const medications: Medication[] = [
  { id: "m1@08:00", medication_id: "m1", name: "Metformin", dosage: "500mg", status: "taken", at: "2026-09-13T08:00:00-05:00" },
  { id: "m2@14:00", medication_id: "m2", name: "Atorvastatin", dosage: "10mg", status: "due", at: "2026-09-13T14:00:00-05:00" },
  { id: "m3@20:00", medication_id: "m3", name: "Vitamin D3", dosage: "", status: "due", at: "2026-09-13T20:00:00-05:00" },
];

const shares: Share[] = [
  { id: "s1", grantee_label: "Dr. Laura Bennett", grantee_email: "dr.laura.bennett@example.com", grantee_kind: "doctor", scopes: ["vitals", "records"], purpose: "Treatment", starts_at: "2026-08-01T00:00:00-05:00", expires_at: "2026-12-01T00:00:00-06:00", active: true },
  { id: "s2", grantee_label: "Dr. Marcus Hale", grantee_email: "dr.marcus.hale@example.com", grantee_kind: "doctor", scopes: ["records"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: "2026-10-31T00:00:00-05:00", active: true },
  { id: "s3", grantee_label: "Dr. Priya Raman", grantee_email: "dr.priya.raman@example.com", grantee_kind: "doctor", scopes: ["vitals"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: null, active: true },
  { id: "s4", grantee_label: "Dr. Chidi Okafor", grantee_email: "dr.chidi.okafor@example.com", grantee_kind: "doctor", scopes: ["activity"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: null, active: true },
  { id: "s5", grantee_label: "Trinity Valley", grantee_email: "trinity.valley@example.com", grantee_kind: "hospital", scopes: ["vitals", "records"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: null, active: true },
  { id: "s6", grantee_label: "Oak Cliff", grantee_email: "oak.cliff@example.com", grantee_kind: "hospital", scopes: ["records"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: null, active: true },
  { id: "s7", grantee_label: "Susan", grantee_email: "susan@example.com", grantee_kind: "family", scopes: ["activity", "sleep"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: null, active: true },
  { id: "s8", grantee_label: "Ray", grantee_email: "ray@example.com", grantee_kind: "family", scopes: ["activity"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: null, active: true },
  { id: "s9", grantee_label: "Emma", grantee_email: "emma@example.com", grantee_kind: "family", scopes: ["activity"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: null, active: true },
  { id: "s10", grantee_label: "MyFitness", grantee_email: "myfitness@example.com", grantee_kind: "app", scopes: ["activity"], purpose: "", starts_at: "2026-08-01T00:00:00-05:00", expires_at: null, active: true },
];

const accessLog: AccessLogEntry[] = [
  { at: "2026-09-13T09:12:00-05:00", actor: "Dr. Laura Bennett", actor_detail: "Cardiology · Trinity Valley", accessed: "Lab results, medications", purpose: "Treatment", status: "authorised", share_id: "s1", share_active: true },
  { at: "2026-09-11T14:41:00-05:00", actor: "Trinity Valley Medical Center", actor_detail: "Hospital", accessed: "Full record", purpose: "Admission check-in", status: "authorised", share_id: "s5", share_active: true },
  { at: "2026-09-09T11:05:00-05:00", actor: "Dr. Marcus Hale", actor_detail: "Internal Medicine", accessed: "Medication list", purpose: "Refill approval", status: "authorised", share_id: "s2", share_active: true },
  { at: "2026-09-08T06:30:00-05:00", actor: "MyFitness", actor_detail: "Third-party app", accessed: "Activity, steps", purpose: "App sync", status: "limited", share_id: "s10", share_active: true },
  { at: "2026-09-02T01:17:00-05:00", actor: "Unknown device", actor_detail: "Sign-in attempt · Fort Worth, TX", accessed: "None — blocked", purpose: "Failed authentication", status: "blocked", share_id: null, share_active: false },
];

export const mock = {
  me,
  dashboard,
  targets: { daily_steps_goal: 10000, move_kcal_goal: 500, exercise_min_goal: 30, stand_hours_goal: 12, sleep_goal_min: 480 },
  latest: [
    { metric: "blood_pressure", value: 118, value2: 76, unit: "mmHg", at: "2026-09-13T08:42:00-05:00", assessment: "Normal" },
  ] as Reading[],
  series: {
    steps,
    distance: weekSum("distance", "m", 39911.7), // 24.8 mi
    flights_climbed: weekSum("flights_climbed", "count", 71),
    active_energy: weekSum("active_energy", "kcal", 3412),
    exercise_minutes: weekSum("exercise_minutes", "min", 196),
  } as Record<string, Series>,
  bloodPressure: {
    systolic: bp([121, 118, 125, 116, 120, 117, 118], "blood_pressure_systolic"),
    diastolic: bp([77, 75, 80, 74, 78, 76, 76], "blood_pressure_diastolic"),
  },
  workouts,
  goals,
  streaks: [
    { label: "Move ring closed", days: 5 },
    { label: "Longest this year", days: 14 },
    { label: "Stood every hour", days: 2 },
  ],
  medications,
  latestLab: {
    panel_id: "cbc",
    panel: "Complete Blood Count",
    collected_at: "2026-09-11T08:30:00-05:00",
    result: { name: "Hemoglobin", value: 14.2, unit: "g/dL", low: 13, high: 17, previous: 14.0, decimals: 1, interpretation: "normal" as const },
  },
  shares,
  accessLog,
  privacy: { emergency_on_lock_screen: true, devices: { total: 5, syncing: 4, overdue: 1 } },
  training,
  nutrition,
  /** A different-looking sample day for the Week and Meals tabs (same meals, scaled by the date). */
  nutritionOn: (date: string): NutritionDay => {
    if (date === nutrition.date.slice(0, 10)) return nutrition;
    const d = Number(date.slice(8, 10));
    const f = 0.95 + ((d * 37) % 41) / 100;
    const r = (n: number) => Math.round(n * f);
    return {
      ...nutrition,
      date: `${date}T00:00:00-05:00`,
      kcal: r(nutrition.kcal),
      protein_g: r(nutrition.protein_g),
      carbs_g: r(nutrition.carbs_g),
      fat_g: r(nutrition.fat_g),
      water_ml: Math.round((nutrition.water_ml * f) / 50) * 50,
      meals: nutrition.meals.map((m) => ({ ...m, id: `${m.id}-${date}`, eaten_at: `${date}${m.eaten_at.slice(10)}`, kcal: r(m.kcal) })),
    };
  },
  exercises: [
    { id: 1, name: "Back Squat", primary_muscle: "legs" },
    { id: 2, name: "Romanian Deadlift", primary_muscle: "legs" },
    { id: 3, name: "Walking Lunge", primary_muscle: "legs" },
    { id: 4, name: "Deadlift", primary_muscle: "back" },
    { id: 5, name: "Barbell Row", primary_muscle: "back" },
    { id: 6, name: "Pull-up", primary_muscle: "back" },
    { id: 7, name: "Bench Press", primary_muscle: "chest" },
    { id: 8, name: "Push-up", primary_muscle: "chest" },
    { id: 9, name: "Overhead Press", primary_muscle: "shoulders" },
    { id: 10, name: "Lateral Raise", primary_muscle: "shoulders" },
    { id: 11, name: "Biceps Curl", primary_muscle: "arms" },
    { id: 12, name: "Plank", primary_muscle: "core" },
    { id: 13, name: "Hip Thrust", primary_muscle: "glutes" },
    { id: 14, name: "Burpee", primary_muscle: "full_body" },
  ] as ExerciseItem[],
  plans: [
    {
      id: "plan1",
      name: "Strength + 5K base",
      status: "active",
      weekly_target_sessions: 3,
      sessions: [
        { id: "p0", title: "Legs", kind: "strength", starts_at: "2026-09-11T19:05:00-05:00", exercises: 5, minutes: 52 },
        { id: "p1", title: "Push Day", kind: "strength", starts_at: "2026-09-14T18:30:00-05:00", exercises: 6, minutes: 50 },
        { id: "p2", title: "Easy Run", kind: "cardio", starts_at: "2026-09-16T07:00:00-05:00", exercises: 0, minutes: 35 },
        { id: "p3", title: "Pull Day", kind: "strength", starts_at: "2026-09-17T09:00:00-05:00", exercises: 6, minutes: 50 },
      ],
    },
  ] as TrainingPlan[],
  goalsOverview,
  medicationsOverview,
  appointments,
  conversations,
  notifications,
  family,
  /** Same list as the API's GoalMetrics.ALL. */
  goalMetrics: [
    { code: "steps", label: "Steps", unit: "count", source: "health", kind: "daily_total", area: "activity" },
    { code: "exercise_minutes", label: "Exercise", unit: "min", source: "health", kind: "daily_total", area: "activity" },
    { code: "active_energy", label: "Active energy", unit: "kcal", source: "health", kind: "daily_total", area: "activity" },
    { code: "stand_hours", label: "Stand hours", unit: "h", source: "health", kind: "daily_total", area: "activity" },
    { code: "distance", label: "Distance", unit: "m", source: "health", kind: "daily_total", area: "activity" },
    { code: "mindful_minutes", label: "Mindful minutes", unit: "min", source: "health", kind: "daily_total", area: "mind" },
    { code: "screen_time", label: "Screen time", unit: "min", source: "health", kind: "daily_total", area: "mind" },
    { code: "sleep", label: "Sleep", unit: "sleep_min", source: "sleep", kind: "daily_total", area: "recovery" },
    { code: "protein_g", label: "Protein", unit: "g", source: "nutrition", kind: "daily_total", area: "nutrition" },
    { code: "kcal", label: "Calories", unit: "kcal", source: "nutrition", kind: "daily_total", area: "nutrition" },
    { code: "water_ml", label: "Water", unit: "ml", source: "nutrition", kind: "daily_total", area: "nutrition" },
    { code: "workouts", label: "Workouts", unit: "sessions", source: "workouts", kind: "weekly_count", area: "training" },
    { code: "hrv", label: "Heart rate variability", unit: "ms", source: "health", kind: "target_value", area: "heart" },
    { code: "weight", label: "Weight", unit: "kg", source: "health", kind: "target_value", area: "body" },
    { code: "body_fat", label: "Body fat", unit: "%", source: "health", kind: "target_value", area: "body" },
    { code: "waist", label: "Waist", unit: "cm", source: "health", kind: "target_value", area: "body" },
  ] as GoalMetricOption[],
  conditions: [
    { id: "c1", label: "Type 2 diabetes", kind: "chronic", note: null, noted_on: null },
    { id: "c2", label: "Hypertension", kind: "chronic", note: null, noted_on: null },
    { id: "c3", label: "Penicillin", kind: "allergy", note: "severe", noted_on: null },
    { id: "c4", label: "Shellfish", kind: "allergy", note: null, noted_on: null },
  ] as ConditionItem[],
  emergency,
  devices,
  sync,
  library,
  account,
  recordsOverview,
  labPanels,
  health: {
    overview: healthOverview,
    vitals: vitalsTab,
    sleep: sleepTab,
    heart: heartTab,
    respiratory: respiratoryTab,
    body: bodyTab,
    mental: mentalTab,
    digital: digitalTab,
  },
};
