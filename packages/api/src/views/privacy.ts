// Privacy Centre: access cards, the access history (Access details, Figma 211:1577) and Manage access (206:1516).
import type { VitalLinkClient } from "../client";
import { dayMonth, longDate, shortDateTime, timeOfDay } from "../format";
import { SHARE_SCOPES, todayIn } from "../forms";
import type { AccessLogEntry, Share } from "../types";
import type { ChipVM } from "./shared";

// ---------------------------------------------------------------- privacy

export interface AccessCardVM {
  key: string;
  label: string;
  value: string;
  caption: string;
  tone: "default" | "brand" | "critical" | "warning";
}

export interface AccessRowVM {
  who: string;
  detail: string;
  accessed: string;
  purpose: string;
  when: string;
  status: ChipVM;
  /** Access details dialog (Figma 211:1577). */
  details: {
    subtitle: string;
    rows: { label: string; value: string }[];
    /** The share it went through; `active` false once stopped or expired. */
    share: { id: string; who: string; line: string; until: string; active: boolean } | null;
  };
}

export interface PrivacyVM {
  /** Desktop Privacy Centre cards, in design order. */
  cards: AccessCardVM[];
  /** Mobile summary cards. */
  summary: AccessCardVM[];
  log: AccessRowVM[];
}

const surname = (label: string) => label.replace(/^Dr\.\s+/, "").split(" ").slice(-1)[0];

const RESULT: Record<AccessLogEntry["status"], string> = {
  authorised: "Authorised",
  limited: "Limited scope",
  blocked: "Blocked",
};

/** "Sunday 13 Sep 2026 · 9:12 AM" */
const fullWhen = (iso: string, tz: string) => {
  const [weekday, dm] = longDate(iso, tz).split(", ");
  return `${weekday} ${dm.replace(/(\d+) (\w{3})\w*/, "$1 $2")} ${todayIn(tz, Date.parse(iso)).slice(0, 4)} · ${timeOfDay(iso, tz)}`;
};

function accessDetails(e: AccessLogEntry, shares: Share[], tz: string): AccessRowVM["details"] {
  const share = e.share_id ? shares.find((s) => s.id === e.share_id) : undefined;
  const looked = e.status === "blocked" ? "tried to look at" : "looked at";
  const result =
    e.status === "blocked"
      ? `Blocked${e.purpose ? `: ${e.purpose.toLowerCase()}` : ""}`
      : `${RESULT[e.status]}${share ? ", allowed by the share below" : ""}`;
  return {
    subtitle: `${e.actor} ${looked} your record on ${dayMonth(e.at, tz).replace(/^0/, "")} at ${timeOfDay(e.at, tz)}.`,
    rows: [
      { label: "Who", value: e.actor },
      { label: "Signed in as", value: e.actor_detail },
      { label: "Opened", value: e.accessed },
      ...(e.status !== "blocked" && e.purpose ? [{ label: "Reason", value: e.purpose }] : []),
      { label: "When", value: fullWhen(e.at, tz) },
      { label: "Result", value: result },
    ],
    share: share ? { ...shareRow(share, tz), active: e.share_active && share.active } : null,
  };
}

const statusChip = (s: AccessLogEntry["status"]): [ChipVM, string] => {
  switch (s) {
    case "authorised":
      return [{ label: "Authorised", fg: "rangeNormal", bg: "rangeNormalBg" }, "Authorised"];
    case "limited":
      return [{ label: "Limited scope", fg: "rangeBorderline", bg: "rangeBorderlineBg" }, "Limited"];
    case "blocked":
      return [{ label: "Blocked", fg: "rangeHigh", bg: "rangeHighBg" }, "Blocked"];
  }
};

