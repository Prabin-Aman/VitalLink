import type {
  AccessLogEntry,
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
    const res = await fetch(`${this.opts.baseUrl}/v1${path}`, {
      method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
      body: body === undefined ? undefined : JSON.stringify(body),
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
  bloodPressure = () =>
    this.get<{ systolic: Series; diastolic: Series }>(
      "/me/health/series/blood_pressure?days=7&interval=day",
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
  training = () => this.get<Training>("/me/training", () => mock.training);
  nutrition = () => this.get<NutritionDay>("/me/nutrition/day", () => mock.nutrition);
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
