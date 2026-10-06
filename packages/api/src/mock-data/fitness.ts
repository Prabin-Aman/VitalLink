// Sample training, nutrition and goal data from the Fitness frames
// (web 129:1142, 131:100, 131:644 · mobile 134:2, 134:216, 134:395).
// Week shown: Mon 7 – Sun 13 Sep 2026, America/Chicago.
import type { GoalsOverview, NutritionDay, Training } from "../types";

export const training: Training = {
  total_sessions: 182,
  week: {
    sessions: 4,
    target_sessions: 5,
    minutes: 192,
    volume_kg: 24150,
    active_kcal: 3412,
    streak_days: 5,
    longest_streak_days: 14,
    source: "Apple Watch",
    load: [
      { strength: 48, mobility: 10 },
      { cardio: 28, mobility: 12 },
      { strength: 52, mobility: 8 },
      {},
      { cardio: 35 },
      { cardio: 42, mobility: 15 },
      { mobility: 20 },
    ],
  },
  sessions: [
    { id: "t5", type: "strength", kind: "strength", title: "Upper Body Strength", started_at: "2026-09-12T19:05:00-05:00", duration_s: 48 * 60, exercises: 6, sets: 24, volume_kg: 8420, distance_m: null, pace_s_per_km: null, kcal: null, avg_hr: null, rpe: 8, zone: 3, focus: null, pr: null },
    { id: "t4", type: "run", kind: "cardio", title: "Outdoor Run", started_at: "2026-09-12T06:40:00-05:00", duration_s: 42 * 60, exercises: null, sets: null, volume_kg: null, distance_m: 7400, pace_s_per_km: 341, kcal: 341, avg_hr: 156, rpe: null, zone: 4, focus: null, pr: null },
    { id: "t3", type: "walk", kind: "mobility", title: "Mobility & Stretch", started_at: "2026-09-11T21:15:00-05:00", duration_s: 15 * 60, exercises: null, sets: null, volume_kg: null, distance_m: null, pace_s_per_km: null, kcal: null, avg_hr: null, rpe: null, zone: 1, focus: "Hips, thoracic, hamstrings", pr: null },
    { id: "t2", type: "cycle", kind: "cardio", title: "Indoor Cycle", started_at: "2026-09-11T18:30:00-05:00", duration_s: 35 * 60, exercises: null, sets: null, volume_kg: null, distance_m: 15100, pace_s_per_km: null, kcal: 412, avg_hr: 138, rpe: null, zone: 3, focus: null, pr: null },
    { id: "t1", type: "strength", kind: "strength", title: "Lower Body Strength", started_at: "2026-09-09T19:05:00-05:00", duration_s: 52 * 60, exercises: 5, sets: 20, volume_kg: 11300, distance_m: null, pace_s_per_km: null, kcal: null, avg_hr: null, rpe: null, zone: 3, focus: null, pr: { exercise: "Squat", weight_kg: 92.5 } },
  ],
  muscles: {
    days: 14,
    groups: [
      { group: "Legs", sets: 34 },
      { group: "Back", sets: 28 },
      { group: "Chest", sets: 24 },
      { group: "Shoulders", sets: 14 },
      { group: "Arms", sets: 12 },
      { group: "Core", sets: 8 },
    ],
    note: "Core and arms are trailing. Two accessory sets would even this out.",
  },
  records: [
    { exercise: "Back Squat", achieved_at: "2026-09-09T19:40:00-05:00", weight_kg: 92.5, reps: 5, time_s: null },
    { exercise: "Deadlift", achieved_at: "2026-09-11T18:10:00-05:00", weight_kg: 120, reps: 3, time_s: null },
    { exercise: "5 km run", achieved_at: "2026-09-06T07:30:00-05:00", weight_kg: null, reps: null, time_s: 27 * 60 + 42 },
  ],
  next: { id: "p1", title: "Push Day", kind: "Strength", starts_at: "2026-09-14T18:30:00-05:00", exercises: 6, minutes: 50 },
};

export const nutrition: NutritionDay = {
  date: "2026-09-13T08:45:00-05:00",
  kcal: 1842,
  kcal_goal: 2400,
  protein_g: 128,
  carbs_g: 196,
  fat_g: 58,
  fibre_g: 22,
  goals: { protein_g: 150, carbs_g: 260, fat_g: 75, fibre_g: 30 },
  water_ml: 2100,
  water_goal_ml: 3000,
  glass_ml: 300,
  base_burn_kcal: 1780,
  active_burn_kcal: 512,
  weekly_target_kg: -0.4,
  meals: [
    { id: "n1", name: "Breakfast", eaten_at: "2026-09-13T07:10:00-05:00", items: ["Greek yoghurt", "oats", "blueberries", "honey"], kcal: 486, protein_g: 32, carbs_g: 61, fat_g: 12 },
    { id: "n2", name: "Lunch", eaten_at: "2026-09-13T12:40:00-05:00", items: ["Chicken rice bowl", "avocado", "mixed greens"], kcal: 624, protein_g: 48, carbs_g: 62, fat_g: 21 },
    { id: "n3", name: "Snack", eaten_at: "2026-09-13T15:50:00-05:00", items: ["Whey shake", "banana"], kcal: 284, protein_g: 28, carbs_g: 34, fat_g: 4 },
    { id: "n4", name: "Dinner", eaten_at: "2026-09-13T19:30:00-05:00", items: ["Salmon", "sweet potato", "broccoli"], kcal: 448, protein_g: 20, carbs_g: 39, fat_g: 21 },
  ],
};

const T = true;
const F = false;

export const goalsOverview: GoalsOverview = {
  goals: [
    { id: "tg1", title: "Train 5 times per week", short_title: "Train 5× per week", area: "Training", status: "on_track", progress: 0.8, detail: "4 of 5 sessions · 2 days left", short_detail: "4 of 5 · 2 days left" },
    { id: "tg2", title: "Walk 10,000 steps daily", short_title: "10,000 steps daily", area: "Activity", status: "on_track", progress: 0.84, detail: "8,412 average this week", short_detail: "8,412 average" },
    { id: "tg3", title: "Sleep 8 hours", short_title: "Sleep 8 hours", area: "Recovery", status: "at_risk", progress: 0.9, detail: "7h 12m average · 48 min short", short_detail: "7h 12m average" },
    { id: "tg4", title: "Protein 150 g daily", short_title: "Protein 150 g", area: "Nutrition", status: "on_track", progress: 0.85, detail: "128 g average over 7 days", short_detail: "128 g average" },
    { id: "tg5", title: "Body fat to 17%", short_title: "Body fat to 17%", area: "Body composition", status: "at_risk", progress: 0.62, detail: "18.4% · down 0.6 in 8 weeks", short_detail: "18.4% · down 0.6" },
    { id: "tg6", title: "Resting heart rate under 60", short_title: "Resting HR under 60", area: "Heart", status: "achieved", progress: 1, detail: "58 BPM · held for 3 weeks", short_detail: "58 BPM · 3 weeks" },
  ],
  achieved_this_month: 2,
  longest_streak: { days: 14, label: "step goal" },
  consistency: [
    [T, T, F, T, T, T, F],
    [T, T, T, F, T, T, F],
    [T, F, T, T, T, F, T],
    [T, T, T, T, F, T, T],
    [T, T, T, F, T, T, F],
  ],
  recent: [
    { title: "Resting HR under 60", achieved_at: "2026-08-30T09:00:00-05:00" },
    { title: "Run 5 km without stopping", achieved_at: "2026-08-09T07:30:00-05:00" },
  ],
};