export async function loadPrivacy(api: VitalLinkClient): Promise<PrivacyVM> {
  const [me, shares, log, settings] = await Promise.all([
    api.me(),
    api.shares(),
    api.accessLog(),
    api.privacySettings(),
  ]);
  const active = shares.filter((s) => s.active);
  const of = (k: Share["grantee_kind"]) => active.filter((s) => s.grantee_kind === k);
  const doctors = of("doctor");
  const hospitals = of("hospital");
  const family = of("family");
  const apps = of("app");
  const appCaption = apps.length
    ? `${apps[0].grantee_label}: ${apps[0].scopes.join(", ")} only`
    : "None connected";
  const emergencyOn = settings?.emergency_on_lock_screen ?? false;
  const devices = settings?.devices;

  const cards: AccessCardVM[] = [
    { key: "doctors", label: "Doctors with access", value: String(doctors.length), caption: doctors.map((d) => surname(d.grantee_label)).join(", "), tone: "default" },
    // Shown because the desktop design includes it; see Share.grantee_kind.
    { key: "hospitals", label: "Hospitals with access", value: String(hospitals.length), caption: hospitals.map((h) => h.grantee_label).join(" · "), tone: "default" },
    { key: "family", label: "Family access", value: String(family.length), caption: family.map((f) => f.grantee_label).join(", "), tone: "brand" },
    { key: "emergency", label: "Emergency access", value: emergencyOn ? "On" : "Off", caption: "Medical ID visible when locked", tone: "critical" },
    { key: "apps", label: "Third-party apps", value: String(apps.length), caption: appCaption, tone: "warning" },
  ];
  if (devices) {
    cards.push({
      key: "devices",
      label: "Connected devices",
      value: String(devices.total),
      caption: `${devices.syncing} syncing · ${devices.overdue} overdue`,
      tone: "default",
    });
  }

  const people = doctors.length + family.length;
  const summary: AccessCardVM[] = [
    // Design copy said "links & family"; anonymous share links were removed in the
    // backend design review, so shares are always with named people.
    { key: "shares", label: "Active shares", value: String(people), caption: "doctors & family", tone: "brand" },
    { key: "family", label: "Family access", value: String(family.length), caption: "profiles", tone: "brand" },
    { key: "apps", label: "Connected apps", value: String(apps.length), caption: apps.length ? `${apps[0].scopes.join(", ")} only` : "none", tone: "warning" },
    { key: "emergency", label: "Emergency access", value: emergencyOn ? "On" : "Off", caption: "ID when locked", tone: "critical" },
  ];

  return {
    cards,
    summary,
    log: log.map((e) => {
      const [chip] = statusChip(e.status);
      return {
        who: e.actor,
        detail: e.actor_detail,
        accessed: e.accessed,
        purpose: e.purpose,
        when: shortDateTime(e.at, me.timezone),
        status: chip,
        details: accessDetails(e, shares, me.timezone),
      };
    }),
  };
}

// ---------------------------------------------------------------- Privacy › Manage access (Figma 206:1516)

const KIND_LABEL: Record<Share["grantee_kind"], string> = { doctor: "Doctor", family: "Family", other: "Other", app: "App", hospital: "Hospital" };
export interface ManageAccessVM {
  title: string;
  rows: { id: string; who: string; line: string; until: string }[];
  empty: string | null;
}
/** Active shares, optionally only doctors or family (the Privacy Centre card that opened it). */
/** "Doctor · Vitals, Records & labs" and "Until 27 Jan 2027" for a share row. */
export function shareRow(s: Share, tz: string) {
  const scopes = SHARE_SCOPES.filter((x) => s.scopes.includes(x.value)).map((x) => x.label).join(", ");
  return {
    id: s.id,
    who: s.grantee_label,
    line: `${KIND_LABEL[s.grantee_kind]} · ${scopes}`,
    until: s.expires_at ? `Until ${dayMonth(s.expires_at, tz).replace(/^0/, "")} ${todayIn(tz, Date.parse(s.expires_at)).slice(0, 4)}` : "No end date",
  };
}

export async function loadManageAccess(api: VitalLinkClient, kind: "doctor" | "family" | null): Promise<ManageAccessVM> {
  const [me, shares] = await Promise.all([api.me(), api.shares()]);
  const tz = me.timezone;
  const list = shares.filter((s) => s.active && (kind === null || s.grantee_kind === kind)).sort((a, b) => a.grantee_label.localeCompare(b.grantee_label));
  return {
    title: kind === "doctor" ? "Doctors with access" : kind === "family" ? "Family access" : "Everyone with access",
    rows: list.map((s) => shareRow(s, tz)),
    empty: list.length ? null : "No one has access right now.",
  };
}
