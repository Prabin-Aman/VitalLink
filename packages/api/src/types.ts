// Response shapes of the VitalLink API (see "VitalLink Backend System Design", §5).
// Kept in step with the FastAPI schemas; regenerate from /openapi.json when they change.

export type ISODate = string;

export type RangeStatus = "normal" | "borderline" | "high" | "low" | "no-data";

export interface Me {
  id: string;
  email: string;
  display_name: string | null;
  timezone: string;
  /** Short-lived link to the profile photo (Settings › Change photo), or null. */
  avatar_url?: string | null;
  /** Settings › Units: distances in km (metric) or miles (imperial). */
  units?: "metric" | "imperial";
  /** False until Create Account's setup steps are finished or left for later (Google sign-ups skip the form). */
  onboarded?: boolean;
}

/** One water entry (GET /me/hydration: today's, newest first). */
export interface WaterEntry {
  id: string;
  ml: number;
  at: string;
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

/** GET /me/goals/today: today's progress on the daily targets and on active goals. */
export interface Goal {
  id: string;
  metric: string;
  label: string;
  current: number;
  target: number;
  unit: string;
}

/** GET /me/medications/today: one scheduled dose today. `id` is per dose ("<medication>@08:00"). */
export interface Medication {
  id: string;
  medication_id: string;
  name: string;
  dosage: string;
  /** "due" until two hours after its time, then "missed" unless logged. */
  status: "taken" | "skipped" | "due" | "missed";
  at: ISODate;
}

export type Scope = "vitals" | "activity" | "sleep" | "nutrition" | "records";

export interface Share {
  id: string;
  grantee_label: string;
  grantee_email: string;
  /**
   * The API returns doctor, family or other. "hospital" and "app" exist only because the
   * desktop Privacy Centre design shows them; consumers only and no third parties (decision
   * D4), so the API never returns them. Remove once the design is corrected.
   */
  grantee_kind: "doctor" | "family" | "other" | "app" | "hospital";
  scopes: Scope[];
  purpose: string;
  starts_at: ISODate;
  expires_at: ISODate | null;
  /** False once expired (revoked shares aren't listed). */
  active: boolean;
}

/** GET /me/shared-with-me: live shares other people gave me. */
export interface SharedWithMe {
  id: string;
  owner_id: string;
  owner_name: string;
  scopes: Scope[];
  expires_at: ISODate | null;
}

export interface AccessLogEntry {
  at: ISODate;
  actor: string;
  actor_detail: string;
  accessed: string;
  purpose: string;
  status: "authorised" | "limited" | "blocked";
  /** The share the look went through (null when there was none), and whether it is still live. */
  share_id: string | null;
  share_active: boolean;
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
  id: string;
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
  /** null until height, weight, birth date and sex are known. */
  base_burn_kcal: number | null;
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

/** GET /me/goals/{id}: a goal as stored (Edit Goal). */
export interface GoalDetail {
  id: string;
  title: string;
  short_title: string;
  area: GoalMetricOption["area"];
  kind: GoalMetricOption["kind"];
  metric: string;
  comparator: "at_least" | "at_most";
  target: number;
  baseline: number | null;
  starts_on: string;
  ends_on: string | null;
  lifecycle: "active" | "paused" | "achieved" | "abandoned";
  achieved_at: ISODate | null;
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

/** GET /me/medications/{id}: everything Edit Medication prefills. Times are "HH:MM:SS"; days ISO (Mon = 1). */
export interface MedicationDetail {
  id: string;
  name: string;
  form: string;
  strength: string;
  instructions: string | null;
  prescriber: string | null;
  refills_left: number;
  refill_due_on: string | null;
  status: "active" | "stopped";
  started_on: string;
  stopped_on: string | null;
  frequency: string;
  schedule: { time: string; days: number[] }[];
  /** The last 14 days of logged doses, newest first (Dose history). */
  recent_doses?: { scheduled_for: ISODate; status: "taken" | "skipped"; logged_at: ISODate }[];
}

/** GET/PATCH /me/nutrition/targets */
export interface NutritionTargets {
  kcal: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fibre_g: number;
  water_ml: number;
  glass_ml: number;
  weekly_target_kg: number;
}

/** GET /me/medications. */
export interface MedicationsOverview {
  prescriptions: Prescription[];
  doses_today: { taken: number; total: number };
}

// ---------------------------------------------------------------- appointments

/** GET /me/appointments. A personal tracker: "pending" = not confirmed by the user yet (decision D5). */
export interface Appointment {
  id: string;
  clinician: string;
  specialty: string;
  starts_at: ISODate;
  duration_min?: number;
  mode: "in_person" | "telehealth";
  location: string;
  status: "confirmed" | "pending" | "cancelled" | "completed";
  notes?: string | null;
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

/** GET /me/notifications. */
export interface AppNotification {
  id: string;
  kind: NotificationKind;
  at: ISODate;
  title: string;
  body: string;
  /** Mobile copy where the design shortens it. */
  short_body?: string | null;
  action: string;
  /** App route the action opens. */
  target?: string;
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
  /** Only what the share covers: resting HR needs vitals; the rest needs records (null otherwise). */
  summary: null | {
    resting_hr: number | null;
    conditions: { label: string; severity: "chronic" | "allergy" }[];
    medications: number | null;
    next_appointment: ISODate | null;
  };
}

// ---------------------------------------------------------------- emergency

/** GET /me/emergency-profile. Rendered (and its QR code made) on the phone, no public link (decision D2). */
export interface EmergencyProfile {
  name: string;
  /** null until the birth date / sex are set in Settings. */
  age: number | null;
  sex: string | null;
  city: string;
  allergies: string[];
  conditions: string[];
  medications: string[];
  organ_donor: string | null;
  preferred_hospital: string | null;
  notes: string;
  show_on_lock_screen?: boolean;
  /** `phone` is E.164; `tone` is mock-only (screens pick it from the relation). */
  contacts: { id?: string; name: string; relation: string; phone: string; primary: boolean; tone?: AvatarTone }[];
}

/** GET /me/privacy: the privacy centre's switches and counts. */
export interface PrivacySettings {
  emergency_on_lock_screen: boolean;
  devices: { total: number; syncing: number; overdue: number };
}

// ---------------------------------------------------------------- devices

/** GET /me/sync: what was uploaded through ingest (batches are kept 30 days). */
export interface SyncPlatform {
  platform: "healthkit" | "health_connect" | "bluetooth";
  last_upload_at: ISODate;
  uploads_week: number;
  readings_week: number;
  changed_week: number;
  devices: number;
}
export interface SyncUpload {
  id: number;
  at: ISODate;
  platform: SyncPlatform["platform"];
  submitted: number;
  inserted: number;
  updated: number;
  unchanged: number;
}
export interface SyncStatus {
  platforms: SyncPlatform[];
  recent: SyncUpload[];
}

/** POST /me/health/import: what a file added (see backend HealthImport). */
export interface ImportResult {
  format: "apple" | "csv";
  records: number;
  readings: number;
  inserted: number;
  updated: number;
  unchanged: number;
  skipped: number;
  problems: string[];
  from: ISODate | null;
  to: ISODate | null;
}

/** POST /me/health/ingest response. */
export interface IngestResult {
  batch_id: number;
  submitted: number;
  inserted: number;
  updated: number;
  unchanged: number;
  replayed: boolean;
}

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
  slug?: string;
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
  avatar_url?: string | null;
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
  /** The stored document, if any: pending until the upload is confirmed. */
  file?: { name: string; size_kb: number | null; content_type: string | null; status: "pending" | "attached" } | null;
}

export interface RecordShare {
  /** The share's id (Revoke). Absent in the older sample data. */
  id?: string;
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

/** GET /me/labs/latest-result: the dashboard's lab card. */
export interface LatestLab {
  panel_id: string;
  panel: string;
  collected_at: ISODate;
  result: LabResult;
}

export interface LabPanel {
  id: string;
  record_id?: string | null;
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
  value2?: number | null;
  unit: string;
  at: ISODate | null;
  /** Aggregates without a single timestamp. */
  period?: "overnight" | "last_night" | "today" | "now" | null;
  assessment: string;
  /** Mobile wording where the design shortens it ("up 9", "38 social"). */
  short_assessment?: string | null;
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

/** Live tabs leave out readings nothing measures, and send null for blocks without data. */
export interface SleepTab {
  last: null | { asleep_min: number; start: ISODate; end: ISODate; goal_min: number; deep: number; rem: number; awake: number; wakeups: number; score: number };
  week: SleepNight[];
  consistency: { bedtime: string; wake: string; variance_min: number; steadiest: string } | null;
  insights: Insight[];
}

export interface HeartTab {
  resting: { value: number; delta_90d: number; trend: number[] } | null;
  readings: Reading[];
  zones: { zone: number; name: string; range: string; minutes: number }[];
  alerts: { label: string; value: string }[];
}

export interface RespiratoryTab {
  readings: Reading[];
  overnight_spo2: number[];
  aerobic: { current: number; six_months_ago: number; category: string } | null;
  alerts: { label: string; value: string }[];
}

export interface BodyTab {
  readings: Reading[];
  weekly: { weight: number[]; body_fat: number[] };
  muscle_change_kg: number;
  measurements: { name: string; current: number; previous: number; unit: string; taken_at: ISODate }[];
  body_fat_goal: { current: number; start: number; target: number; weeks_to_go: number | null } | null;
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
  deep_sleep: { heavy_min: number; light_min: number; heavy_nights: number; light_nights: number } | null;
  limits: { name: string; detail: string; on: boolean }[];
}

// ---------------------------------------------------------------- entry forms

/** GET /foods/search: macros per 100 g. */
export interface FoodHit {
  id: string;
  name: string;
  brand: string | null;
  serving_g: number;
  kcal: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fibre_g: number;
}

/** Nutrition › Recipes (GET /me/recipes): per-serving macros are worked out from the foods. */
/** A listing from the public NPI Registry. */
export interface Doctor {
  npi: string;
  name: string;
  credential: string | null;
  specialty: string | null;
  address: string | null;
  phone: string | null;
}

export interface Recipe {
  id: string;
  name: string;
  servings: number;
  notes: string | null;
  items: { food: FoodHit; quantity_g: number; kcal: number }[];
  per_serving: { grams: number; kcal: number; protein_g: number; carbs_g: number; fat_g: number; fibre_g: number };
  updated_at: string;
}

/** GET /me/goals/metrics */
export interface GoalMetricOption {
  code: string;
  label: string;
  unit: string;
  source: string;
  kind: "daily_total" | "weekly_count" | "target_value";
  area: "activity" | "training" | "nutrition" | "recovery" | "body" | "heart" | "mind";
}

/** GET /me/conditions */
export interface ConditionItem {
  id: string;
  label: string;
  kind: "chronic" | "allergy";
  note: string | null;
  noted_on: string | null;
}

// ---------------------------------------------------------------- workouts and nutrition tabs

/** GET /exercises */
export interface ExerciseItem {
  id: number;
  name: string;
  primary_muscle: "chest" | "back" | "shoulders" | "arms" | "core" | "legs" | "glutes" | "full_body";
}

/** GET /me/training/plans */
export interface TrainingPlan {
  id: string;
  name: string;
  status: string;
  weekly_target_sessions: number;
  sessions: PlannedSession[];
}

/** GET /library/articles/{slug}: an article with its Markdown body. */
export interface ArticleDetail extends Article {
  slug: string;
  body_md: string;
  published_on: string | null;
}
