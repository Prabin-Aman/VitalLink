// Bluetooth health devices paired in the browser (Web Bluetooth): the standard GATT health profiles, decoded
// into readings, and the ingest body that stores them (platform "bluetooth"). Pure — the web screen owns the
// connection. Profiles: Bluetooth SIG Heart Rate, Blood Pressure, Weight Scale, Pulse Oximeter, Glucose.
import type { Option } from "./forms";

export type BleKind = "heart_rate" | "blood_pressure" | "weight" | "pulse_oximeter" | "glucose";

/** A decoded measurement: one or more catalogue readings taken at `at` (device time, or when it arrived). */
export interface BleReading {
  at: string;
  values: { metric: string; value: number }[];
}

export interface BleProfile {
  kind: BleKind;
  label: string;
  /** GATT service and the measurement characteristic (16-bit UUIDs, as Web Bluetooth accepts them). */
  service: number;
  characteristic: number;
  /** Heart rate streams; the others send one measurement per reading taken on the device. */
  streaming: boolean;
  deviceKind: "wearable" | "home_monitor";
  metrics: string[];
}

export const BLE_PROFILES: BleProfile[] = [
  { kind: "heart_rate", label: "Heart-rate strap", service: 0x180d, characteristic: 0x2a37, streaming: true, deviceKind: "wearable", metrics: ["heart_rate"] },
  { kind: "blood_pressure", label: "Blood pressure cuff", service: 0x1810, characteristic: 0x2a35, streaming: false, deviceKind: "home_monitor", metrics: ["bp_systolic", "bp_diastolic", "heart_rate"] },
  { kind: "weight", label: "Scale", service: 0x181d, characteristic: 0x2a9d, streaming: false, deviceKind: "home_monitor", metrics: ["weight"] },
  { kind: "pulse_oximeter", label: "Pulse oximeter", service: 0x1822, characteristic: 0x2a5e, streaming: false, deviceKind: "home_monitor", metrics: ["oxygen_saturation", "heart_rate"] },
  { kind: "glucose", label: "Glucose meter", service: 0x1808, characteristic: 0x2a18, streaming: false, deviceKind: "home_monitor", metrics: ["glucose"] },
];
export const BLE_KINDS: Option<BleKind>[] = BLE_PROFILES.map((p) => ({ value: p.kind, label: p.label }));
export const bleProfile = (kind: BleKind) => BLE_PROFILES.find((p) => p.kind === kind)!;

/** Pulse oximeters may only offer continuous measurements (0x2A5F); glucose meters need the record access point. */
export const PLX_CONTINUOUS = 0x2a5f;
export const GLUCOSE_RACP = 0x2a52;

// ---------------------------------------------------------------- IEEE-11073 numbers

/** 16-bit SFLOAT: 12-bit signed mantissa, 4-bit signed exponent. Reserved values (NaN, ±INF…) → null. */
export function sfloat(raw: number): number | null {
  const m = raw & 0x0fff;
  if (m === 0x07ff || m === 0x0800 || m === 0x07fe || m === 0x0802 || m === 0x0801) return null;
  const mantissa = m >= 0x0800 ? m - 0x1000 : m;
  const e = (raw >> 12) & 0x0f;
  const exponent = e >= 0x08 ? e - 0x10 : e;
  return Number((mantissa * 10 ** exponent).toPrecision(6));
}

/** A device clock may run this far ahead of the browser's before its time is taken as wrong. */
const CLOCK_SLACK_MS = 10 * 60000;

/**
 * Date Time (7 bytes: year u16, month, day, hours, minutes, seconds), device-local. Null when the clock is unset
 * or plainly wrong (a year before 2010, or a time still to come): a device whose clock was never set reports its
 * factory date, and the API refuses a whole upload over one reading from the future.
 */
