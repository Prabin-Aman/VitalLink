// Care view-models: Medications, Appointments, Messages, Notifications, Family,
// Emergency ID, Devices, Settings.
import type { ColorToken } from "@vitallink/tokens";
import { need, type VitalLinkClient } from "../client";
import { ago, dayMonth, dayMonthYear, daysFrom, height, languageName, monthYear, phone, relativeDay, timeOfDay, weekday } from "../format";
import type { AppNotification, AvatarTone, Device, NotificationKind, Prescription, Doctor } from "../types";
import type { Option } from "../forms";
import type { ChipVM } from "./shared";

export const initials = (name: string) =>
  name
    .replace(/^(Dr|Coach)\.?\s+/, "")
    .split(/\s+/)
    .map((s) => s[0])
    .join("")
    .slice(0, 2)
    .toUpperCase();

/** Avatar fill per tone (Avatar/Tone=* components). */
export const TONE_BG: Record<AvatarTone, ColorToken> = {
  brand: "bgBrand",
  teal: "bgInfo",
  energy: "bgEnergy",
  neutral: "bgMuted",
  purple: "dataSleep",
};

export interface PersonVM {
  name: string;
  initials: string;
  tone: AvatarTone;
}

const person = (name: string, tone: AvatarTone): PersonVM => ({ name, initials: initials(name), tone });

export interface TintStatVM {
  key: string;
  label: string;
  value: string;
  bg: ColorToken;
  fg: ColorToken;
}

// ---------------------------------------------------------------- medications

export interface MedRowVM {
  id: string;
  /** The dose the row is about (for "Mark taken"), and whether that action applies. */
  doseAt: string;
  canMarkTaken: boolean;
  name: string;
  detail: string;
  dose: string;
  frequency: string;
  next: string;
  nextColor: ColorToken;
  prescriber: string;
  refills: string;
  refillsColor: ColorToken;
  status: ChipVM;
  /** Row tint: missed doses and due refills are highlighted. */
  rowBg: ColorToken | null;
  action: string;
  /** What the row's button does: mark the dose taken, open Dose history, or record a refill. */
  actionKind: "take" | "history" | "refill";
  /** Mobile: "500 mg · twice daily · Taken 8:00 AM" */
  summary: string;
}

export interface MedicationsVM {
  subtitle: string;
  tints: TintStatVM[];
  rows: MedRowVM[];
}

const medState = (p: Prescription) =>
  p.dose.status === "missed" ? "missed" : p.refill_due ? "refill" : p.dose.status === "taken" ? "taken" : "upcoming";

const MED_CHIP: Record<ReturnType<typeof medState>, ChipVM> = {
  taken: { label: "Taken", fg: "rangeNormal", bg: "rangeNormalBg" },
  upcoming: { label: "Upcoming", fg: "textBrand", bg: "bgBrandSubtle" },
  missed: { label: "Missed", fg: "rangeHigh", bg: "rangeHighBg" },
  refill: { label: "Refill due", fg: "rangeBorderline", bg: "rangeBorderlineBg" },
};

