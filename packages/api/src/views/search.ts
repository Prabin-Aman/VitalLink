// Top-bar search (Figma 207:1493): pages, records and labs, medications, appointments and library
// articles, matched on every word typed and grouped like the design. The index loads once, on first focus.
import type { VitalLinkClient } from "../client";
import { dayMonthYear, timeOfDay } from "../format";

export type SearchIcon = "records" | "medications" | "appointments" | "library" | "page";
export interface SearchHit {
  group: string;
  title: string;
  caption: string;
  to: string;
  icon: SearchIcon;
}
interface Entry extends SearchHit {
  hay: string;
}
export interface SearchIndex {
  entries: Entry[];
}

const GROUP_ORDER = ["RECORDS & LABS", "MEDICATIONS", "APPOINTMENTS", "HEALTH LIBRARY", "PAGES"];

/** Every page in the sidebar and My Health, with words people search for. */
const PAGES: [string, string, string, string][] = [
  ["Dashboard", "Health score, today's vitals and medications", "/", "home overview score"],
  ["My Health › Overview", "Activity rings and every reading", "/health/overview", "readings rings"],
  ["My Health › Vitals", "Blood pressure, heart rate, glucose, weight", "/health/vitals", "bp blood pressure glucose sugar oxygen spo2 temperature weight bmi"],
  ["My Health › Activity", "Steps, distance, active energy", "/health/activity", "steps walking distance calories energy flights"],
  ["My Health › Sleep", "Last night, stages and consistency", "/health/sleep", "sleep bedtime rem deep"],
  ["My Health › Heart", "Heart rate, HRV, cardio fitness", "/health/heart", "heart hr hrv vo2 cardio cholesterol pulse zones"],
  ["My Health › Respiratory", "Breathing rate and blood oxygen", "/health/respiratory", "breathing respiratory oxygen spo2"],
  ["My Health › Body Composition", "Weight, body fat, measurements", "/health/body", "weight fat muscle waist bmi lean"],
  ["My Health › Mental Wellbeing", "Mood, stress and mindful minutes", "/health/mental", "mood stress mindful meditation anxiety"],
  ["My Health › Digital Wellbeing", "Screen time and pickups", "/health/digital", "screen phone pickups"],
  ["Workouts", "Sessions, history, plans and records", "/workouts", "exercise training gym run strength history plans personal records pr"],
  ["Nutrition", "Meals, macros and water", "/nutrition", "food meals calories protein carbs fat water hydration diet"],
  ["Goals", "What you're working towards", "/goals", "targets streaks"],
  ["Medical Records", "Lab results, prescriptions, documents", "/records", "documents files uploads labs"],
  ["Medications", "Today's doses and prescriptions", "/medications", "pills meds doses refills"],
  ["Appointments", "Upcoming and past visits", "/appointments", "visits doctor clinic calendar"],
  ["Health Library", "Articles and guides", "/library", "articles learn read"],
  ["Family", "People who share with you", "/family", "family dependents"],
  ["Notifications", "Reminders and alerts", "/notifications", "alerts reminders inbox"],
  ["Emergency Medical ID", "Allergies, conditions, contacts", "/emergency", "emergency allergies contacts ice qr"],
  ["Connected Devices", "Watches, monitors and sync", "/devices", "watch sync apple health connect monitor scale"],
  ["Privacy Centre", "Who can see your data", "/privacy", "privacy sharing share access consent"],
  ["Settings", "Profile, appearance, data export", "/settings", "account profile theme dark export delete"],
];

const norm = (s: string) => s.toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "");

