// Connected Devices › Add (proposed, Figma 213:1667) and its ways in: Import health data (213:1701), Pair a
// Bluetooth device (213:1754) and Google Health (Fitbit, Pixel Watch; no Figma frame, built from the same rows).
// Google sends the browser back to /devices/google/callback (screens/care/GoogleCallback.tsx).
import {
  ago,
  BLE_KINDS,
  bleIngest,
  bleProfile,
  bleSummary,
  checkImport,
  decodeBle,
  GLUCOSE_RACP,
  IMPORT_CSV_TEMPLATE,
  IMPORT_PERIODS,
  IMPORT_TYPES,
  PLX_CONTINUOUS,
  relativeDay,
  timeOfDay,
  type BleKind,
  type BleReading,
  type Connection,
  type ConnectionSync,
  type ImportResult,
  type Phone,
  type PhoneCode,
} from "@vitallink/api";
import { useEffect, useRef, useState } from "react";
import { Choices, DropZone, FormDialog, FormProblem, ItemList, KeyValueList, must, Note, useZone } from "../components/Form";
import { Txt } from "../components/kit";
import { QrCode } from "../components/QrCode";
import { Button } from "../components/ui";
import { notifyChanged, useApi } from "../data";
import { saveFile } from "../download";

export type DeviceDialog = "add" | "import" | "bluetooth" | "android" | "iphone";