export async function loadMedications(api: VitalLinkClient): Promise<MedicationsVM> {
  const [me, data] = await Promise.all([api.me(), api.medicationsOverview()]);
  const m = need(data, "Medications");
  const tz = me.timezone;
  const now = api.now();
  const states = m.prescriptions.map(medState);
  const count = (s: string) => states.filter((x) => x === s).length;
  const upcomingToday = m.prescriptions.filter((p) => p.dose.status === "due" && daysFrom(p.dose.at, now, tz) === 0).length;
  const refills = count("refill");
  const missed = count("missed");
  const active = m.prescriptions.length;
  // Counted per medication, like the table's rows (a twice-daily medication taken twice is one "taken").
  const taken = m.prescriptions.filter((p) => p.dose.status === "taken").length;

  return {
    subtitle: `${active} active prescriptions · ${refills} refill${refills === 1 ? "" : "s"} due this week`,
    tints: [
      { key: "active", label: "Active medications", value: String(active), bg: "bgSurface", fg: "textPrimary" },
      { key: "taken", label: "Taken today", value: String(taken), bg: "rangeNormalBg", fg: "rangeNormal" },
      { key: "upcoming", label: "Upcoming", value: String(upcomingToday), bg: "bgBrandSubtle", fg: "textBrand" },
      { key: "missed", label: "Missed", value: String(missed), bg: "rangeHighBg", fg: "rangeHigh" },
      { key: "refills", label: "Refills due", value: String(refills), bg: "rangeBorderlineBg", fg: "rangeBorderline" },
    ],
    rows: m.prescriptions.map((p) => {
      const state = medState(p);
      const time = timeOfDay(p.dose.at, tz);
      const day = relativeDay(p.dose.at, now, tz);
      const next = p.dose.status === "taken" ? `Taken ${time}` : p.dose.status === "missed" ? `Missed ${time}` : `${day} ${time}`;
      const due = day === "Today" ? `Due ${time}` : `Due ${day.toLowerCase()} ${time}`;
      return {
        doseAt: p.dose.at,
        canMarkTaken: state === "missed" || state === "upcoming",
        id: p.id,
        name: p.name,
        detail: `${p.form} · ${p.strength}`,
        dose: p.strength,
        frequency: p.frequency,
        next,
        nextColor: p.dose.status === "missed" ? "rangeHigh" : "textSecondary",
        prescriber: p.prescriber,
        refills: String(p.refills_left),
        refillsColor: p.refills_left === 0 ? "rangeBorderline" : "textSecondary",
        status: MED_CHIP[state],
        rowBg: state === "missed" ? "rangeHighBg" : state === "refill" ? "rangeBorderlineBg" : null,
        action: state === "taken" ? "History" : state === "refill" ? "Request Refill" : "Mark Taken",
        actionKind: state === "taken" ? "history" : state === "refill" ? "refill" : "take",
        summary: [p.strength, p.frequency.toLowerCase(), p.dose.status === "due" ? due : next].join(" · "),
      };
    }),
  };
}

// ---------------------------------------------------------------- appointments

export interface AppointmentVM {
  id: string;
  weekday: string;
  day: string;
  month: string;
  title: string;
  detail: string;
  name: string;
  status: ChipVM;
  primary: string;
  /** Google Maps search for an in-person visit ("Directions"); null for telehealth. */
  mapsUrl: string | null;
  /** Upcoming and not cancelled, so the user can cancel or reschedule it. */
  canCancel: boolean;
  /** ISO start, to prefill Reschedule. */
  startsAt: string;
  group: "Upcoming" | "Past" | "Cancelled";
}

export interface CalendarVM {
  title: string;
  weeks: (number | null)[][];
  today: number | null;
  marked: number[];
}

export interface AppointmentsVM {
  subtitle: string;
  items: AppointmentVM[];
  calendar: CalendarVM;
}

const APPT_CHIP = {
  confirmed: [{ label: "Confirmed", fg: "rangeNormal", bg: "rangeNormalBg" }, "Confirmed"],
  pending: [{ label: "Awaiting confirmation", fg: "rangeBorderline", bg: "rangeBorderlineBg" }, "Awaiting"],
  cancelled: [{ label: "Cancelled", fg: "textSecondary", bg: "bgSubtle" }, "Cancelled"],
  completed: [{ label: "Completed", fg: "textSecondary", bg: "bgSubtle" }, "Completed"],
} as const satisfies Record<string, [ChipVM, string]>;

/** Month grid, Sunday first (calendar card 70:53). */
function monthGrid(iso: string, tz: string): (number | null)[][] {
  const [m, , y] = new Intl.DateTimeFormat("en-US", { timeZone: tz, year: "numeric", month: "numeric", day: "numeric" })
    .format(new Date(iso))
    .split("/")
    .map(Number);
  const first = new Date(Date.UTC(y, m - 1, 1)).getUTCDay();
  const days = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const cells: (number | null)[] = [...Array(first).fill(null), ...Array.from({ length: days }, (_, i) => i + 1)];
  while (cells.length % 7) cells.push(null);
  return Array.from({ length: cells.length / 7 }, (_, w) => cells.slice(w * 7, w * 7 + 7));
}