function dateTime(v: DataView, at: number, offsetMinutes: number, now: string): string | null {
  const year = v.getUint16(at, true);
  const month = v.getUint8(at + 2);
  const day = v.getUint8(at + 3);
  if (year < 2010 || !month || !day) return null;
  const local = Date.UTC(year, month - 1, day, v.getUint8(at + 4), v.getUint8(at + 5), v.getUint8(at + 6));
  // Devices keep wall-clock time; treat it as the browser's zone.
  const t = local + offsetMinutes * 60000;
  if (!Number.isFinite(t) || t > Date.parse(now) + CLOCK_SLACK_MS) return null;
  return new Date(t).toISOString();
}

/** What the API accepts for each metric (backend health/Catalogue.java); it refuses a whole upload over one value outside. */
const ACCEPTED: Record<string, [number, number]> = {
  heart_rate: [25, 250],
  bp_systolic: [60, 260],
  bp_diastolic: [30, 160],
  oxygen_saturation: [50, 100],
  glucose: [20, 600],
  weight: [20, 400],
};
const accepted = (x: { metric: string; value: number }) => {
  const r = ACCEPTED[x.metric];
  return !!r && Number.isFinite(x.value) && x.value >= r[0] && x.value <= r[1];
};

const round = (n: number, d = 1) => Math.round(n * 10 ** d) / 10 ** d;

/**
 * Decodes one notification/indication of a profile's measurement characteristic. `now` is when it arrived (used
 * when the device sends no time, or one that can't be right); `tzOffset` is `new Date().getTimezoneOffset()` for
 * device-local times. `continuous` is an oximeter's streaming characteristic (0x2A5F), which has no timestamp.
 * Null when there's nothing to keep: a failed or cut-short measurement, or a main value no person can have. A
 * pulse outside the accepted range is dropped on its own.
 */
export function decodeBle(kind: BleKind, v: DataView, now: string, tzOffset = 0, continuous = false): BleReading | null {
  let r: BleReading | null;
  try {
    r = decode(kind, v, now, tzOffset, continuous);
  } catch {
    return null; // shorter than its flags say
  }
  if (!r || !accepted(r.values[0])) return null;
  // Blood pressure is a pair: one number without the other isn't a reading.
  if (kind === "blood_pressure" && !accepted(r.values[1])) return null;
  return { at: r.at, values: r.values.filter(accepted) };
}

