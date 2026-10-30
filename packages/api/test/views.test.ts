// Every screen's view-model builds from the sample data, and mock mode never pretends to save.
import { describe, expect, it } from "vitest";
import * as api from "../src";
import { VitalLinkClient } from "../src";

const client = new VitalLinkClient();
// loadX(api) loaders; the ones that need an id are covered below.
const loaders = Object.entries(api).filter(
  ([name, fn]) => name.startsWith("load") && typeof fn === "function" && (fn as (...a: unknown[]) => unknown).length === 1,
) as [string, (c: VitalLinkClient) => Promise<unknown>][];

describe("view-models from the Figma sample data", () => {
  it("finds the screen loaders", () => {
    expect(loaders.length).toBeGreaterThan(15);
  });

  it.each(loaders)("%s builds", async (_, load) => {
    const vm = await load(client);
    expect(vm).toBeTruthy();
    // No formatting gaps leak to the screen.
    expect(JSON.stringify(vm)).not.toMatch(/undefined|NaN|\[object Object\]/);
  });

  it("builds the lab results and a document", async () => {
    const labs = await api.loadLabResults(client);
    expect(labs.rows.length).toBeGreaterThan(0);
    const overview = await client.recordsOverview();
    const withPanel = overview.recent.find((r) => r.panel_id);
    expect(withPanel).toBeTruthy();
    const doc = await api.loadDocument(client, withPanel!.id);
    expect(doc.title).toBeTruthy();
  });
});

describe("tab view-models", () => {
  it("keeps half kilos in the heaviest lift", async () => {
    const c = new VitalLinkClient();
    c.personalRecords = async () => [
      { exercise: "Back Squat", achieved_at: "2026-09-10T07:00:00-05:00", weight_kg: 92.5, reps: 5, time_s: null },
      { exercise: "5 km run", achieved_at: "2026-09-06T07:00:00-05:00", weight_kg: null, reps: null, time_s: 1662 },
    ];
    const vm = await api.loadPersonalRecords(c);
    expect(vm.stats.find((s) => s.key === "heaviest")?.value).toBe("92.5");
    expect(vm.rows.map((r) => r.value)).toEqual(["92.5 kg × 5", "27:42"]);
  });

  it("filters exercises by name and muscle", async () => {
    const vm = await api.loadExerciseLibrary(client);
    expect(api.filterExercises(vm, "row", "all").map((e) => e.name)).toEqual(["Barbell Row"]);
    expect(api.filterExercises(vm, "", "legs").every((e) => e.muscle === "Legs")).toBe(true);
  });

  it("builds a week of nutrition with today's real sample day", async () => {
    const vm = await api.loadNutritionWeek(client);
    expect(vm.bars).toHaveLength(7);
    expect(vm.days[0].kcal).toBe("1,842 kcal"); // newest first: today is the sample day
    expect(vm.bars.some((b) => b.onTarget)).toBe(true);
  });

  it("filters workout history by type", async () => {
    const runs = await api.loadWorkoutHistory(client, 90, "run");
    expect(runs.groups.flatMap((g) => g.rows).every((r) => r.icon === "run")).toBe(true);
    const none = await api.loadWorkoutHistory(client, 30, "strength");
    expect(none.empty === null || none.empty.includes("strength")).toBe(true);
  });
});

describe("top-bar search", () => {
  it("finds pages, labs by the tests in them, and medications", async () => {
    const index = await api.buildSearchIndex(client);
    const titles = (q: string) => api.searchIndex(index, q).flatMap((g) => g.hits.map((h) => `${g.group}: ${h.title}`));
    expect(titles("sleep")).toContain("PAGES: My Health › Sleep");
    expect(titles("hemoglobin").some((t) => t.startsWith("RECORDS & LABS"))).toBe(true);
    expect(titles("atorvastatin")).toContain("MEDICATIONS: Atorvastatin 10 mg");
    expect(titles("blood pressure vitals")).toEqual(["PAGES: My Health › Vitals"]);
    expect(titles("   ")).toEqual([]);
    expect(titles("zzzz")).toEqual([]);
  });

  it("names the signed-in person for the top bar", async () => {
    expect(await api.loadShell(client)).toMatchObject({ name: "Alex Carter", initials: "AC" });
  });
});

describe("lab export", () => {
  it("writes the panel as CSV with quoting", async () => {
    const vm = await api.loadLabResults(client);
    const f = api.labCsv({ ...vm, rows: [{ ...vm.rows[0], name: 'LDL, "bad"' }] }, "Lipid Profile", "2026-10-26");
    expect(f.filename).toBe("lipid-profile-2026-10-26.csv");
    const [head, row] = f.text.trim().split("\r\n");
    expect(head).toBe("Test,Result,Unit,Reference range,Status,Previous");
    expect(row.startsWith('"LDL, ""bad"""')).toBe(true);
  });
});

