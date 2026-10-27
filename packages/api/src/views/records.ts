// Records view-models: Medical Records (143:1538), Lab Results (61:53),
// Document Viewer (143:1955) and mobile Records (145:343).
import type { ColorToken } from "@vitallink/tokens";
import { need, NotAvailableError, type VitalLinkClient } from "../client";
import { calendarDate, dayMonthYear, daysFrom, longDayMonthYear } from "../format";
import type { ChipVM } from "../screens";
import type { LabPanel, LabResult, RecordType } from "../types";

const TYPE_LABEL: Record<RecordType, [string, string]> = {
  lab: ["Lab results", "Lab result"],
  prescription: ["Prescriptions", "Prescription"],
  imaging: ["Imaging", "Imaging"],
  visit: ["Visit summaries", "Visit summary"],
  vaccination: ["Vaccinations", "Vaccination"],
  allergy: ["Allergies", "Allergy"],
  procedure: ["Procedures", "Procedure"],
  upload: ["Uploaded documents", "Uploaded document"],
};

const date = (iso: string, tz: string) => dayMonthYear(iso, tz);

// ---------------------------------------------------------------- records

export interface RecordRowVM {
  id: string;
  type: RecordType;
  title: string;
  source: string;
  sourceColor: ColorToken;
  typeLabel: string;
  date: string;
  /** Mobile: "Lab result · 11 Sep 2026" (+ " · shared"). */
  mobileLine: string;
  shared: boolean;
  /** Opens the Document Viewer. */
  href: string;
}

export interface RecordsVM {
  subtitle: string;
  shortSubtitle: string;
  total: number;
  types: { key: RecordType; label: string; count: number }[];
  rows: RecordRowVM[];
  shares: { name: string; caption: string }[];
}

export async function loadRecords(api: VitalLinkClient): Promise<RecordsVM> {
  const [me, data] = await Promise.all([api.me(), api.recordsOverview()]);
  const r = need(data, "Medical records");
  const tz = me.timezone;
  const now = api.now();
  const total = Object.values(r.counts).reduce((a, b) => a + b, 0);
  return {
    subtitle: `${total} records · you own and control every one of them`,
    shortSubtitle: `${total} records · all owned by you`,
    total,
    types: (Object.keys(TYPE_LABEL) as RecordType[]).map((k) => ({ key: k, label: TYPE_LABEL[k][0], count: r.counts[k] })),
    rows: r.recent.map((x) => {
      const detail = x.shared_with ? `shared with ${x.shared_with}` : x.note ?? (x.type === "lab" ? "not shared" : "");
      return {
        id: x.id,
        type: x.type,
        title: x.title,
        source: [x.source, detail].filter(Boolean).join(" · "),
        sourceColor: x.shared_with ? "goalOnTrack" : "textTertiary",
        typeLabel: TYPE_LABEL[x.type][1],
        date: date(x.date, tz),
        mobileLine: `${TYPE_LABEL[x.type][1]} · ${date(x.date, tz)}`,
        shared: !!x.shared_with,
        href: `/records/${x.id}`,
      };
    }),
    shares: r.shares.map((s) => {
      const days = s.expires_at ? daysFrom(s.expires_at, now, tz) : null;
      const parts = [s.records !== null ? `${s.records} records` : s.scope, days !== null ? `expires in ${days} days` : null];
      return { name: s.grantee, caption: parts.filter(Boolean).join(" · ") };
    }),
  };
}

// ---------------------------------------------------------------- lab results

export interface LabRowVM {
  name: string;
  value: string;
  valueColor: ColorToken;
  unit: string;
  range: string;
  status: ChipVM;
  previous: string;
  trend: string;
  trendLabel: string;
  rowBg: ColorToken | null;
}

export interface LabResultsVM {
  panelId: string;
  subtitle: string;
  tints: { key: string; label: string; value: string; bg: ColorToken; fg: ColorToken }[];
  rows: LabRowVM[];
}

const fixed = (v: number, d: number) => v.toFixed(d);
const direction = (r: LabResult) => (r.value < r.low ? "low" : r.value > r.high ? "high" : "normal");

const labRow = (r: LabResult): LabRowVM => {
  const dir = direction(r);
  const chip: ChipVM =
    dir === "low" ? { label: "Low", fg: "rangeLow", bg: "rangeLowBg" } : dir === "high" ? { label: "High", fg: "rangeHigh", bg: "rangeHighBg" } : { label: "Normal", fg: "rangeNormal", bg: "rangeNormalBg" };
  const trend = r.previous === null ? "" : r.value > r.previous ? "↑" : r.value < r.previous ? "↓" : "→";
  return {
    name: r.name,
    value: fixed(r.value, r.decimals),
    valueColor: dir === "low" ? "rangeLow" : dir === "high" ? "rangeHigh" : "textPrimary",
    unit: r.unit,
    range: `${fixed(r.low, r.decimals)} – ${fixed(r.high, r.decimals)}`,
    status: chip,
    previous: r.previous === null ? "—" : fixed(r.previous, r.decimals),
    trend,
    trendLabel: trend === "↑" ? "up" : trend === "↓" ? "down" : trend === "→" ? "unchanged" : "",
    rowBg: dir === "low" ? "rangeLowBg" : dir === "high" ? "rangeHighBg" : null,
  };
};