export function AddDeviceDialog({ onClose, onPick }: { onClose: () => void; onPick: (d: DeviceDialog) => void }) {
  const api = useApi();
  const [google, setGoogle] = useState<Connection | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  useEffect(() => {
    api.connections().then((cs) => setGoogle(cs.find((c) => c.service === "google_health") ?? null), () => setGoogle(null));
  }, [api]);

  const connect = async () => {
    setBusy(true);
    try {
      const r = await api.startGoogleHealth();
      if (r) window.location.assign(r.url);
      else setResult("Connecting Google Health needs the live server; sample data can't connect.");
    } catch (e) {
      setResult((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  const sync = async () => {
    setBusy(true);
    try {
      const r = await api.syncGoogleHealth();
      if (r) {
        setResult(syncSummary(r));
        notifyChanged();
      }
    } catch (e) {
      setResult((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const googleCaption = !google
    ? "Checking…"
    : !google.available
      ? "Not set up on this server yet"
      : google.last_error
        ? google.last_error
        : google.connected && google.last_sync_at
          ? `Connected · last synced ${ago(google.last_sync_at, api.now())} · syncs every night`
          : "Steps, heart rate, sleep and weight, through your Google account";
  const googleAction = google?.available
    ? google.connected && !google.last_error
      ? { onRemove: busy ? undefined : sync, removeLabel: "Sync now" }
      : { onRemove: busy ? undefined : connect, removeLabel: google.connected ? "Connect again" : "Connect" }
    : {};

  return (
    <FormDialog overline="Connected Devices › Add" title="Add a device" subtitle="Choose how its readings reach VitalLink." onClose={onClose}>
      <ItemList
        label="Ways to connect"
        empty=""
        add={null}
        items={[
          { key: "google", title: "Fitbit or Pixel Watch", caption: googleCaption, ...googleAction },
          { key: "iphone", title: "iPhone (Apple Health)", caption: "Every night through an Apple Shortcut: steps, heart rate, sleep, weight and more. No app needed", onRemove: () => onPick("iphone"), removeLabel: "Set up" },
          { key: "android", title: "Android phone", caption: "Health Connect: Samsung Health, Google Fit and other Android apps, through the VitalLink Sync app", onRemove: () => onPick("android"), removeLabel: "Link phone" },
          { key: "import", title: "Apple Health or a file", caption: "An Apple Health export (export.zip) or a CSV of readings", onRemove: () => onPick("import"), removeLabel: "Import file" },
          { key: "bluetooth", title: "Bluetooth", caption: "Strap, cuff, scale, oximeter or glucose meter · Chrome or Edge", onRemove: () => onPick("bluetooth"), removeLabel: "Pair device" },
        ]}
      />
      {result && <Note>{result}</Note>}
      <Note>VitalLink only reads from your devices; it never changes their settings. Google Health access is read-only, and you can stop it any time with Disconnect.</Note>
    </FormDialog>
  );
}

/** "Added 1,234 readings. Not read: …" for a Google Health sync. */
export function syncSummary(r: ConnectionSync): string {
  const added = r.inserted + r.updated;
  const head = added === 0 ? "Up to date: no new readings" : `Added ${added.toLocaleString("en-US")} readings`;
  return r.problems.length ? `${head}. Not read: ${r.problems.slice(0, 3).join("; ")}.` : `${head}.`;
}

// ---------------------------------------------------------------- Android phone (Health Connect)

/** The APK the Android CI publishes on every change (.github/workflows/android.yml); VITE_ANDROID_APK_URL overrides it. */
export const ANDROID_APK_URL: string =
  import.meta.env.VITE_ANDROID_APK_URL || "https://github.com/zeutonx/VitalLink/releases/download/android-latest/vitallink-sync.apk";

/** The lines the Shortcut builds: one "Find Health Samples" + "Repeat with Each" per row (Health type, our code). */
export const SHORTCUT_TYPES: [string, string][] = [
  ["Steps", "steps"],
  ["Walking + Running Distance", "distance"],
  ["Active Energy", "active_energy"],
  ["Flights Climbed", "flights_climbed"],
  ["Heart Rate", "heart_rate"],
  ["Resting Heart Rate", "resting_heart_rate"],
  ["Heart Rate Variability", "hrv"],
  ["Blood Oxygen", "oxygen_saturation"],
  ["Respiratory Rate", "respiratory_rate"],
  ["Blood Pressure Systolic", "bp_systolic"],
  ["Blood Pressure Diastolic", "bp_diastolic"],
  ["Blood Glucose", "glucose"],
  ["Weight", "weight"],
  ["Body Fat Percentage", "body_fat"],
  ["Sleep", "sleep"],
];

/** An Apple Shortcut sends Apple Health data every night (no Figma frame; there's no web API for HealthKit). */
export function IphoneDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const [token, setToken] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const url = api.shortcutUploadUrl();
  const copy = (text: string) => navigator.clipboard?.writeText(text).catch(() => undefined);
  const make = () => {
    setProblem(null);
    api.shortcutToken().then(
      (r) => {
        if (r) {
          setToken(r.token);
          notifyChanged();
        } else setProblem("Setting up an iPhone needs the live server; sample data can't link.");
      },
      (e: Error) => setProblem(e.message),
    );
  };
  return (
    <FormDialog overline="Connected Devices › iPhone" title="Send Apple Health from your iPhone" subtitle="A Shortcut on the iPhone sends the last 2 days of readings every night. Set it up once; it runs on its own." onClose={onClose}>
      <span>
        <Button variant={token ? "secondary" : "primary"} onClick={make}>{token ? "Make a new key" : "1. Make a key for the Shortcut"}</Button>
      </span>
      {problem && <Note>{problem}</Note>}
      {token && (
        <KeyValueList
          label="For the Shortcut"
          labelWidth={110}
          rows={[
            { label: "Key", value: <span style={{ wordBreak: "break-all" }}>Phone {token} <Button variant="tertiary" size="sm" onClick={() => copy(`Phone ${token}`)}>Copy</Button></span> },
            { label: "Address", value: <span style={{ wordBreak: "break-all" }}>{url} <Button variant="tertiary" size="sm" onClick={() => copy(url)}>Copy</Button></span> },
          ]}
        />
      )}
      {token && (
        <span>
          <QrCode value={`Phone ${token}`} label="Scan with the iPhone camera, then tap Copy" />
        </span>
      )}
      {token && <Note>The key is shown only now. It can only send readings; remove the iPhone in Connected Devices to stop it.</Note>}
      <Note>
        2. On the iPhone, open Shortcuts, tap +, and name it VitalLink. Add a Text action with: start|metric|value|end|unit|source, and Set Variable "Lines" to it.
      </Note>
      <Note>
        3. For each type below: add Find Health Samples (Type = the Health type, Start Date is in the last 2 days), then Repeat with Each. Inside the repeat, add a Text action with: Start Date|code|Value|End Date|Unit|Source (tap Start Date and End Date and choose ISO 8601 with time; use the code from the list), then Add to Variable "Lines".
      </Note>
      <KeyValueList label="Health types and codes" labelWidth={210} rows={SHORTCUT_TYPES.map(([type, code]) => ({ label: type, value: code }))} />
      <Note>
        4. After the repeats, add Combine Text (Lines, with New Lines), then Get Contents of URL: the address above, Method POST, Header Authorization = the key above, Header Content-Type = text/plain, Request Body File = Combined Text. Run it once and allow Health access.
      </Note>
      <Note>
        5. In Shortcuts › Automation, tap +, choose Time of Day (for example 9:00 PM, Daily), choose Run Immediately, and pick VitalLink. Sleep and readings from the last 36 hours are kept each night; for older history, use Import with an Apple Health export.
      </Note>
    </FormDialog>
  );
}

/** Shows a one-time code for the VitalLink Sync app and the phones already linked (no Figma frame). */
export function AndroidPhoneDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const tz = useZone();
  const [code, setCode] = useState<PhoneCode | null>(null);
  const [phones, setPhones] = useState<Phone[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const reload = () => api.phones().then(setPhones, () => setPhones([]));
  useEffect(() => {
    reload();
  }, [api]);
  const newCode = () => {
    setProblem(null);
    api.phoneCode().then(
      (c) => (c ? setCode(c) : setProblem("Linking a phone needs the live server; sample data can't link.")),
      (e: Error) => setProblem(e.message),
    );
  };
  const remove = (p: Phone) => {
    if (!confirm(`Remove ${p.name}? It stops sending readings; readings already sent stay.`)) return;
    api.removePhone(p.id).then(() => {
      reload();
      notifyChanged();
    }, (e: Error) => setProblem(e.message));
  };
  return (
    <FormDialog overline="Connected Devices › Android phone" title="Link an Android phone" subtitle="Send Health Connect data from your phone with the VitalLink Sync app." onClose={onClose}>
      <Note>
        1. On the phone, download VitalLink Sync and install it (allow installing from your browser when Android asks). 2. Open it and type the code below. 3. Tap Allow access and choose what to share. It then syncs about every 3 hours. Android 8 or later; Health Connect comes with Android 14 and later, and is a free app from Google Play before that.
      </Note>
      <span>
        <Button variant="secondary" onClick={() => window.open(ANDROID_APK_URL, "_blank", "noopener")}>Download the app (APK)</Button>
      </span>
      <Txt s="caption" c="textTertiary">{ANDROID_APK_URL}</Txt>
      {code && tz ? (
        <KeyValueList label="Link code" rows={[{ label: "Code", value: <Txt s="h5">{code.code}</Txt> }, { label: "Works until", value: timeOfDay(code.expires_at, tz) }]} />
      ) : null}
      <span>
        <Button variant={code ? "secondary" : "primary"} onClick={newCode}>{code ? "Get a new code" : "Get a code"}</Button>
      </span>
      {problem && <Note>{problem}</Note>}
      <ItemList
        label="Linked phones"
        empty="No phones linked yet."
        add={null}
        items={phones.map((p) => ({
          key: p.id,
          title: p.name,
          caption: p.last_upload_at ? `Last sent ${ago(p.last_upload_at, api.now())}` : "Linked, nothing sent yet",
          onRemove: () => remove(p),
        }))}
      />
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Import health data

/** `onImported` gets the API's counts (null in mock mode) so the page can say what was added. */
export function ImportDataDialog({ onClose, onImported }: { onClose: () => void; onImported: (r: ImportResult) => void }) {
  const api = useApi();
  const [file, setFile] = useState<File | null>(null);
  const [days, setDays] = useState("90");
  return (
    <FormDialog
      overline="Connected Devices › Import"
      title="Import health data"
      subtitle="Add readings from a file: an Apple Health export or a CSV."
      action="Import"
      onClose={onClose}
      save={async () => {
        must(checkImport(file));
        const r = await api.importHealthFile(file!, Number(days));
        if (r) onImported(r);
      }}
    >
      <DropZone file={file} onFile={setFile} accept={IMPORT_TYPES} prompt="Drop export.zip, export.xml or a .csv here" hint="Up to 300 MB" />
      <Choices label="Period" options={IMPORT_PERIODS} selected={[days]} onChange={([d]) => setDays(d)} />
      <Note>On iPhone: Health app › your picture › Export All Health Data. It can take a few minutes. Save export.zip to Files, then choose it here, on the iPhone or on a computer you sent it to. Import again whenever you want newer data: readings update rather than double.</Note>
      <span>
        <Button variant="tertiary" size="sm" onClick={() => saveFile(new Blob(["﻿" + IMPORT_CSV_TEMPLATE], { type: "text/csv" }), "vitallink-readings-template.csv")}>
          Download the CSV template
        </Button>
      </span>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Pair a Bluetooth device

// The parts of Web Bluetooth used here (TypeScript's DOM library doesn't include it).
type Characteristic = EventTarget & {
  value?: DataView;
  startNotifications(): Promise<Characteristic>;
  writeValue(v: BufferSource): Promise<void>;
};
type Service = { getCharacteristic(uuid: number): Promise<Characteristic> };
type Gatt = { connected: boolean; connect(): Promise<Gatt>; disconnect(): void; getPrimaryService(uuid: number): Promise<Service> };
type BleDevice = EventTarget & { id: string; name?: string; gatt?: Gatt };
type Bluetooth = { requestDevice(o: { filters: { services: number[] }[] }): Promise<BleDevice> };
const bluetooth = () => (navigator as Navigator & { bluetooth?: Bluetooth }).bluetooth;

type Link = { device: BleDevice; state: "connecting" | "connected" | "disconnected" };

export function BluetoothDialog({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <Bluetooth_ tz={tz} onClose={onClose} /> : null;
}

function Bluetooth_({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const [kind, setKind] = useState<BleKind>("blood_pressure");
  const [link, setLink] = useState<Link | null>(null);
  const [readings, setReadings] = useState<BleReading[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const device = useRef<BleDevice | null>(null);
  const supported = !!bluetooth();
  const profile = bleProfile(kind);

  // Disconnect when the dialog closes.
  useEffect(() => () => device.current?.gatt?.disconnect(), []);

  const pair = async () => {
    setProblem(null);
    setReadings([]);
    device.current?.gatt?.disconnect();
    let d: BleDevice;
    try {
      d = await bluetooth()!.requestDevice({ filters: [{ services: [profile.service] }] });
    } catch (e) {
      // The user closed the chooser, or no device nearby offers this profile.
      if ((e as Error).name !== "NotFoundError") setProblem((e as Error).message);
      return;
    }
    device.current = d;
    setLink({ device: d, state: "connecting" });
    d.addEventListener("gattserverdisconnected", () => setLink((l) => (l && l.device === d ? { ...l, state: "disconnected" } : l)));
    try {
      const gatt = await d.gatt!.connect();
      const service = await gatt.getPrimaryService(profile.service);
      let ch: Characteristic;
      try {
        ch = await service.getCharacteristic(profile.characteristic);
      } catch (e) {
        if (kind !== "pulse_oximeter") throw e;
        ch = await service.getCharacteristic(PLX_CONTINUOUS); // oximeters that only stream
      }
      ch.addEventListener("characteristicvaluechanged", () => {
        if (!ch.value) return;
        const r = decodeBle(kind, ch.value, new Date().toISOString(), new Date().getTimezoneOffset());
        if (r) setReadings((all) => [...all.slice(-5000), r]);
      });
      await ch.startNotifications();
      if (kind === "glucose") {
        // Glucose meters keep their readings until asked: "report all stored records".
        const racp = await service.getCharacteristic(GLUCOSE_RACP);
        await racp.startNotifications();
        await racp.writeValue(new Uint8Array([0x01, 0x01]));
      }
      setLink({ device: d, state: "connected" });
    } catch (e) {
      setLink({ device: d, state: "disconnected" });
      setProblem(`Couldn't read from ${d.name || "the device"}: ${(e as Error).message}`);
    }
  };

  const last = readings[readings.length - 1];
  const now = new Date().toISOString();
  const n = readings.length;
  const rows = link
    ? [
        { label: "Device", value: `${link.device.name || profile.label} · ${link.state}` },
        { label: profile.streaming ? "Heart rate" : "Last reading", value: last ? bleSummary(last) : profile.streaming ? "Waiting for the strap…" : "Take a reading on the device" },
        ...(last ? [{ label: "Taken", value: `${relativeDay(last.at, now, tz)} · ${timeOfDay(last.at, tz)}` }] : []),
        { label: "To save", value: profile.streaming && n ? `${n} beats-per-minute samples` : `${n} reading${n === 1 ? "" : "s"}` },
      ]
    : [];

  return (
    <FormDialog
      overline="Connected Devices › Bluetooth"
      title="Pair a Bluetooth device"
      subtitle="Readings go from the device to VitalLink through this browser."
      action={n === 0 ? "Save" : profile.streaming ? "Save session" : n === 1 ? "Save 1 reading" : `Save ${n} readings`}
      onClose={onClose}
      save={async () => {
        if (!link || n === 0) throw new FormProblem(link ? "No readings yet. Take one on the device first." : "Pair a device first.");
        const key = crypto.randomUUID();
        await api.ingest(bleIngest(readings, { id: link.device.id, name: link.device.name ?? "" }, profile, key));
        device.current?.gatt?.disconnect();
      }}
    >
      <Choices
        label="Device"
        options={BLE_KINDS}
        selected={[kind]}
        onChange={([k]) => {
          device.current?.gatt?.disconnect();
          setLink(null);
          setReadings([]);
          setKind(k as BleKind);
        }}
      />
      {rows.length > 0 && <KeyValueList label="Bluetooth device" rows={rows} />}
      {problem && <Note>{problem}</Note>}
      {supported ? (
        <span>
          <Button variant="secondary" onClick={pair}>
            {link ? "Pair another" : `Pair ${profile.label.toLowerCase()}`}
          </Button>
        </span>
      ) : (
        <Note>This browser can't use Bluetooth. Open the portal in Chrome or Edge on a computer or Android phone.</Note>
      )}
      <Note>
        Works in Chrome and Edge on a computer or Android phone, with devices that use the standard Bluetooth health profiles. Take a reading on the device; nothing is saved until you press Save.
      </Note>
    </FormDialog>
  );
}
