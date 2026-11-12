import type {
  AccessLogEntry,
  NutritionTargets,
  GoalDetail,
  HealthRecord,
  MedicationDetail,
  ImportResult,
  Connection,
  ConnectionSync,
  Phone,
  PhoneCode,
  IngestResult,
  AccountProfile,
  AppNotification,
  BodyTab,
  DigitalTab,
  HealthOverview,
  HeartTab,
  MentalTab,
  Reading,
  RespiratoryTab,
  SleepTab,
  VitalsTab,
  Appointment,
  Conversation,
  Device,
  EmergencyProfile,
  FamilyMember,
  LabPanel,
  LatestLab,
  Library,
  MedicationsOverview,
  Dashboard,
  Goal,
  GoalsOverview,
  Me,
  Medication,
  NutritionDay,
  PrivacySettings,
  RecordsOverview,
  Series,
  Share,
  SharedWithMe,
  Targets,
  Training,
  Workout,
  FoodHit,
  WaterEntry,
  Recipe,
  GoalMetricOption,
  ConditionItem,
  ExerciseItem,
  PersonalRecord,
  TrainingPlan,
  ArticleDetail,
  Doctor,
} from "./types";
import { mock } from "./mock";

export interface ClientOptions {
  /** API origin, e.g. https://vitallink-api.onrender.com. Omit to use mock data. */
  baseUrl?: string;
  /** Returns the current Supabase access token. */
  getToken?: () => Promise<string | null>;
}

/**
 * Thrown by a screen loader when its data has no API endpoint yet (live mode).
 * Screens show "not available yet" instead of sample numbers.
 */
export class NotAvailableError extends Error {
  constructor(what: string) {
    super(`${what} isn't available yet.`);
  }
}

/** Unwraps a `pending()` result, or throws NotAvailableError in live mode. */
export const need = <T>(value: T | null, what: string): T => {
  if (value === null) throw new NotAvailableError(what);
  return value;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
  ) {
    super(message);
  }
}

/**
 * Thin typed client over the VitalLink REST API.
 * With no baseUrl it serves the sample data from the Figma designs, so every
 * screen renders without a backend (demo mode, tests, Storybook).
 */
export class VitalLinkClient {
  constructor(private readonly opts: ClientOptions = {}) {}

  get isMock(): boolean {
    return !this.opts.baseUrl;
  }

  /** Reference time for "2 h ago" / "Tomorrow": the design's moment in mock mode. */
  now(): string {
    return this.isMock ? mock.dashboard.generated_at : new Date().toISOString();
  }

  /** fetch, but a server that can't be reached is an ApiError with a message a person can act on. */
  private async reach(url: string, init?: RequestInit): Promise<Response> {
    try {
      return await fetch(url, init);
    } catch {
      throw new ApiError(0, "unreachable", `Can't reach the VitalLink server at ${this.opts.baseUrl}. Check that the API is running, then try again.`);
    }
  }