describe("article reader", () => {
  it("parses the library's Markdown subset into safe blocks", () => {
    const blocks = api.parseArticle("Intro with **60 and 100** bpm.\nsame paragraph\n\n## Why it moves\n\n- **Fitness:** lower\n- Sleep\n\nLast <b>raw</b> line.");
    expect(blocks).toEqual([
      { kind: "paragraph", parts: [{ text: "Intro with ", bold: false }, { text: "60 and 100", bold: true }, { text: " bpm. same paragraph", bold: false }] },
      { kind: "heading", text: "Why it moves" },
      { kind: "list", items: [[{ text: "Fitness:", bold: true }, { text: " lower", bold: false }], [{ text: "Sleep", bold: false }]] },
      { kind: "paragraph", parts: [{ text: "Last <b>raw</b> line.", bold: false }] },
    ]);
  });

  it("reads *italic* runs", () => {
    expect(api.parseArticle("*Not advice.* Ask a clinician.")).toEqual([
      { kind: "paragraph", parts: [{ text: "Not advice.", bold: false, italic: true }, { text: " Ask a clinician.", bold: false }] },
    ]);
  });

  it("loads an article with related ones", async () => {
    const lib = await client.library();
    const vm = await api.loadArticle(client, lib.featured.slug ?? lib.featured.id);
    expect(vm.title).toBe(lib.featured.title);
    expect(vm.related.length).toBeGreaterThan(0);
    expect(vm.related.every((r) => r.slug !== (lib.featured.slug ?? lib.featured.id))).toBe(true);
  });
});

describe("mock mode writes", () => {
  it("change nothing and resolve with null", async () => {
    expect(client.isMock).toBe(true);
    await expect(client.addReading("weight", 70, "2026-10-01T08:00:00-05:00")).resolves.toBeNull();
    await expect(client.logMeal({})).resolves.toBeNull();
    await expect(client.cancelAppointment("x")).resolves.toBeNull();
    await expect(client.exportData()).resolves.toBeNull();
    await expect(client.recordDownloadUrl("x")).resolves.toBeNull();
    await expect(client.searchFoods("chicken")).resolves.toEqual([]);
  });

  it("still serves the lists a form needs", async () => {
    expect((await client.goalMetrics()).map((m) => m.code)).toContain("steps");
    expect((await client.conditions()).length).toBeGreaterThan(0);
  });
});

describe("Privacy › Access details and Devices › Permissions", () => {
  it("explains one access-history row and links the share it went through", async () => {
    const v = await api.loadPrivacy(client);
    const d = v.log[0].details;
    expect(d.subtitle).toBe("Dr. Laura Bennett looked at your record on 13 Sep at 9:12 AM.");
    expect(d.rows.map((r) => r.label)).toEqual(["Who", "Signed in as", "Opened", "Reason", "When", "Result"]);
    expect(d.rows.find((r) => r.label === "When")!.value).toBe("Sunday 13 Sep 2026 · 9:12 AM");
    expect(d.share).toMatchObject({ id: "s1", who: "Dr. Laura Bennett", active: true });
    const blocked = v.log[4].details;
    expect(blocked.subtitle).toMatch(/tried to look at/);
    expect(blocked.rows.find((r) => r.label === "Result")!.value).toBe("Blocked — failed authentication");
    expect(blocked.share).toBeNull();
  });

  it("lists each kind of data a device sends", async () => {
    const v = await api.loadDevices(client);
    expect(v.devices[0].dataTypes.length).toBeGreaterThan(0);
    expect(v.devices[0].data).toBe(v.devices[0].dataTypes.join(" · "));
  });
});

describe("Connected Devices › phone sync", () => {
  it("summarises the phone's uploads", async () => {
    const v = await api.loadDevices(client);
    expect(v.phones).toHaveLength(1);
    const p = v.phones[0];
    expect(p.title).toBe("iPhone · Apple Health");
    expect(p.caption).toBe("VitalLink app · last upload 4 min ago");
    expect(p.status.label).toBe("Connected");
    expect(p.summary).toBe("6 uploads this week · 1,296 readings · 412 new or updated");
    expect(p.uploads.map((u) => u.when)).toEqual(["Today · 8:41 AM", "Today · 7:02 AM", "Yesterday · 9:15 PM"]);
    expect(p.uploads[1].detail).toBe("180 readings · 36 new · 144 unchanged");
  });

  it("says when an upload changed nothing", () => {
    expect(api.uploadDetail({ id: 1, at: "", platform: "healthkit", submitted: 3, inserted: 0, updated: 0, unchanged: 3 })).toBe("3 readings · nothing new");
    expect(api.uploadDetail({ id: 1, at: "", platform: "healthkit", submitted: 1, inserted: 0, updated: 1, unchanged: 0 })).toBe("1 reading · 1 updated");
  });
});
