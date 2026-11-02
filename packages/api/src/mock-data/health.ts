// My Health tabs: Overview 77:512, Vitals 142:1340, Sleep 133:1142, Heart 133:1543,
// Respiratory 142:1733, Body Composition 133:1929, Mental 142:2090, Digital 81:834.
// "Now" is Sun 13 Sep 2026, 8:45 AM, America/Chicago.
import type { BodyTab, DigitalTab, HealthOverview, HeartTab, MentalTab, Reading, RespiratoryTab, SleepTab, VitalsTab } from "../types";

const at = (hhmm: string, day = 13) => `2026-09-${String(day).padStart(2, "0")}T${hhmm}:00-05:00`;
const r = (metric: string, value: number, unit: string, when: string | Reading["period"] | undefined, assessment: string, value2?: number): Reading => ({
  metric,
  value,
  ...(value2 !== undefined ? { value2 } : {}),
  unit,
  at: when && when.includes("T") ? when : null,
  ...(when && !when.includes("T") ? { period: when as Reading["period"] } : {}),
  assessment,
});

export const healthOverview: HealthOverview = {
  rings: { move: [512, 700], exercise: [34, 40], stand: [9, 12], source: "Apple Watch Series 9", synced_at: at("08:33") },
  sections: [
    { title: "Activity & movement", sources: "Apple Watch · iPhone", metrics: ["steps", "walking_distance", "flights", "active_energy", "exercise_minutes", "walking_speed"] },
    { title: "Heart & circulation", sources: "Apple Watch · Omron monitor", metrics: ["resting_hr", "hrv", "vo2max", "blood_pressure"] },
    { title: "Respiratory & body", sources: "Apple Watch · Withings scale", metrics: ["spo2", "respiratory_rate", "wrist_temp", "weight", "bmi", "body_fat"] },
    { title: "Sleep & digital wellbeing", sources: "Apple Watch · iPhone Screen Time", metrics: ["sleep", "deep_sleep", "screen_time", "pickups", "mindful_minutes", "headphone_audio"] },
  ],
  readings: [
    r("steps", 8412, "steps", at("08:33"), "Goal 10,000"),
    r("walking_distance", 3.7, "mi", at("08:33"), "Above average"),
    r("flights", 12, "floors", at("08:33"), "Goal 10"),
    r("active_energy", 512, "kcal", at("08:33"), "Goal 700"),
    r("exercise_minutes", 34, "min", at("08:33"), "Goal 40"),
    r("walking_speed", 2.9, "mph", at("18:00", 12), "Normal"),
    r("resting_hr", 58, "BPM", at("06:10"), "Normal"),
    r("hrv", 46, "ms", "overnight", "Normal"),
    r("vo2max", 41.2, "VO₂max", at("07:30", 11), "Above average"),
    r("blood_pressure", 118, "mmHg", at("08:42"), "Normal", 76),
    r("spo2", 98, "%", at("08:40"), "Normal"),
    r("respiratory_rate", 14, "br/min", "overnight", "Normal"),
    r("wrist_temp", 0.2, "°C dev", "overnight", "Baseline"),
    r("weight", 72.4, "kg", at("07:30", 12), "Steady"),
    r("bmi", 23.1, "", at("07:30", 12), "Healthy"),
    r("body_fat", 18.4, "%", at("07:30", 12), "Healthy"),
    r("sleep", 432, "duration", "last_night", "Goal 8h"),
    r("deep_sleep", 64, "duration", "last_night", "Below usual"),
    r("screen_time", 258, "duration", "now", "Down 22 min"),
    r("pickups", 74, "today", "now", "Up 9"),
    r("mindful_minutes", 10, "min", at("07:05"), "Goal 15"),
    r("headphone_audio", 68, "dB avg", "today", "Safe"),
  ],
  sources: [
    { name: "Apple Watch Series 9", data: "Activity, heart, sleep, oxygen", ok: true },
    { name: "iPhone 16", data: "Steps, screen time, pickups", ok: true },
    { name: "Dexcom G7", data: "Glucose", ok: true },
    { name: "Withings Scale", data: "Weight, BMI, body fat", ok: true },
    { name: "Pulse Oximeter", data: "Sync overdue · 6 days", ok: false },
  ],
  insights: [
    { text: "Deep sleep has fallen 18% over the last two weeks while screen time after 10 PM rose by 41 minutes.", tone: "watch" },
    { text: "You have closed your Move ring 5 days running, your longest streak this year.", tone: "positive" },
    { text: "Resting heart rate is 4 BPM lower than your 90-day average.", tone: "positive" },
  ],
};