  private async get<T>(path: string, fallback: () => T): Promise<T> {
    if (this.isMock) return structuredClone(fallback());
    const token = await this.opts.getToken?.();
    const res = await this.reach(`${this.opts.baseUrl}/v1${path}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      const e = body?.error ?? {};
      throw new ApiError(res.status, e.code ?? "error", e.message ?? res.statusText, e.request_id);
    }
    return (await res.json()) as T;
  }

  /** A write. Mock mode has nothing to write to, so it changes nothing and resolves with null. */
  private async send<T>(method: "POST" | "PATCH" | "PUT" | "DELETE", path: string, body?: unknown): Promise<T | null> {
    if (this.isMock) return null;
    const token = await this.opts.getToken?.();
    // A Blob (an import file) goes as the raw body; anything else as JSON.
    const raw = body instanceof Blob;
    const res = await this.reach(`${this.opts.baseUrl}/v1${path}`, {
      method,
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body === undefined ? {} : { "Content-Type": raw ? "application/octet-stream" : "application/json" }),
      },
      body: body === undefined ? undefined : raw ? body : JSON.stringify(body),
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      const e = err?.error ?? {};
      throw new ApiError(res.status, e.code ?? "error", e.message ?? res.statusText, e.request_id);
    }
    // 202/204 (and any empty reply) have no body.
    const text = await res.text();
    return text ? (JSON.parse(text) as T) : null;
  }

  // ---- Writes.
  /** PATCH /me/account: only the fields given change; null clears an optional one. */
  /** Create Account's setup steps are finished or left for later. */
  completeOnboarding = () => this.send("POST", "/me/onboarding/complete");
  updateAccount = (patch: Partial<Omit<AccountProfile, "email" | "member_since" | "two_factor">>) =>
    this.send<AccountProfile>("PATCH", "/me/account", patch);
  /** Mark one scheduled dose taken or skipped (idempotent). `at` is the dose's scheduled time. */
  logDose = (medicationId: string, at: string, status: "taken" | "skipped") =>
    this.send("PUT", `/me/medications/${medicationId}/doses/${encodeURIComponent(at)}`, { status });
  markNotificationsRead = (ids: string[] | "all") =>
    this.send<{ updated: number }>("POST", "/me/notifications/read", ids === "all" ? { all: true } : { ids });
  setEmergencyOnLockScreen = (on: boolean) =>
    this.send<PrivacySettings>("PATCH", "/me/privacy", { emergency_on_lock_screen: on });
  /** The user cancels a visit (it stays in the Cancelled list). */
  cancelAppointment = (id: string) => this.send<Appointment>("PATCH", `/me/appointments/${id}`, { state: "cancelled" });
  // ---- Entry forms (Figma page "15 — Proposed · Entry forms"); bodies are built in forms.ts.
  /** One manual reading (POST /me/health/manual); blood pressure is two calls, systolic and diastolic. */
  /** POST /v1/auth/sign-up and /sign-in (local email/password accounts): an access token, or null in mock mode. */
  signUp = (body: object) => this.send<{ access_token: string; expires_at: string }>("POST", "/auth/sign-up", body);
  /** GET /v1/auth/config: what Sign In can offer. Mock mode has no accounts. */
  authConfig = () => this.get<{ password: boolean; google_client_id: string }>("/auth/config", () => ({ password: false, google_client_id: "" }));
  /** Always 202 (it never says whether the email has an account); null in mock mode. */
  forgotPassword = (email: string) => this.send("POST", "/auth/forgot-password", { email });
  resetPassword = (token: string, password: string) => this.send<{ access_token: string; expires_at: string }>("POST", "/auth/reset-password", { token, password });
  /** A Google ID token from Sign in with Google → a VitalLink session. */
  signInWithGoogle = (credential: string) => this.send<{ access_token: string; expires_at: string }>("POST", "/auth/google", { credential });
  /** Settings › Security (Figma 221:2114): 204; 422 wrong_password, 409 no_password (a Google-only account). */
  changePassword = (current: string, next: string) => this.send("POST", "/auth/change-password", { current_password: current, new_password: next });
  signIn = (email: string, password: string) => this.send<{ access_token: string; expires_at: string }>("POST", "/auth/sign-in", { email, password });
  addReading = (metric: string, value: number, at: string) => this.send("POST", "/me/health/manual", { metric, value, at });
  /** POST /me/health/import: an Apple Health export or a CSV, readings from the last `days`. Null in mock mode. */
  importHealthFile = (file: Blob, days: number) => this.send<ImportResult>("POST", `/me/health/import?days=${days}`, file);
  /** Device clouds (Google Health). Mock mode: not set up, so the option says so. */
  connections = () =>
    this.get<Connection[]>("/me/connections", () => [
      { service: "google_health", name: "Fitbit (Google Health)", available: false, connected: false, connected_at: null, last_sync_at: null, synced_through: null, last_error: null },
    ]);
  /** The Google consent page to open; null in mock mode. */
  startGoogleHealth = () => this.send<{ url: string }>("POST", "/me/connections/google-health/start", {});
  /** What Google sent back to /devices/google/callback; stores the grant and syncs 30 days. */
  finishGoogleHealth = (code: string, state: string) => this.send<ConnectionSync>("POST", "/me/connections/google-health/finish", { code, state });
  syncGoogleHealth = () => this.send<ConnectionSync>("POST", "/me/connections/google-health/sync", {});
  disconnectGoogleHealth = () => this.send("DELETE", "/me/connections/google-health");
  /** Android phones linked with the VitalLink Sync app (Health Connect); none in mock mode. */
  phones = () => this.get<Phone[]>("/me/phones", () => []);
  /** A one-time code for the app; null in mock mode. */
  phoneCode = () => this.send<PhoneCode>("POST", "/me/phones/code", {});
  removePhone = (id: string) => this.send("DELETE", `/me/phones/${id}`);
  /** POST /me/health/ingest: 5-minute buckets (a Bluetooth device paired in the browser). */
  ingest = (body: object) => this.send<IngestResult>("POST", "/me/health/ingest", body);
  /** Doctors from the public NPI Registry; mock mode filters a short sample. */
  searchDoctors = (q: { name: string; specialty: string; city: string }) => {
    const params = new URLSearchParams(Object.entries(q).filter(([, v]) => v));
    const words = [q.name, q.specialty].filter(Boolean).map((w) => w.toLowerCase());
    return this.get<Doctor[]>(`/me/doctors/search?${params}`, () =>
      mock.doctors.filter((d) => words.some((w) => `${d.name} ${d.specialty}`.toLowerCase().includes(w))),
    );
  };
  /** Seeded and own foods matching `q` (2+ letters); empty in mock mode. */
  searchFoods = (q: string) => (this.isMock ? Promise.resolve([] as FoodHit[]) : this.get<FoodHit[]>(`/foods/search?q=${encodeURIComponent(q)}`, () => []));
  logMeal = (body: object) => this.send("POST", "/me/meals", body);
  logWorkout = (body: object) => this.send("POST", "/me/workouts", body);
  addMedication = (body: object) => this.send("POST", "/me/medications", body);
  addAppointment = (body: object) => this.send("POST", "/me/appointments", body);
  rescheduleAppointment = (id: string, startsAt: string) => this.send("PATCH", `/me/appointments/${id}`, { starts_at: startsAt });
  /** What a goal can track (GET /me/goals/metrics). */
  goalMetrics = () => this.get<GoalMetricOption[]>("/me/goals/metrics", () => mock.goalMetrics);
  createGoal = (body: object) => this.send("POST", "/me/goals", body);
  /** Conditions and allergies with their ids (the emergency profile only lists labels). */
  conditions = () => this.get<ConditionItem[]>("/me/conditions", () => mock.conditions);
  addCondition = (label: string, kind: "chronic" | "allergy") => this.send("POST", "/me/conditions", { label, kind });
  removeCondition = (id: string) => this.send("DELETE", `/me/conditions/${id}`);
  addContact = (body: { name: string; relation: string; phone: string; primary?: boolean }) => this.send<{ id: string }>("POST", "/me/emergency-contacts", body);
  removeContact = (id: string) => this.send("DELETE", `/me/emergency-contacts/${id}`);
  /** Edit an emergency contact in place (Figma 221:2168). */
  updateContact = (id: string, patch: { name?: string; relation?: string; phone?: string }) => this.send("PATCH", `/me/emergency-contacts/${id}`, patch);
  /**
   * A record with a file: create it, PUT the file to the signed URL the API hands out, then confirm so the
   * API checks what was stored. Resolves with the record id, or null in mock mode.
   */
  uploadRecord = async (record: object, file: Blob, name = (file as File).name): Promise<string | null> => {
    const created = await this.send<{ id: string }>("POST", "/me/records", record);
    if (!created) return null;
    const target = await this.send<{ upload_url: string; method: string; headers: Record<string, string> }>(
      "POST",
      `/me/records/${created.id}/upload`,
      { file_name: name, content_type: file.type, size: file.size },
    );
    const put = await fetch(target!.upload_url, { method: target!.method, headers: target!.headers, body: file });
    if (!put.ok) throw new ApiError(put.status, "upload_failed", "The file didn't upload. Try again.");
    await this.send("POST", `/me/records/${created.id}/confirm`);
    return created.id;
  };

  /** POST /me/shares: read-only access for someone who signs in with that email. */
  createShare = (body: object) => this.send<Share>("POST", "/me/shares", body);
  /** Stops a share at once (what they already viewed stays in the access log). */
  revokeShare = (id: string) => this.send("DELETE", `/me/shares/${id}`);
  /** PATCH /me/records/{id}: only the fields given change (e.g. { note }). */
  updateRecord = (id: string, patch: object) => this.send("PATCH", `/me/records/${id}`, patch);
  /** GET /me/records/{id} (Edit details prefill). */
  record = (id: string) => this.get<HealthRecord>(`/me/records/${id}`, () => {
    const r = mock.recordsOverview.recent.find((x) => x.id === id);
    if (!r) throw new ApiError(404, "not_found", "No such record.");
    return r;
  });
  deleteRecord = (id: string) => this.send("DELETE", `/me/records/${id}`);
  /**
   * Settings › Change photo: ask for a signed upload, PUT the image, then confirm so the API checks it.
   * Resolves with the new /me (its avatar_url), or null in mock mode.
   */
  uploadAvatar = async (file: Blob): Promise<Me | null> => {
    const target = await this.send<{ key: string; upload_url: string; method: string; headers: Record<string, string> }>("POST", "/me/avatar/upload", {
      content_type: file.type,
      size: file.size,
    });
    if (!target) return null;
    const put = await this.reach(target.upload_url, { method: target.method, headers: target.headers, body: file });
    if (!put.ok) throw new ApiError(put.status, "upload_failed", "The photo didn't upload. Try again.");
    return this.send<Me>("POST", "/me/avatar/confirm", { key: target.key });
  };
  removeAvatar = () => this.send("DELETE", "/me/avatar");
  // ---- Edit and delete (Figma 214:2165 Edit Medication, 214:2210 Edit Appointment, 214:2245 Edit Goal,
  // 214:2058 Daily targets). Mock mode serves a detail built from the sample rows so the dialogs open.
  medication = (id: string) =>
    this.get<MedicationDetail>(`/me/medications/${id}`, () => {
      const p = mock.medicationsOverview.prescriptions.find((x) => x.id === id) ?? mock.medicationsOverview.prescriptions[0];
      return { id: p.id, name: p.name, form: p.form.toLowerCase(), strength: p.strength, instructions: null, prescriber: p.prescriber, refills_left: p.refills_left, refill_due_on: null, status: "active", started_on: "2026-01-01", stopped_on: null, frequency: p.frequency, schedule: [{ time: "08:00:00", days: [1, 2, 3, 4, 5, 6, 7] }] };
    });
  updateMedication = (id: string, patch: object) => this.send<MedicationDetail>("PATCH", `/me/medications/${id}`, patch);
  deleteMedication = (id: string) => this.send("DELETE", `/me/medications/${id}`);
  /** Undo a logged dose (Dose history): it becomes due or missed again. `at` is the dose's scheduled time. */
  undoDose = (medicationId: string, at: string) => this.send("DELETE", `/me/medications/${medicationId}/doses/${encodeURIComponent(at)}`);
  nutritionTargets = () =>
    this.get<NutritionTargets>("/me/nutrition/targets", () => ({ kcal: 2200, protein_g: 150, carbs_g: 250, fat_g: 70, fibre_g: 30, water_ml: 2500, glass_ml: 250, weekly_target_kg: -0.25 }));
  updateNutritionTargets = (patch: Partial<NutritionTargets>) => this.send<NutritionTargets>("PATCH", "/me/nutrition/targets", patch);
  /** POST /me/foods: a food of the user's own (Add a food), found by search afterwards. */
  createFood = (body: object) => this.send<FoodHit>("POST", "/me/foods", body);
  /** POST /me/labs: a typed-in lab report (Add lab results). */
  addLabResults = (body: object) => this.send<{ id: string }>("POST", "/me/labs", body);
  /** PATCH /me/emergency-profile: organ donor, preferred hospital, notes. */
  updateEmergencyProfile = (patch: object) => this.send<EmergencyProfile>("PATCH", "/me/emergency-profile", patch);
  updateAppointment = (id: string, patch: object) => this.send<Appointment>("PATCH", `/me/appointments/${id}`, patch);
  deleteAppointment = (id: string) => this.send("DELETE", `/me/appointments/${id}`);
  goal = (id: string) =>
    this.get<GoalDetail>(`/me/goals/${id}`, () => {
      const g = mock.goalsOverview.goals.find((x) => x.id === id) ?? mock.goalsOverview.goals[0];
      return { id: g.id, title: g.title, short_title: g.short_title, area: g.area as GoalDetail["area"], kind: "daily_total", metric: "steps", comparator: "at_least", target: 10000, baseline: null, starts_on: "2026-09-01", ends_on: null, lifecycle: g.status === "paused" ? "paused" : "active", achieved_at: null };
    });
  updateGoal = (id: string, patch: object) => this.send<GoalDetail>("PATCH", `/me/goals/${id}`, patch);
  deleteGoal = (id: string) => this.send("DELETE", `/me/goals/${id}`);
  updateTargets = (patch: Partial<Targets>) => this.send<Targets>("PATCH", "/me/targets", patch);
  deleteWorkout = (id: string) => this.send("DELETE", `/me/workouts/${id}`);
  deleteMeal = (id: string) => this.send("DELETE", `/me/meals/${id}`);
  addWater = (ml: number) => this.send("POST", "/me/hydration", { ml });
  /** Today's water entries, newest first (Hydration › Today, Figma 221:2205). */
  waterToday = () => this.get<WaterEntry[]>("/me/hydration", () => mock.waterToday);
  removeWater = (id: string) => this.send("DELETE", `/me/hydration/${id}`);
  /** Nutrition › Recipes (Figma 222:2104, 222:2330 New recipe, 222:2417 Log a recipe). */
  recipes = () => this.get<Recipe[]>("/me/recipes", () => mock.recipes);
  createRecipe = (body: object) => this.send<Recipe>("POST", "/me/recipes", body);
  updateRecipe = (id: string, patch: object) => this.send<Recipe>("PATCH", `/me/recipes/${id}`, patch);
  deleteRecipe = (id: string) => this.send("DELETE", `/me/recipes/${id}`);
  /** Logs servings as a meal named after the recipe. */
  logRecipe = (id: string, body: object) => this.send("POST", `/me/recipes/${id}/log`, body);
  /**
   * Nutrition › Scan Barcode (Figma 221:2145): a food you have with this barcode, else Open Food Facts' product
   * saved as your food. 404 food_not_found, 503 lookup_unavailable. Null in mock mode (nothing to look up).
   */
  foodByBarcode = (code: string) => (this.isMock ? Promise.resolve(null) : this.get<FoodHit>(`/foods/barcode/${encodeURIComponent(code)}`, () => null as never));
  /** DELETE /me: needs a sign-in within the last 10 minutes (403 reauth_required otherwise). */
  deleteAccount = () => this.send("DELETE", "/me");
  /** Removes a connected device and its sync state (its past readings stay). */
  disconnectDevice = (id: string) => this.send("DELETE", `/me/devices/${id}`);
  /** A signed, 5-minute URL for a record's attached file; null in mock mode (no files there). */
  recordDownloadUrl = async (id: string): Promise<string | null> =>
    this.isMock ? null : (await this.get<{ url: string }>(`/me/records/${id}/download`, () => ({ url: "" }))).url;
  /** Everything the account owns, as the JSON file GET /me/export sends; null in mock mode. */
  exportData = async (): Promise<{ blob: Blob; filename: string } | null> => {
    if (this.isMock) return null;
    const token = await this.opts.getToken?.();
    const res = await this.reach(`${this.opts.baseUrl}/v1/me/export`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
    if (!res.ok) {
      const e = (await res.json().catch(() => ({})))?.error ?? {};
      throw new ApiError(res.status, e.code ?? "error", e.message ?? res.statusText, e.request_id);
    }
    const name = /filename="([^"]+)"/.exec(res.headers.get("Content-Disposition") ?? "")?.[1] ?? "vitallink-export.json";
    return { blob: await res.blob(), filename: name };
  };

  me = () => this.get<Me>("/me", () => mock.me);
  account = () => this.get<AccountProfile>("/me/account", () => mock.account);
  dashboard = () => this.get<Dashboard>("/me/health/dashboard", () => mock.dashboard);
  /** Buckets in the user's time zone; SI units (distance in metres). */
  series = (metric: string, days = 7, interval: Series["interval"] = "day") =>
    this.get<Series>(
      `/me/health/series/${metric}?days=${days}&interval=${interval}`,
      () => mock.series[metric] ?? { metric, unit: "", interval, points: [] },
    );
  bloodPressure = (days = 7, interval: Series["interval"] = "day") =>
    this.get<{ systolic: Series; diastolic: Series }>(
      `/me/health/series/blood_pressure?days=${days}&interval=${interval}`,
      () => mock.bloodPressure,
    );
  /** Daily targets (steps, move, exercise, stand, sleep): the one source for goals on every screen. */
  targets = () => this.get<Targets>("/me/targets", () => mock.targets);
  latest = (metrics: string[]) =>
    this.get<Reading[]>(`/me/health/latest?metrics=${metrics.join(",")}`, () =>
      mock.latest.filter((r) => metrics.includes(r.metric)),
    );
  /** The latest blood-pressure pair, or null if there's no reading yet. */
  bloodPressureLatest = async () => {
    const bp = (await this.latest(["blood_pressure"]))[0];
    return bp && bp.value2 != null ? { systolic: bp.value, diastolic: bp.value2 } : null;
  };
  devices = () => this.get<Device[]>("/me/devices", () => mock.devices);
  workouts = (days = 7) => this.get<Workout[]>(`/me/workouts?days=${days}`, () => mock.workouts);
  /** Every personal record (heaviest set per exercise, fastest 5 km and 10 km). */
  personalRecords = () => this.get<PersonalRecord[]>("/me/training/records", () => mock.training.records);
  exercises = () => this.get<ExerciseItem[]>("/exercises", () => mock.exercises);
  trainingPlans = () => this.get<TrainingPlan[]>("/me/training/plans", () => mock.plans);
  training = () => this.get<Training>("/me/training", () => mock.training);
  /** One local day (default today). Mock mode scales the sample day so a week doesn't look copied. */
  nutrition = (date?: string) =>
    this.get<NutritionDay>(`/me/nutrition/day${date ? `?date=${date}` : ""}`, () => (date ? mock.nutritionOn(date) : mock.nutrition));
  shares = () => this.get<Share[]>("/me/shares", () => mock.shares);
  sharedWithMe = () => this.get<SharedWithMe[]>("/me/shared-with-me", () => []);
  accessLog = () => this.get<AccessLogEntry[]>("/me/access-log?limit=20", () => mock.accessLog);

  // ---- Care, goals, records, notifications, family, library and My Health (backend phases 5–8).
  goals = () => this.get<Goal[]>("/me/goals/today", () => mock.goals);
  goalsOverview = () => this.get<GoalsOverview>("/me/goals/overview", () => mock.goalsOverview);
  streaks = () => this.get<{ label: string; days: number }[]>("/me/streaks", () => mock.streaks);
  /** Today's scheduled doses (dashboard card). */
  medications = () => this.get<Medication[]>("/me/medications/today", () => mock.medications);
  medicationsOverview = () => this.get<MedicationsOverview>("/me/medications", () => mock.medicationsOverview);
  appointments = () => this.get<Appointment[]>("/me/appointments", () => mock.appointments);
  /** The next appointment that isn't over or cancelled, or null. */
  nextAppointment = async (): Promise<Appointment | null> =>
    (await this.get<Appointment[]>("/me/appointments?upcoming=true&limit=1", () => [mock.appointments[0]]))[0] ?? null;
  emergencyProfile = () => this.get<EmergencyProfile>("/me/emergency-profile", () => mock.emergency);
  privacySettings = () => this.get<PrivacySettings>("/me/privacy", () => mock.privacy);
  recordsOverview = () => this.get<RecordsOverview>("/me/records/overview", () => mock.recordsOverview);
  labPanels = () => this.get<LabPanel[]>("/me/labs", () => mock.labPanels);
  /** The latest panel's first result, or null when there are no labs yet. */
  latestLab = async (): Promise<LatestLab | null> => {
    try {
      return await this.get<LatestLab>("/me/labs/latest-result", () => mock.latestLab);
    } catch (e) {
      if (e instanceof ApiError && e.code === "no_labs") return null;
      throw e;
    }
  };
  notifications = () => this.get<AppNotification[]>("/me/notifications", () => mock.notifications);
  family = () => this.get<FamilyMember[]>("/me/family", () => mock.family);
  library = () => this.get<Library>("/library", () => mock.library);
  /** One article with its Markdown body; mock mode builds it from the sample article's summary. */
  article = (slug: string) =>
    this.get<ArticleDetail>(`/library/articles/${encodeURIComponent(slug)}`, () => {
      const all = [mock.library.featured, ...mock.library.trending];
      const a = all.find((x) => (x.slug ?? x.id) === slug) ?? mock.library.featured;
      return { ...a, slug: a.slug ?? a.id, body_md: a.summary ?? "", published_on: null };
    });
  healthOverview = () => this.get<HealthOverview>("/me/health/overview", () => mock.health.overview);
  vitals = () => this.get<VitalsTab>("/me/health/tabs/vitals", () => mock.health.vitals);
  sleep = () => this.get<SleepTab>("/me/health/tabs/sleep", () => mock.health.sleep);
  heart = () => this.get<HeartTab>("/me/health/tabs/heart", () => mock.health.heart);
  respiratory = () => this.get<RespiratoryTab>("/me/health/tabs/respiratory", () => mock.health.respiratory);
  body = () => this.get<BodyTab>("/me/health/tabs/body", () => mock.health.body);
  mental = () => this.get<MentalTab>("/me/health/tabs/mental", () => mock.health.mental);
  digital = () => this.get<DigitalTab>("/me/health/tabs/digital", () => mock.health.digital);

  // ---- Shown in the designs but not in the API: mock mode returns sample data, live mode null, so the
  // screen says "isn't available yet". A real user must never see sample health numbers.
  private async pending<T>(fallback: () => T): Promise<T | null> {
    return this.isMock ? structuredClone(fallback()) : null;
  }
  /** Messages with clinicians: left out until decision D1. */
  conversations = () => this.pending<Conversation[]>(() => mock.conversations);
}
