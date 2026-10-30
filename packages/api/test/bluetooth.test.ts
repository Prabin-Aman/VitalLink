import { describe, expect, it } from "vitest";
import { bleIngest, bleProfile, bleSummary, checkImport, decodeBle, importSummary, sfloat } from "../src";

const view = (...bytes: number[]) => new DataView(new Uint8Array(bytes).buffer);
const le16 = (n: number) => [n & 0xff, (n >> 8) & 0xff];
const NOW = "2026-10-30T15:00:00.000Z";

describe("Bluetooth health profiles", () => {
  it("decodes IEEE-11073 SFLOAT and its reserved values", () => {
    expect(sfloat(0x0079)).toBe(121); // 121 × 10⁰
    expect(sfloat(0xf3e3)).toBe(99.5); // 995 × 10⁻¹
    expect(sfloat(0x07ff)).toBeNull(); // NaN
    expect(sfloat(0x0fff)).toBe(-1);
  });

  it("reads heart rate in 8 and 16 bits", () => {
    expect(decodeBle("heart_rate", view(0x00, 72), NOW)).toEqual({ at: NOW, values: [{ metric: "heart_rate", value: 72 }] });
    expect(decodeBle("heart_rate", view(0x01, ...le16(301)), NOW)!.values[0].value).toBe(301);
    expect(decodeBle("heart_rate", view(0x00, 0), NOW)).toBeNull(); // no contact
  });

  it("reads a cuff's pressure, device time and pulse", () => {
    // flags: mmHg, timestamp, pulse; 121/79 (MAP 93); 2026-10-30 08:42:00 local; pulse 68
    const r = decodeBle("blood_pressure", view(0x06, ...le16(121), ...le16(79), ...le16(93), ...le16(2026), 10, 30, 8, 42, 0, ...le16(68)), NOW, 300)!;
    expect(r.at).toBe("2026-10-30T13:42:00.000Z"); // Chicago is UTC−5 in late October
    expect(bleSummary(r)).toBe("121 / 79 mmHg · pulse 68");
    // kPa → mmHg
    const k = decodeBle("blood_pressure", view(0x01, ...le16(0xf0a1), ...le16(0xf069), ...le16(0)), NOW)!; // 16.1 / 10.5 kPa
    expect(k.values.map((v) => v.value)).toEqual([121, 79]);
  });

  it("reads a scale in kg or lb", () => {
    expect(decodeBle("weight", view(0x00, ...le16(14480)), NOW)!.values[0]).toEqual({ metric: "weight", value: 72.4 });
    expect(decodeBle("weight", view(0x01, ...le16(16000)), NOW)!.values[0].value).toBe(72.57); // 160.00 lb
    expect(decodeBle("weight", view(0x00, 0xff, 0xff), NOW)).toBeNull();
  });

  it("reads an oximeter and a glucose meter", () => {
    expect(bleSummary(decodeBle("pulse_oximeter", view(0x00, ...le16(97), ...le16(66)), NOW)!)).toBe("SpO₂ 97% · pulse 66");
    // flags: concentration present, kg/L; seq 1; 2026-10-30 07:00:00; 0.00104 kg/L = 104 mg/dL (104 × 10⁻⁵)
    const g = decodeBle("glucose", view(0x02, ...le16(1), ...le16(2026), 10, 30, 7, 0, 0, ...le16(0xb068), 0x11), NOW, 0)!;
    expect(g).toEqual({ at: "2026-10-30T07:00:00.000Z", values: [{ metric: "glucose", value: 104 }] });
  });

  it("averages readings into 5-minute buckets and names the device", () => {
    const body = bleIngest(
      [
        { at: "2026-10-30T15:01:00.000Z", values: [{ metric: "heart_rate", value: 70 }] },
        { at: "2026-10-30T15:03:00.000Z", values: [{ metric: "heart_rate", value: 74 }] },
        { at: "2026-10-30T15:06:00.000Z", values: [{ metric: "heart_rate", value: 80 }] },
      ],
      { id: "abc", name: "Polar H10" },
      bleProfile("heart_rate"),
      "k1",
    );
    expect(body.platform).toBe("bluetooth");
    expect(body.devices[0]).toMatchObject({ external_id: "abc", name: "Polar H10", kind: "wearable" });
    expect(body.buckets).toEqual([
      { metric: "heart_rate", start: "2026-10-30T15:00:00Z", value: 72, samples: 2 },
      { metric: "heart_rate", start: "2026-10-30T15:05:00Z", value: 80, samples: 1 },
    ]);
  });
});

describe("Import health data", () => {
  it("accepts Apple exports and CSVs up to 300 MB", () => {
    expect(checkImport(null)).toEqual({ ok: false, error: "Choose a file to import." });
    expect(checkImport({ name: "notes.pdf", size: 10 }).ok).toBe(false);
    expect(checkImport({ name: "export.zip", size: 301 * 1024 * 1024 }).ok).toBe(false);
    expect(checkImport({ name: "Readings.CSV", size: 10 }).ok).toBe(true);
  });

  it("summarises what a file added", () => {
    expect(importSummary({ readings: 1284, inserted: 1200, updated: 0, unchanged: 84, skipped: 3, from: "2026-09-12T15:00:00Z", to: "2026-10-30T15:00:00Z" }, "America/Chicago")).toBe(
      "Imported 1,284 readings · 12 Sep – 30 Oct · 1,200 new, 84 unchanged · 3 skipped",
    );
  });
});
