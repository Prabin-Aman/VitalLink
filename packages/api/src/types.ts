// Response shapes of the VitalLink API (see "VitalLink Backend System Design", §5).
// Kept in step with the FastAPI schemas; regenerate from /openapi.json when they change.

export type ISODate = string;

export type RangeStatus = "normal" | "borderline" | "high" | "low" | "no-data";

export interface Me {
  id: string;
  email: string;
  display_name: string | null;
  timezone: string;
}

export interface MetricSummary {
  metric: string;
  unit: string;
  latest: number | null;
  latest_at: ISODate | null;
  avg_7d: number | null;
  range: RangeStatus;
}

export interface Dashboard {
  generated_at: ISODate;
  health_score: number | null; // readiness, 0–100
  health_score_delta_7d: number | null;
  metrics: MetricSummary[];
}

export interface SeriesPoint {
  bucket: ISODate;
  value: number;
  samples: number;
}

export interface Series {
  metric: string;
  unit: string;
  interval: "hour" | "day" | "week" | "month";
  points: SeriesPoint[];
}

export type WorkoutType = "walk" | "run" | "cycle" | "strength" | "swim" | "hiit" | "yoga" | "other";

export interface Workout {
  id: string;
  type: WorkoutType;
  title: string;
  started_at: ISODate;
  duration_s: number;
  distance_m: number | null;
  kcal: number | null;
  avg_hr: number | null;
}

/** GET /me/targets. */
export interface Targets {
  daily_steps_goal: number;
  move_kcal_goal: number;
  exercise_min_goal: number;
  stand_hours_goal: number;
  sleep_goal_min: number;
}

export interface Goal {
  id: string;
  metric: string;
  label: string;
  current: number;
  target: number;
  unit: string;
}

export interface Medication {
  id: string;
  name: string;
  dosage: string;
  status: "taken" | "due";
  time: string; // local, e.g. "8:00 AM"
}

export type Scope = "vitals" | "activity" | "sleep" | "nutrition" | "records";

export interface Share {
  id: string;
  grantee_label: string;
  /**
   * "hospital" exists only because the desktop Privacy Centre design shows a
   * "Hospitals with access" card. VitalLink is consumer-only and the API never
   * returns it. Remove once the design is corrected.
   */
  grantee_kind: "doctor" | "family" | "app" | "hospital";
  scopes: Scope[];
  expires_at: ISODate | null;
  active: boolean;
}

export interface AccessLogEntry {
  at: ISODate;
  actor: string;
  actor_detail: string;
  accessed: string;
  purpose: string;
  status: "authorised" | "limited" | "blocked";
}

// ---------------------------------------------------------------- training
// Not in the API yet (system design §5); served from mock data, null when live.

export type SessionKind = "cardio" | "strength" | "mobility";

export interface TrainingSession {
  id: string;
  type: WorkoutType;
  kind: SessionKind;
  title: string;
  started_at: ISODate;
  duration_s: number;
  exercises: number | null;
  sets: number | null;
  volume_kg: number | null;
  distance_m: number | null;
  pace_s_per_km: number | null;
  kcal: number | null;
  avg_hr: number | null;
  rpe: number | null;
  /** Dominant heart-rate zone, 1–5. */
  zone: number;
  /** Mobility sessions: body areas covered. */
  focus: string | null;
  pr: { exercise: string; weight_kg: number } | null;
}

/** Server-side weekly aggregate (Mon–Sun). */
export interface TrainingWeek {
  sessions: number;
  target_sessions: number;
  minutes: number;
  volume_kg: number;
  active_kcal: number;
  streak_days: number;
  longest_streak_days: number;
  source: string;
  /** Minutes per session kind for each day, Monday first. Empty = rest day. */
  load: Partial<Record<SessionKind, number>>[];
}

export interface MuscleGroupSets {
  group: string;
  sets: number;
}

export interface PersonalRecord {
  exercise: string;
  achieved_at: ISODate;
  weight_kg: number | null;
  reps: number | null;
  time_s: number | null;
}

export interface PlannedSession {
  title: string;
  kind: string;
  starts_at: ISODate;
  exercises: number;
  minutes: number;
}

export interface Training {
  /** All sessions ever logged. */
  total_sessions: number;
  week: TrainingWeek;
  sessions: TrainingSession[];
  muscles: { days: number; groups: MuscleGroupSets[]; note: string };
  records: PersonalRecord[];
  next: PlannedSession | null;
}

// ---------------------------------------------------------------- nutrition

export interface Meal {
  id: string;
  name: string;
  eaten_at: ISODate;
  items: string[];
  kcal: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
}