const dayOfMonth = (iso: string, tz: string) => Number(new Intl.DateTimeFormat("en-US", { timeZone: tz, day: "numeric" }).format(new Date(iso)));

export async function loadAppointments(api: VitalLinkClient): Promise<AppointmentsVM> {
  const [me, data] = await Promise.all([api.me(), api.appointments()]);
  const list = need(data, "Appointments");
  const tz = me.timezone;
  const now = api.now();
  const items = [...list]
    .sort((a, b) => a.starts_at.localeCompare(b.starts_at))
    .map((a): AppointmentVM => {
      const [chip] = APPT_CHIP[a.status];
      const time = timeOfDay(a.starts_at, tz);
      const mode = a.mode === "telehealth" ? "Telehealth" : "In person";
      const group = a.status === "cancelled" ? "Cancelled" : a.status === "completed" || a.starts_at < now ? "Past" : "Upcoming";
      return {
        id: a.id,
        weekday: weekday(a.starts_at, tz),
        day: String(dayOfMonth(a.starts_at, tz)),
        month: dayMonth(a.starts_at, tz).slice(3),
        title: `${a.clinician} · ${a.specialty}`,
        detail: `${time} · ${mode} · ${a.location}`,
        name: a.clinician,
        status: chip,
        primary: a.mode === "telehealth" ? "Join Telehealth" : "Directions",
        mapsUrl: a.mode === "telehealth" ? null : `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(a.location)}`,
        canCancel: group === "Upcoming" && a.status !== "cancelled",
        startsAt: a.starts_at,
        group,
      };
    });
  const upcoming = list.filter((a) => a.status !== "cancelled" && a.status !== "completed" && a.starts_at >= now).sort((a, b) => a.starts_at.localeCompare(b.starts_at));
  const next = upcoming[0];
  const rel = (iso: string) => {
    const d = relativeDay(iso, now, tz);
    return d === "Today" || d === "Tomorrow" ? d.toLowerCase() : `${d} ${dayMonth(iso, tz)}`;
  };
  return {
    subtitle: `${upcoming.length} upcoming${next ? ` · next visit ${rel(next.starts_at)} at ${timeOfDay(next.starts_at, tz)}` : ""}`,
    items,
    calendar: {
      title: monthYear(now, tz),
      weeks: monthGrid(now, tz),
      today: dayOfMonth(now, tz),
      marked: upcoming.filter((a) => monthYear(a.starts_at, tz) === monthYear(now, tz)).map((a) => dayOfMonth(a.starts_at, tz)),
    },
  };
}

// ---------------------------------------------------------------- messages

export interface ConversationVM extends PersonVM {
  id: string;
  role: string;
  roleLong: string;
  when: string;
  preview: string;
  unread: boolean;
  messages: { id: string; mine: boolean; body: string; time: string; attachment: string | null }[];
}

export interface MessagesVM {
  conversations: ConversationVM[];
}

export async function loadMessages(api: VitalLinkClient): Promise<MessagesVM> {
  const [me, data] = await Promise.all([api.me(), api.conversations()]);
  const list = need(data, "Messages");
  const now = api.now();
  const conversations = [...list]
    .sort((a, b) => b.last_at.localeCompare(a.last_at))
    .map((c) => ({
      ...person(c.with, c.tone),
      id: c.id,
      role: c.role,
      roleLong: c.role_long,
      when: ago(c.last_at, now, "short"),
      preview: c.preview,
      unread: c.unread,
      messages: c.messages.map((m) => ({ id: m.id, mine: m.from_me, body: m.body, time: timeOfDay(m.at, me.timezone), attachment: m.attachment?.name ?? null })),
    }));
  return {
    conversations,
  };
}

// ---------------------------------------------------------------- notifications

export interface NotificationVM {
  id: string;
  kind: NotificationKind;
  tag: ChipVM;
  when: string;
  title: string;
  body: string;
  action: string;
  unread: boolean;
}