export async function buildSearchIndex(api: VitalLinkClient): Promise<SearchIndex> {
  // Each source is optional: one failing (or unavailable in this mode) leaves the rest searchable.
  const safe = <T>(p: Promise<T>) => p.catch(() => null);
  const [me, records, meds, appts, library, panels] = await Promise.all([
    safe(api.me()),
    safe(api.recordsOverview()),
    safe(api.medicationsOverview()),
    safe(api.appointments()),
    safe(api.library()),
    safe(api.labPanels()),
  ]);
  // A lab record is found by the tests in it too ("chol" → Lipid Profile's LDL cholesterol).
  const tests = new Map((panels ?? []).map((p) => [p.id, p.results.map((r) => r.name).join(" ")]));
  const tz = me?.timezone ?? "America/Chicago";
  const entries: Entry[] = PAGES.map(([title, caption, to, words]) => ({ group: "PAGES", title, caption, to, icon: "page", hay: norm(`${title} ${caption} ${words}`) }));
  for (const r of records?.recent ?? []) {
    entries.push({
      group: "RECORDS & LABS",
      title: r.title,
      caption: [r.type === "lab" ? "Lab result" : r.type.charAt(0).toUpperCase() + r.type.slice(1), dayMonthYear(r.date, tz), r.source].filter(Boolean).join(" · "),
      to: r.panel_id ? `/records/labs/${r.panel_id}` : "/records",
      icon: "records",
      hay: norm(`${r.title} ${r.type} ${r.source} ${r.note ?? ""} ${r.panel_id ? (tests.get(r.panel_id) ?? "") : ""}`),
    });
  }
  for (const m of meds?.prescriptions ?? []) {
    entries.push({ group: "MEDICATIONS", title: `${m.name} ${m.strength}`, caption: [m.frequency, m.prescriber].filter(Boolean).join(" · "), to: "/medications", icon: "medications", hay: norm(`${m.name} ${m.strength} ${m.prescriber} ${m.form}`) });
  }
  for (const a of appts ?? []) {
    entries.push({
      group: "APPOINTMENTS",
      title: `${a.clinician} · ${a.specialty}`,
      caption: `${dayMonthYear(a.starts_at, tz)} · ${timeOfDay(a.starts_at, tz)} · ${a.location}`,
      to: "/appointments",
      icon: "appointments",
      hay: norm(`${a.clinician} ${a.specialty} ${a.location} ${a.notes ?? ""}`),
    });
  }
  const articles = library ? [library.featured, ...library.trending] : [];
  for (const a of articles.filter((x, i) => articles.findIndex((y) => y.id === x.id) === i)) {
    entries.push({ group: "HEALTH LIBRARY", title: a.title, caption: `Article · ${a.category}`, to: "/library", icon: "library", hay: norm(`${a.title} ${a.category} ${a.summary ?? ""}`) });
  }
  return { entries };
}

/** Entries containing every word typed (prefix order kept), at most `perGroup` per group, groups in design order. */
export function searchIndex(index: SearchIndex, query: string, perGroup = 3): { group: string; hits: SearchHit[] }[] {
  const words = norm(query).split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  const score = (e: Entry) => (norm(e.title).startsWith(words[0]) ? 0 : norm(e.title).includes(words[0]) ? 1 : 2);
  const matches = index.entries.filter((e) => words.every((w) => e.hay.includes(w))).sort((a, b) => score(a) - score(b));
  return GROUP_ORDER.map((group) => ({
    group,
    hits: matches.filter((m) => m.group === group).slice(0, perGroup).map(({ hay: _hay, ...hit }) => hit),
  })).filter((g) => g.hits.length);
}

// ---------------------------------------------------------------- top bar (Figma 207:1541)

export interface ShellVM {
  /** False sends the person to Create Account's setup steps. */
  onboarded: boolean;
  name: string;
  initials: string;
  email: string;
  /** Profile photo link, or null (initials show instead). */
  photo: string | null;
}
/** The signed-in person for the top bar and profile menu (GET /me only). */
export async function loadShell(api: VitalLinkClient): Promise<ShellVM> {
  const me = await api.me();
  const name = me.display_name || me.email.split("@")[0];
  return {
    onboarded: me.onboarded !== false,
    name,
    initials: name.split(/\s+/).map((s) => s[0]).join("").slice(0, 2).toUpperCase(),
    email: me.email,
    photo: me.avatar_url ?? null,
  };
}
