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