export interface NotificationsVM {
  subtitle: string;
  items: NotificationVM[];
}

const TAG: Record<NotificationKind, ChipVM> = {
  fitness: { label: "FITNESS", fg: "textEnergy", bg: "bgEnergySubtle" },
  health: { label: "HEALTH", fg: "rangeHigh", bg: "rangeHighBg" },
  medication: { label: "MEDICATION", fg: "rangeBorderline", bg: "rangeBorderlineBg" },
  appointments: { label: "APPOINTMENTS", fg: "textBrand", bg: "bgBrandSubtle" },
  goals: { label: "GOALS", fg: "rangeNormal", bg: "rangeNormalBg" },
  records: { label: "RECORDS", fg: "textSecondary", bg: "bgSubtle" },
  security: { label: "SECURITY", fg: "textSecondary", bg: "bgSubtle" },
};

/** Which notification kinds each filter chip shows (web tabs and mobile chips). */
export const NOTIFICATION_FILTERS: Record<string, NotificationKind[] | null> = {
  All: null,
  Health: ["health"],
  Train: ["fitness"],
  Training: ["fitness"],
  Medication: ["medication"],
  Appointments: ["appointments"],
  Records: ["records"],
  Security: ["security"],
  Goals: ["goals"],
};

export async function loadNotifications(api: VitalLinkClient): Promise<NotificationsVM> {
  const list = need(await api.notifications(), "Notifications");
  const now = api.now();
  const unread = list.filter((n) => !n.read).length;
  return {
    subtitle: `${unread} unread · alerts about your training, health and record access.`,
    items: [...list]
      .sort((a, b) => b.at.localeCompare(a.at))
      .map((n: AppNotification) => ({
        id: n.id,
        kind: n.kind,
        tag: TAG[n.kind],
        when: ago(n.at, now),
        title: n.title,
        body: n.body,
        action: n.action,
        unread: !n.read,
      })),
  };
}

// ---------------------------------------------------------------- family

export interface FamilyVM {
  members: (PersonVM & { id: string; caption: string; selected: boolean })[];
  viewing: null | {
    person: PersonVM;
    title: string;
    detail: string;
    tints: TintStatVM[];
    conditions: { label: string; color: ColorToken }[];
    access: { label: string; shortLabel: string; granted: boolean }[];
    note: string;
  };
}

export async function loadFamily(api: VitalLinkClient): Promise<FamilyVM> {
  const [me, data] = await Promise.all([api.me(), api.family()]);
  const list = need(data, "Family");
  const tz = me.timezone;
  const shared = list.find((m) => m.access && m.summary) ?? null;
  const v = shared && shared.access && shared.summary ? { m: shared, a: shared.access, s: shared.summary } : null;
  const first = (n: string) => n.split(" ")[0];
  const date = (iso: string) => dayMonthYear(iso, tz).replace(/^0/, "");
  return {
    members: list.map((m) => ({
      ...person(m.name, m.tone),
      id: m.id,
      caption: m.relation === "Daughter" || m.relation === "Son" ? `${m.relation} · ${m.age}` : m.relation,
      selected: m.id === shared?.id,
    })),
    viewing: v && {
      person: person(v.m.name, v.m.tone),
      title: `Viewing: ${v.m.name}`,
      detail: `${v.m.relation} · ${v.m.age} · you have ${v.a.level} access granted ${date(v.a.granted_at)}`,
      tints: [
        { key: "hr", label: "Resting heart rate", value: v.s.resting_hr === null ? "Not shared" : `${v.s.resting_hr} BPM`, bg: "bgEnergySubtle", fg: "textEnergy" },
        { key: "conditions", label: "Active conditions", value: v.s.medications === null ? "Not shared" : String(v.s.conditions.filter((c) => c.severity === "chronic").length), bg: "bgSurface", fg: "textPrimary" },
        { key: "meds", label: "Medications", value: v.s.medications === null ? "Not shared" : String(v.s.medications), bg: "bgSurface", fg: "textPrimary" },
        { key: "appt", label: "Next appointment", value: v.s.next_appointment ? dayMonth(v.s.next_appointment, tz).replace(/^0/, "") : "None", bg: "bgBrandSubtle", fg: "textBrand" },
      ],
      conditions: v.s.conditions.map((c) => ({ label: c.label, color: c.severity === "allergy" ? "rangeHigh" : "rangeBorderline" })),
      access: v.a.scopes.map((x) => ({ label: x.label, shortLabel: x.short_label, granted: x.granted })),
      note: `${first(v.m.name)} controls what you can see and can revoke access at any time from their own privacy centre.`,
    },
  };
}