async function panel(api: VitalLinkClient, id?: string): Promise<LabPanel> {
  const panels = need(await api.labPanels(), "Lab results");
  const p = id ? panels.find((x) => x.id === id) : panels[0];
  if (!p) throw new NotAvailableError("This lab panel");
  return p;
}

export async function loadLabResults(api: VitalLinkClient, panelId?: string): Promise<LabResultsVM> {
  const [me, p] = await Promise.all([api.me(), panel(api, panelId)]);
  const count = (i: LabResult["interpretation"]) => p.results.filter((r) => r.interpretation === i).length;
  return {
    panelId: p.id,
    subtitle: `${p.name} · ${p.facility} · ordered by ${p.ordered_by} · collected ${date(p.collected_at, me.timezone).replace(/^0/, "")}`,
    tints: [
      { key: "tests", label: "Tests in panel", value: String(p.results.length), bg: "bgSurface", fg: "textPrimary" },
      { key: "normal", label: "Within range", value: String(count("normal")), bg: "rangeNormalBg", fg: "rangeNormal" },
      { key: "borderline", label: "Borderline", value: String(count("borderline")), bg: "rangeBorderlineBg", fg: "rangeBorderline" },
      { key: "abnormal", label: "Out of range", value: String(count("abnormal")), bg: "rangeHighBg", fg: "rangeHigh" },
    ],
    rows: p.results.map(labRow),
  };
}

// ---------------------------------------------------------------- document viewer

export interface DocumentVM {
  title: string;
  subtitle: string;
  page: { overline: string; title: string; meta: string; rows: LabRowVM[]; footnote: string };
  details: { label: string; value: string }[];
  sharing: { name: string; caption: string; color: ColorToken }[];
  note: string | null;
  /** The record has an uploaded file, so Download can fetch it (GET /me/records/{id}/download). */
  canDownload: boolean;
}

export async function loadDocument(api: VitalLinkClient, recordId: string): Promise<DocumentVM> {
  const [me, overview, account] = await Promise.all([api.me(), api.recordsOverview(), api.account()]);
  const rec = need(overview, "Medical records").recent.find((r) => r.id === recordId);
  if (!rec || !rec.panel_id) throw new NotAvailableError("A preview of this document");
  const p = await panel(api, rec.panel_id);
  const tz = me.timezone;
  const now = api.now();
  const share = need(overview, "Medical records").shares.find((s) => rec.shared_with && s.grantee.endsWith(rec.shared_with.replace(/^Dr\.\s*/, "")));
  const shown = p.results.filter((r) => p.report_results.includes(r.name)).map(labRow);
  const name = me.display_name ?? me.email;
  return {
    title: p.name,
    subtitle: `Lab result · ${rec.source} · collected ${longDayMonthYear(p.collected_at, tz)}`,
    page: {
      overline: p.laboratory.toUpperCase(),
      title: p.name,
      meta: [`Patient: ${name}`, account.date_of_birth ? `DOB ${calendarDate(account.date_of_birth)}` : null, `Collected ${date(p.collected_at, tz).replace(/^0/, "")}`, `Reported ${date(p.reported_at, tz).replace(/^0/, "")}`]
        .filter(Boolean)
        .join(" · "),
      rows: shown,
      footnote: "Reference ranges vary by laboratory. This report is a copy stored in your VitalLink account.",
    },
    details: [
      { label: "Type", value: "Lab result" },
      { label: "Source", value: p.laboratory },
      { label: "Ordered by", value: p.ordered_by },
      { label: "Collected", value: date(p.collected_at, tz).replace(/^0/, "") },
      { label: "Added to VitalLink", value: date(p.added_at, tz).replace(/^0/, "") },
      ...(p.file ? [{ label: "File", value: `${p.file.name} · ${p.file.size_kb} KB` }] : []),
    ],
    // Shares are always with named people (anonymous links were removed in the backend review).
    sharing: share
      ? [{ name: share.grantee, caption: `Shared${share.expires_at ? ` · expires in ${daysFrom(share.expires_at, now, tz)} days` : ""}`, color: "goalOnTrack" }]
      : [{ name: "Not shared", caption: "Only you can see this record", color: "textTertiary" }],
    note: p.note,
    canDownload: rec.file?.status === "attached",
  };
}