export interface NutritionDay {
  date: ISODate;
  kcal: number;
  kcal_goal: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fibre_g: number;
  goals: { protein_g: number; carbs_g: number; fat_g: number; fibre_g: number };
  water_ml: number;
  water_goal_ml: number;
  glass_ml: number;
  base_burn_kcal: number;
  active_burn_kcal: number;
  /** Planned weight change per week, kg (negative = loss). */
  weekly_target_kg: number;
  meals: Meal[];
}

// ---------------------------------------------------------------- goals

export type GoalStatus = "on_track" | "at_risk" | "achieved" | "paused";

export interface TrackedGoal {
  id: string;
  title: string;
  /** Mobile list label. */
  short_title: string;
  area: string;
  status: GoalStatus;
  progress: number;
  /** Progress summary from the goals service, e.g. "4 of 5 sessions · 2 days left". */
  detail: string;
  short_detail: string;
}

export interface GoalsOverview {
  goals: TrackedGoal[];
  achieved_this_month: number;
  longest_streak: { days: number; label: string };
  /** Last 5 weeks, Monday first; true = goal day met. */
  consistency: boolean[][];
  recent: { title: string; achieved_at: ISODate }[];
}

// ---------------------------------------------------------------- medications

export interface Prescription {
  id: string;
  name: string;
  form: string;
  strength: string;
  frequency: string;
  prescriber: string;
  refills_left: number;
  /** The dose that decides the row's state: last taken, next due, or missed. */
  dose: { at: ISODate; status: "taken" | "due" | "missed" };
  refill_due: boolean;
}

export interface MedicationsOverview {
  prescriptions: Prescription[];
  doses_today: { taken: number; total: number };
}

// ---------------------------------------------------------------- appointments

export interface Appointment {
  id: string;
  clinician: string;
  specialty: string;
  starts_at: ISODate;
  mode: "in_person" | "telehealth";
  location: string;
  status: "confirmed" | "pending" | "cancelled" | "completed";
}

// ---------------------------------------------------------------- messages

export type AvatarTone = "brand" | "teal" | "energy" | "neutral" | "purple";

export interface Conversation {
  id: string;
  with: string;
  role: string;
  /** Longer affiliation shown in the thread header. */
  role_long: string;
  tone: AvatarTone;
  unread: boolean;
  last_at: ISODate;
  preview: string;
  messages: { id: string; from_me: boolean; at: ISODate; body: string; attachment?: { name: string } }[];
}

// ---------------------------------------------------------------- notifications

export type NotificationKind = "fitness" | "health" | "medication" | "appointments" | "records" | "security" | "goals";

export interface AppNotification {
  id: string;
  kind: NotificationKind;
  at: ISODate;
  title: string;
  body: string;
  /** Mobile copy where the design shortens it. */
  short_body?: string;
  action: string;
  read: boolean;
}

// ---------------------------------------------------------------- family

export interface FamilyMember {
  id: string;
  name: string;
  relation: string;
  age: number | null;
  tone: AvatarTone;
  /** null for the signed-in user. */
  access: null | {
    level: "full" | "partial";
    granted_at: ISODate;
    scopes: { label: string; short_label: string; granted: boolean }[];
  };
  summary: null | {
    resting_hr: number;
    conditions: { label: string; severity: "chronic" | "allergy" }[];
    medications: number;
    next_appointment: ISODate | null;
  };
}

// ---------------------------------------------------------------- emergency

export interface EmergencyProfile {
  name: string;
  age: number;
  sex: string;
  city: string;
  allergies: string[];
  conditions: string[];
  medications: string[];
  organ_donor: string | null;
  preferred_hospital: string | null;
  notes: string;
  contacts: { name: string; relation: string; phone: string; primary: boolean; tone: AvatarTone }[];
}

// ---------------------------------------------------------------- devices

export interface Device {
  id: string;
  name: string;
  kind: "wearable" | "home_monitor" | "continuous" | "phone";
  icon: "devices" | "monitor" | "droplet" | "scale" | "health";
  last_sync_at: ISODate;
  status: "connected" | "overdue";
  data: string[];
  /** Mobile shows fewer data types for wearables. */
  short_data?: string[];
}

// ---------------------------------------------------------------- library

export interface Article {
  id: string;
  title: string;
  category: string;
  minutes: number;
  reviewer: string | null;
  summary?: string;
}

export interface Library {
  categories: string[];
  featured: Article;
  /** Mobile features a different article. */
  featured_mobile: Article;
  trending: Article[];
}

// ---------------------------------------------------------------- account

/** Calendar date without a time zone, "YYYY-MM-DD". */
export type CalendarDate = string;

