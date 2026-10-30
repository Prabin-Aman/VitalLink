import type {
  AccessLogEntry,
  ImportResult,
  IngestResult,
  SyncStatus,
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
  GoalMetricOption,
  ConditionItem,
  ExerciseItem,
  PersonalRecord,
  TrainingPlan,
  ArticleDetail,
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

  private async get<T>(path: string, fallback: () => T): Promise<T> {
    if (this.isMock) return structuredClone(fallback());
    const token = await this.opts.getToken?.();
    const res = await fetch(`${this.opts.baseUrl}/v1${path}`, {
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
    const res = await fetch(`${this.opts.baseUrl}/v1${path}`, {
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
    return res.status === 204 ? null : ((await res.json()) as T);
  }

  // ---- Writes.
  /** PATCH /me/account: only the fields given change; null clears an optional one. */
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
  addReading = (metric: string, value: number, at: string) => this.send("POST", "/me/health/manual", { metric, value, at });
  /** POST /me/health/import: an Apple Health export or a CSV, readings from the last `days`. Null in mock mode. */
  importHealthFile = (file: Blob, days: number) => this.send<ImportResult>("POST", `/me/health/import?days=${days}`, file);
  /** POST /me/health/ingest: 5-minute buckets (phone sync, or a Bluetooth device paired in the browser). */
  ingest = (body: object) => this.send<IngestResult>("POST", "/me/health/ingest", body);
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
  addContact = (body: { name: string; relation: string; phone: string; primary?: boolean }) => this.send("POST", "/me/emergency-contacts", body);
  removeContact = (id: string) => this.send("DELETE", `/me/emergency-contacts/${id}`);
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
  deleteMeal = (id: string) => this.send("DELETE", `/me/meals/${id}`);
  addWater = (ml: number) => this.send("POST", "/me/hydration", { ml });
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
    const res = await fetch(`${this.opts.baseUrl}/v1/me/export`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
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
  /** GET /me/sync: the phone app's uploads (phone sync status on Connected Devices). */
  sync = () => this.get<SyncStatus>("/me/sync", () => mock.sync);
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
