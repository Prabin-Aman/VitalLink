import type {
  AccessLogEntry,
  AccountProfile,
  AppNotification,
  BodyTab,
  DigitalTab,
  HealthOverview,
  HeartTab,
  MentalTab,
  RespiratoryTab,
  SleepTab,
  VitalsTab,
  Appointment,
  Conversation,
  Device,
  EmergencyProfile,
  FamilyMember,
  LabPanel,
  Library,
  MedicationsOverview,
  Dashboard,
  Goal,
  GoalsOverview,
  Me,
  Medication,
  NutritionDay,
  RecordsOverview,
  Series,
  Share,
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

  me = () => this.get<Me>("/me", () => mock.me);
  account = () => this.get<AccountProfile>("/me/account", () => mock.account);
  dashboard = () => this.get<Dashboard>("/me/health/dashboard", () => mock.dashboard);
  series = (metric: string, days = 7, interval: Series["interval"] = "day") =>
    this.get<Series>(
      `/me/health/series/${metric}?days=${days}&interval=${interval}&agg=sum`,
      () => mock.series[metric] ?? { metric, unit: "", interval, points: [] },
    );
  bloodPressure = () =>
    this.get<{ systolic: Series; diastolic: Series }>(
      "/me/health/series/blood_pressure?days=7&interval=day",
      () => mock.bloodPressure,
    );
  workouts = (days = 7) => this.get<Workout[]>(`/me/workouts?days=${days}`, () => mock.workouts);
  goals = () => this.get<Goal[]>("/me/goals", () => mock.goals);
  medications = () => this.get<Medication[]>("/me/medications/today", () => mock.medications);
  shares = () => this.get<Share[]>("/me/shares", () => mock.shares);
  accessLog = () => this.get<AccessLogEntry[]>("/me/access-log?limit=20", () => mock.accessLog);

  // ---- Shown in the designs but not yet in the API (system design §5). ----
  // Mock mode returns sample data; live mode returns null so the card is hidden.
  // A real user must never see sample health numbers.
  private async pending<T>(fallback: () => T): Promise<T | null> {
    return this.isMock ? structuredClone(fallback()) : null;
  }
  bloodPressureLatest = () => this.pending(() => mock.bloodPressureLatest);
  streaks = () => this.pending(() => mock.streaks);
  nextAppointment = () => this.pending(() => mock.appointment);
  latestLab = () => this.pending(() => mock.labResult);
  privacySettings = () => this.pending(() => mock.privacy);
  training = () => this.pending<Training>(() => mock.training);
  nutrition = () => this.pending<NutritionDay>(() => mock.nutrition);
  goalsOverview = () => this.pending<GoalsOverview>(() => mock.goalsOverview);
  medicationsOverview = () => this.pending<MedicationsOverview>(() => mock.medicationsOverview);
  appointments = () => this.pending<Appointment[]>(() => mock.appointments);
  conversations = () => this.pending<Conversation[]>(() => mock.conversations);
  notifications = () => this.pending<AppNotification[]>(() => mock.notifications);
  family = () => this.pending<FamilyMember[]>(() => mock.family);
  emergencyProfile = () => this.pending<EmergencyProfile>(() => mock.emergency);
  devices = () => this.pending<Device[]>(() => mock.devices);
  library = () => this.pending<Library>(() => mock.library);
  recordsOverview = () => this.pending<RecordsOverview>(() => mock.recordsOverview);
  labPanels = () => this.pending<LabPanel[]>(() => mock.labPanels);
  healthOverview = () => this.pending<HealthOverview>(() => mock.health.overview);
  vitals = () => this.pending<VitalsTab>(() => mock.health.vitals);
  sleep = () => this.pending<SleepTab>(() => mock.health.sleep);
  heart = () => this.pending<HeartTab>(() => mock.health.heart);
  respiratory = () => this.pending<RespiratoryTab>(() => mock.health.respiratory);
  body = () => this.pending<BodyTab>(() => mock.health.body);
  mental = () => this.pending<MentalTab>(() => mock.health.mental);
  digital = () => this.pending<DigitalTab>(() => mock.health.digital);
}