/** GET /me/account. null = the user hasn't filled it in yet. */
export interface AccountProfile {
  first_name: string | null;
  last_name: string | null;
  email: string;
  /** E.164, e.g. "+12145550148". */
  phone: string | null;
  zip: string | null;
  city: string | null;
  /** BCP-47, e.g. "en-US". */
  language: string;
  height_cm: number | null;
  member_since: ISODate;
  units: "metric" | "imperial";
  two_factor: boolean;
  theme: "light" | "dark" | "system";
  date_of_birth: CalendarDate | null;
  sex: "female" | "male" | "other" | "undisclosed" | null;
  timezone: string;
}

// ---------------------------------------------------------------- records

export type RecordType = "lab" | "prescription" | "imaging" | "visit" | "vaccination" | "allergy" | "procedure" | "upload";

export interface HealthRecord {
  id: string;
  title: string;
  type: RecordType;
  source: string;
  /** "active prescription", "visit note", "record card"… */
  note: string | null;
  date: ISODate;
  shared_with: string | null;
  /** Lab panel id when the record is a lab result. */
  panel_id: string | null;
}

export interface RecordShare {
  grantee: string;
  records: number | null;
  scope: string | null;
  expires_at: ISODate | null;
}

export interface RecordsOverview {
  counts: Record<RecordType, number>;
  recent: HealthRecord[];
  shares: RecordShare[];
}

export interface LabResult {
  name: string;
  value: number;
  unit: string;
  low: number;
  high: number;
  previous: number | null;
  /** Decimal places the lab reports. */
  decimals: number;
  /** The lab's interpretation; "borderline" is just outside range, "abnormal" needs follow-up. */
  interpretation: "normal" | "borderline" | "abnormal";
}

export interface LabPanel {
  id: string;
  name: string;
  short_name: string;
  facility: string;
  laboratory: string;
  ordered_by: string;
  collected_at: ISODate;
  reported_at: ISODate;
  added_at: ISODate;
  file: { name: string; size_kb: number } | null;
  results: LabResult[];
  /** Results printed on the one-page report. */
  report_results: string[];
  note: string | null;
}

// ---------------------------------------------------------------- my health

/** A latest reading with the server's assessment ("Normal", "Goal 10,000", "Above average"). */
export interface Reading {
  metric: string;
  value: number;
  /** Diastolic, for blood pressure. */
  value2?: number;
  unit: string;
  at: ISODate | null;
  /** Aggregates without a single timestamp. */
  period?: "overnight" | "last_night" | "today" | "now";
  assessment: string;
  /** Mobile wording where the design shortens it ("up 9", "38 social"). */
  short_assessment?: string;
}

export interface Insight {
  text: string;
  tone: "positive" | "watch";
}

export interface HealthOverview {
  rings: { move: [number, number]; exercise: [number, number]; stand: [number, number]; source: string; synced_at: ISODate };
  sections: { title: string; sources: string; metrics: string[] }[];
  readings: Reading[];
  sources: { name: string; data: string; ok: boolean }[];
  insights: Insight[];
}

export interface VitalsTab {
  measured_at: ISODate;
  readings: Reading[];
  /** Every other day for 30 days. */
  bp30: { systolic: number[]; diastolic: number[] };
  sources: { device: string; data: string }[];
}

export interface SleepNight {
  deep: number;
  core: number;
  rem: number;
  awake: number;
}

export interface SleepTab {
  last: { asleep_min: number; start: ISODate; end: ISODate; goal_min: number; deep: number; rem: number; awake: number; wakeups: number; score: number };
  week: SleepNight[];
  consistency: { bedtime: string; wake: string; variance_min: number; steadiest: string };
  insights: Insight[];
}

export interface HeartTab {
  resting: { value: number; delta_90d: number; trend: number[] };
  readings: Reading[];
  zones: { zone: number; name: string; range: string; minutes: number }[];
  alerts: { label: string; value: string }[];
}

export interface RespiratoryTab {
  readings: Reading[];
  overnight_spo2: number[];
  aerobic: { current: number; six_months_ago: number; category: string };
  alerts: { label: string; value: string }[];
}

export interface BodyTab {
  readings: Reading[];
  weekly: { weight: number[]; body_fat: number[] };
  muscle_change_kg: number;
  measurements: { name: string; current: number; previous: number; unit: string; taken_at: ISODate }[];
  body_fat_goal: { current: number; start: number; target: number; weeks_to_go: number };
}

export interface MentalTab {
  mood: ("great" | "good" | "okay" | "low" | null)[];
  readings: Reading[];
  mindful: { sessions: number; minutes: number; goal: number; longest: number };
  insights: Insight[];
}

export interface DigitalTab {
  average_min: number;
  change_min: number;
  week: { day_h: number; late_h: number }[];
  readings: Reading[];
  categories: { name: string; apps: string; minutes: number }[];
  deep_sleep: { heavy_min: number; light_min: number; heavy_nights: number; light_nights: number };
  limits: { name: string; detail: string; on: boolean }[];
}