// ---------------------------------------------------------------- emergency

export interface EmergencyVM {
  name: string;
  meta: string;
  fields: { key: string; label: string; value: string; color: ColorToken }[];
  contacts: (PersonVM & { title: string; caption: string; phone: string })[];
}

export async function loadEmergency(api: VitalLinkClient): Promise<EmergencyVM> {
  const e = need(await api.emergencyProfile(), "Emergency Medical ID");
  const field = (key: string, label: string, value: string, color: ColorToken, mobile = true, shortValue = value) => ({ key, label, value, color, mobile, shortValue });
  return {
    name: e.name,
    meta: [e.age, e.sex, e.city].filter((x) => x !== null && x !== "").join(" · "),
    fields: [
      field("allergies", "Allergies", e.allergies.join(" · "), "rangeHigh"),
      field("conditions", "Conditions", e.conditions.join(" · "), "textInverse"),
      field("medications", "Medications", e.medications.join(" · "), "textInverse"),
      ...(e.organ_donor ? [field("donor", "Organ donor", e.organ_donor, "rangeNormal", true, e.organ_donor.split(", ")[0])] : []),
      ...(e.preferred_hospital ? [field("hospital", "Preferred hospital", e.preferred_hospital, "textInverse", false)] : []),
      field("notes", "Notes", e.notes, "textSecondary"),
    ],
    contacts: e.contacts.map((c) => {
      // The API sends E.164; clinicians are teal, everyone else purple (as in the design).
      const shown = c.phone.startsWith("+") ? phone(c.phone) : c.phone;
      const tone = c.tone ?? (/^dr\.|doctor|physician|ologist|nurse/i.test(`${c.name} ${c.relation}`) ? "teal" : "purple");
      return {
        ...person(c.name, tone),
        title: c.primary ? `${c.name}  ·  Primary` : c.name,
        caption: `${c.relation} · ${shown}`,
        phone: shown,
      };
    }),
  };
}

// ---------------------------------------------------------------- devices

export interface DeviceVM {
  id: string;
  name: string;
  icon: Device["icon"];
  caption: string;
  data: string;
  /** Each kind of data it sends (Permissions, Figma 211:1647). */
  dataTypes: string[];
  status: ChipVM;
}

export interface DevicesVM {
  subtitle: string;
  devices: DeviceVM[];
}

const KIND_LABEL: Record<Device["kind"], string> = { wearable: "Wearable", home_monitor: "Home monitor", continuous: "Continuous", phone: "Phone" };

export async function loadDevices(api: VitalLinkClient): Promise<DevicesVM> {
  const [me, data] = await Promise.all([api.me(), api.devices()]);
  const list = need(data, "Connected Devices");
  const tz = me.timezone;
  const now = api.now();
  const connected = list.filter((d) => d.status === "connected").length;
  const latest = list.reduce((a, d) => (d.last_sync_at > a ? d.last_sync_at : a), "");
  const synced = (d: Device) => {
    // Home monitors report a reading time; streaming devices report recency.
    if (d.kind !== "home_monitor") return ago(d.last_sync_at, now);
    const days = -daysFrom(d.last_sync_at, now, tz);
    return days === 0 ? timeOfDay(d.last_sync_at, tz) : days === 1 ? "yesterday" : `${days} days ago`;
  };
  const mins = Math.round((Date.parse(now) - Date.parse(latest)) / 60000);
  return {
    subtitle: `${connected} devices connected · last sync ${mins} minute${mins === 1 ? "" : "s"} ago`,
    devices: list.map((d) => ({
      id: d.id,
      name: d.name,
      dataTypes: d.data,
      icon: d.icon,
      caption: `${KIND_LABEL[d.kind]} · ${synced(d)}`,
      data: d.data.join(" · "),
      status: d.status === "connected" ? { label: "Connected", fg: "rangeNormal", bg: "rangeNormalBg" } : { label: "Sync overdue", fg: "rangeBorderline", bg: "rangeBorderlineBg" },
    })),
  };
}