export const vitalsTab: VitalsTab = {
  measured_at: at("08:42"),
  readings: [
    r("heart_rate", 72, "BPM", at("08:42"), "Normal"),
    r("blood_pressure", 118, "mmHg", at("08:42"), "Normal", 76),
    r("spo2", 98, "%", at("08:40"), "Normal"),
    r("temperature", 36.7, "°C", at("07:10"), "Normal"),
    r("respiratory_rate", 14, "br/min", "overnight", "Normal"),
    r("glucose", 92, "mg/dL", at("07:15"), "Normal"),
    r("weight", 72.4, "kg", at("07:30", 12), "Steady"),
    r("bmi", 23.1, "", undefined, "Healthy range"),
  ],
  bp30: {
    systolic: [124, 122, 121, 123, 120, 119, 121, 118, 119, 120, 117, 119, 118, 117, 118],
    diastolic: [80, 79, 78, 79, 77, 76, 77, 75, 76, 77, 75, 76, 76, 75, 76],
  },
  sources: [
    { device: "Apple Watch", data: "HR, oxygen, resp" },
    { device: "Omron BP Monitor", data: "Blood pressure" },
    { device: "Dexcom G7", data: "Glucose" },
    { device: "Withings Scale", data: "Weight, BMI" },
  ],
};

export const sleepTab: SleepTab = {
  last: { asleep_min: 432, start: "2026-09-12T23:28:00-05:00", end: at("06:52"), goal_min: 480, deep: 64, rem: 88, awake: 20, wakeups: 3, score: 78 },
  week: [
    { deep: 76, core: 234, rem: 88, awake: 18 },
    { deep: 59, core: 220, rem: 79, awake: 16 },
    { deep: 67, core: 243, rem: 91, awake: 20 },
    { deep: 53, core: 202, rem: 70, awake: 16 },
    { deep: 47, core: 199, rem: 64, awake: 14 },
    { deep: 82, core: 258, rem: 100, awake: 18 },
    { deep: 62, core: 228, rem: 85, awake: 17 },
  ],
  consistency: { bedtime: "11:34 PM", wake: "6:48 AM", variance_min: 42, steadiest: "Tue – Thu" },
  insights: [
    { text: "Deep sleep is 18% lower on nights with more than an hour of screen time after 10 PM.", tone: "watch" },
    { text: "Nights after a strength session average 32 minutes more deep sleep.", tone: "positive" },
    { text: "Your bedtime moved 42 minutes later on average this week.", tone: "watch" },
  ],
};

export const heartTab: HeartTab = {
  resting: { value: 58, delta_90d: -4, trend: [62, 61.6, 61.2, 60.8, 61.2, 60.4, 60, 60.4, 59.6, 60, 59.2, 59.6, 58.8, 59.2, 58] },
  readings: [
    r("resting_hr", 58, "BPM", at("06:10"), "normal"),
    r("hrv", 46, "ms", "overnight", "normal"),
    r("walking_hr", 104, "BPM", at("08:33"), "normal"),
    r("vo2max", 41.2, "VO₂max", at("07:30", 11), "above average"),
    r("recovery_hr", 32, "BPM drop", at("07:30", 12), "good"),
  ],
  zones: [
    { zone: 1, name: "Recovery", range: "< 114 BPM", minutes: 72 },
    { zone: 2, name: "Easy", range: "114–133 BPM", minutes: 138 },
    { zone: 3, name: "Moderate", range: "133–152 BPM", minutes: 106 },
    { zone: 4, name: "Hard", range: "152–171 BPM", minutes: 62 },
    { zone: 5, name: "Peak", range: "> 171 BPM", minutes: 28 },
  ],
  alerts: [
    { label: "High heart rate alerts", value: "None in 30 days" },
    { label: "Low heart rate alerts", value: "None in 30 days" },
    { label: "Irregular rhythm", value: "Not detected" },
  ],
};

export const respiratoryTab: RespiratoryTab = {
  readings: [
    r("spo2", 98, "%", at("08:40"), "Normal"),
    r("respiratory_rate", 14, "br/min", "overnight", "Normal"),
    r("vo2max", 41.2, "VO₂max", at("07:30", 11), "Above average"),
    r("spo2_low", 95, "%", "overnight", "within normal"),
    r("breathing_variability", 0, "", "overnight", "stable"),
  ],
  overnight_spo2: [98, 98, 97, 98, 97, 96, 97, 98, 97, 96, 95, 96, 97, 98, 98],
  aerobic: { current: 41.2, six_months_ago: 38.4, category: "Above average" },
  alerts: [
    { label: "Low oxygen alerts", value: "None in 30 days" },
    { label: "Elevated breathing rate", value: "Not detected" },
  ],
};