function decode(kind: BleKind, v: DataView, now: string, tzOffset: number, continuous: boolean): BleReading | null {
  const flags = v.getUint8(0);
  switch (kind) {
    case "heart_rate": {
      // Bits 1-2: a strap that can tell (bit 2) and isn't on skin (bit 1 clear) sends numbers that mean nothing.
      if ((flags & 0x06) === 0x04) return null;
      const bpm = flags & 0x01 ? v.getUint16(1, true) : v.getUint8(1);
      return bpm > 0 ? { at: now, values: [{ metric: "heart_rate", value: bpm }] } : null;
    }
    case "blood_pressure": {
      const kpa = (flags & 0x01) !== 0;
      const unit = (x: number | null) => (x === null ? null : kpa ? round(x * 7.50062, 0) : x);
      const sys = unit(sfloat(v.getUint16(1, true)));
      const dia = unit(sfloat(v.getUint16(3, true)));
      let i = 7;
      let at = now;
      if (flags & 0x02) {
        at = dateTime(v, i, tzOffset, now) ?? now;
        i += 7;
      }
      const pulse = flags & 0x04 ? sfloat(v.getUint16(i, true)) : null;
      if (sys === null || dia === null) return null;
      const values = [
        { metric: "bp_systolic", value: sys },
        { metric: "bp_diastolic", value: dia },
      ];
      if (pulse !== null) values.push({ metric: "heart_rate", value: pulse });
      return { at, values };
    }
    case "weight": {
      const raw = v.getUint16(1, true);
      if (raw === 0xffff) return null; // measurement unsuccessful
      const kg = flags & 0x01 ? raw * 0.01 * 0.45359237 : raw * 0.005;
      const at = flags & 0x02 ? (dateTime(v, 3, tzOffset, now) ?? now) : now;
      return { at, values: [{ metric: "weight", value: round(kg, 2) }] };
    }
    case "pulse_oximeter": {
      // Spot-check (0x2A5E): flags, SpO2, PR, [timestamp]. Continuous (0x2A5F) has the same first two values, but
      // its flag bit 0 means more readings follow, not a timestamp.
      const spo2 = sfloat(v.getUint16(1, true));
      const pr = sfloat(v.getUint16(3, true));
      const at = !continuous && flags & 0x01 && v.byteLength >= 12 ? (dateTime(v, 5, tzOffset, now) ?? now) : now;
      if (spo2 === null) return null;
      const values = [{ metric: "oxygen_saturation", value: spo2 }];
      if (pr !== null) values.push({ metric: "heart_rate", value: pr });
      return { at, values };
    }
    case "glucose": {
      // flags, sequence u16, base time (7), [time offset s16], [concentration SFLOAT, type/location u8].
      // A meter sends every stored record at once, so a record without a usable time can't be placed: skip it.
      let i = 3;
      const base = dateTime(v, i, tzOffset, now);
      if (base === null) return null;
      let at = base;
      i += 7;
      if (flags & 0x01) {
        at = new Date(Date.parse(at) + v.getInt16(i, true) * 60000).toISOString();
        i += 2;
      }
      if (!(flags & 0x02)) return null;
      const c = sfloat(v.getUint16(i, true));
      if (c === null) return null;
      // kg/L (×100,000 → mg/dL) or mol/L (×1,000 → mmol/L, ×18.016 → mg/dL)
      const mg = flags & 0x04 ? c * 1000 * 18.016 : c * 100000;
      return { at, values: [{ metric: "glucose", value: round(mg, 0) }] };
    }
  }
}

/** "121 / 79 mmHg · pulse 68", "72 bpm", "72.6 kg", "SpO₂ 97% · pulse 66", "104 mg/dL" */
export function bleSummary(r: BleReading): string {
  const get = (m: string) => r.values.find((x) => x.metric === m)?.value;
  const sys = get("bp_systolic");
  const pulse = get("heart_rate");
  if (sys !== undefined) return `${sys} / ${get("bp_diastolic")} mmHg${pulse !== undefined ? ` · pulse ${pulse}` : ""}`;
  const spo2 = get("oxygen_saturation");
  if (spo2 !== undefined) return `SpO₂ ${spo2}%${pulse !== undefined ? ` · pulse ${pulse}` : ""}`;
  const kg = get("weight");
  if (kg !== undefined) return `${kg} kg`;
  const glucose = get("glucose");
  if (glucose !== undefined) return `${glucose} mg/dL`;
  return `${pulse} bpm`;
}

/** POST /me/health/ingest body: 5-minute buckets (averages with their sample counts) and the device. */
export function bleIngest(readings: BleReading[], device: { id: string; name: string }, profile: BleProfile, key: string) {
  const buckets = new Map<string, { metric: string; start: string; sum: number; n: number }>();
  for (const r of readings) {
    const t = Date.parse(r.at);
    const start = new Date(t - (t % 300000)).toISOString().replace(".000Z", "Z");
    for (const x of r.values) {
      const k = `${x.metric}@${start}`;
      const b = buckets.get(k) ?? { metric: x.metric, start, sum: 0, n: 0 };
      b.sum += x.value;
      b.n += 1;
      buckets.set(k, b);
    }
  }
  return {
    idempotency_key: key,
    platform: "bluetooth" as const,
    devices: [{ external_id: device.id.slice(0, 200), name: device.name.slice(0, 120) || profile.label, kind: profile.deviceKind, metrics: profile.metrics }],
    buckets: [...buckets.values()].map((b) => ({ metric: b.metric, start: b.start, value: Math.round((b.sum / b.n) * 10) / 10, samples: Math.min(b.n, 32767) })),
  };
}
