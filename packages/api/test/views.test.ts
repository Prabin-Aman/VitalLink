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