export const bodyTab: BodyTab = {
  readings: [
    r("weight", 72.4, "kg", at("07:30", 12), "down 1.8 in 8 weeks"),
    r("bmi", 23.1, "", at("07:30", 12), "healthy range"),
    r("body_fat", 18.4, "%", at("07:30", 12), "goal 17%"),
    r("muscle_mass", 34.2, "kg", at("07:30", 12), "up 0.7"),
    r("waist", 81, "cm", at("07:30", 10), "down 2 cm"),
  ],
  weekly: {
    weight: [74.2, 74.0, 73.6, 73.5, 73.1, 72.9, 72.6, 72.4],
    body_fat: [19.8, 19.6, 19.3, 19.2, 18.9, 18.7, 18.5, 18.4],
  },
  muscle_change_kg: 0.7,
  measurements: [
    { name: "Waist", current: 81, previous: 83, unit: "cm", taken_at: "2026-09-10T07:30:00-05:00" },
    { name: "Chest", current: 99, previous: 98, unit: "cm", taken_at: "2026-09-10T07:30:00-05:00" },
    { name: "Hips", current: 96, previous: 97, unit: "cm", taken_at: "2026-09-10T07:30:00-05:00" },
    { name: "Left arm", current: 34, previous: 33, unit: "cm", taken_at: "2026-09-03T07:30:00-05:00" },
    { name: "Left thigh", current: 57, previous: 56, unit: "cm", taken_at: "2026-09-03T07:30:00-05:00" },
  ],
  body_fat_goal: { current: 18.4, start: 20.8, target: 17, weeks_to_go: 9 },
};

export const mentalTab: MentalTab = {
  mood: ["good", "okay", "great", "low", "okay", "great", null],
  readings: [
    r("mood", 0, "", "today", "Good"),
    r("mindful_minutes", 42, "min", "today", "goal 105 min"),
    r("hrv", 46, "ms", "overnight", "stable"),
    r("sleep_quality", 78, "/ 100", "last_night", "fair"),
    r("time_outdoors", 260, "duration", "today", "this week"),
  ],
  mindful: { sessions: 5, minutes: 42, goal: 105, longest: 12 },
  insights: [
    { text: "Your two lowest mood days followed nights under 6 hours 30 minutes of sleep.", tone: "watch" },
    { text: "Mood was rated Great on both days with an outdoor session.", tone: "positive" },
    { text: "HRV has stayed stable, which usually tracks with manageable stress load.", tone: "positive" },
  ],
};

export const digitalTab: DigitalTab = {
  average_min: 258,
  change_min: -22,
  week: [
    { day_h: 3.4, late_h: 0.6 },
    { day_h: 4.1, late_h: 0.9 },
    { day_h: 3.0, late_h: 0.4 },
    { day_h: 4.6, late_h: 1.3 },
    { day_h: 5.2, late_h: 1.6 },
    { day_h: 5.9, late_h: 1.1 },
    { day_h: 3.5, late_h: 0.8 },
  ],
  readings: [
    { ...r("pickups", 74, "today", "today", "up 9 from average"), short_assessment: "up 9" },
    { ...r("first_pickup", 374, "time", at("06:14"), "4 min after waking"), short_assessment: "4m after waking" },
    { ...r("notifications", 132, "today", "today", "38 from social apps"), short_assessment: "38 social" },
    { ...r("late_use", 66, "duration", "today", "up 41 min in 2 weeks"), short_assessment: "up 41m in 2wk" },
    r("longest_use", 52, "min", at("20:10", 12), "Saturday evening"),
  ],
  categories: [
    { name: "Social", apps: "Instagram, X, Reddit", minutes: 112 },
    { name: "Entertainment", apps: "YouTube, Netflix", minutes: 66 },
    { name: "Productivity", apps: "Mail, Calendar, Notes", minutes: 38 },
    { name: "Health & Fitness", apps: "VitalLink, Strava", minutes: 24 },
    { name: "Other", apps: "Maps, Messages", minutes: 18 },
  ],
  deep_sleep: { heavy_min: 48, light_min: 81, heavy_nights: 4, light_nights: 3 },
  limits: [
    { name: "Downtime", detail: "10:30 PM – 6:30 AM", on: true },
    { name: "Social app limit", detail: "1h 30m daily", on: true },
    { name: "Sleep focus", detail: "Auto at 10:30 PM", on: true },
    { name: "Wind-down reminder", detail: "Not set", on: false },
  ],
};