// ---------------------------------------------------------------- settings

const UNITS: Option[] = [
  { value: "imperial", label: "Imperial (miles)" },
  { value: "metric", label: "Metric (km)" },
];

/** A doctor as Search Doctors lists it, with what Book Appointment starts from. */
export interface DoctorRowVM {
  id: string;
  title: string;
  line: string;
  preset: { clinician: string; specialty: string; location: string };
}

export function doctorRow(d: Doctor): DoctorRowVM {
  const title = d.credential ? `${d.name}, ${d.credential}` : d.name;
  return {
    id: d.npi,
    title,
    line: [d.specialty, d.address, d.phone].filter(Boolean).join(" · "),
    // The appointment form allows 80 characters; the registry can run longer.
    preset: { clinician: title.slice(0, 80), specialty: (d.specialty ?? "").slice(0, 80), location: d.address ?? "" },
  };
}

export interface SettingsVM {
  person: PersonVM;
  memberLine: string;
  /** The month and year alone, for Settings › Account. */
  memberDate: string;
  email: string;
  /** `select` fields carry their options (value = option value); `readonly` can't be edited here. */
  fields: { key: string; label: string; value: string; help?: string; wide?: boolean; kind?: "readonly" | "select"; options?: Option[] }[];
  /** Short-lived link to the profile photo, or null (initials show instead). */
  photo: string | null;
  units: "metric" | "imperial";
  theme: "light" | "dark" | "system";
  summary: { profile: string; security: string; appearance: string; language: string; units: string };
}

export async function loadSettings(api: VitalLinkClient): Promise<SettingsVM> {
  const [me, a] = await Promise.all([api.me(), api.account()]);
  const name = [a.first_name, a.last_name].filter(Boolean).join(" ") || a.email;
  const since = monthYear(a.member_since, me.timezone);
  const blank = "N/A";
  return {
    person: person(name, "brand"),
    memberLine: `Member since ${since}${a.city ? ` · ${a.city}` : ""}`,
    memberDate: since,
    email: a.email,
    fields: [
      { key: "first", label: "First name", value: a.first_name ?? blank },
      { key: "last", label: "Last name", value: a.last_name ?? blank },
      { key: "email", label: "Email address", value: a.email, help: "Used for sign-in. It changes with your sign-in account.", kind: "readonly" },
      { key: "phone", label: "Mobile number", value: a.phone ? phone(a.phone) : blank, help: "Used for reminders and goal alerts." },
      { key: "zip", label: "ZIP code", value: a.zip ?? blank, help: "Used to find clinics and facilities near you." },
      { key: "height", label: "Height", value: a.height_cm === null ? blank : height(a.height_cm, a.units), help: a.units === "metric" ? "In cm, e.g. 178 cm. Used to calculate BMI and energy needs." : "e.g. 5 ft 10 in or 178 cm. Used to calculate BMI and energy needs." },
      { key: "units", label: "Units", value: a.units, kind: "select", options: UNITS, help: "Distance, pace, body weight and goals. Lifting weights stay in kg." },
    ],
    photo: a.avatar_url ?? null,
    units: a.units,
    theme: a.theme,
    summary: {
      profile: name,
      security: a.two_factor ? "2FA on" : "2FA off",
      appearance: a.theme[0].toUpperCase() + a.theme.slice(1),
      language: languageName(a.language),
      units: a.units === "metric" ? "Metric" : "Imperial",
    },
  };
}
