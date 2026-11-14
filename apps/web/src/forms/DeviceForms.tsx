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
import { Choices, DropZone, FormDialog, FormProblem, Help, ItemList, KeyValueList, must, Note, Step, Steps, SubSteps, useZone } from "../components/Form";
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
      <Help
        title="Which one should I choose?"
        items={[
          { q: "Fitbit or Pixel Watch", a: "Choose Fitbit or Pixel Watch. You sign in with Google once and readings arrive every night." },
          { q: "Apple Watch or iPhone", a: "Choose iPhone (Apple Health) for nightly updates. To bring in your past history as well, also import an Apple Health export." },
          { q: "Samsung, Garmin, Oura and other Android apps", a: "If the app writes to Health Connect on your Android phone, choose Android phone." },
          { q: "A cuff, scale, oximeter or glucose meter", a: "Choose Bluetooth and take a reading with the device next to this computer or phone. No Bluetooth? Type the reading in with Add Reading on My Health." },
          { q: "Anything else", a: "If its app can save a CSV file, choose Apple Health or a file and use the CSV template there." },
        ]}
      />
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

/** A built VitalLink Shortcut to install (an iCloud link), when the deployment has one; else it's built by hand. */
export const IPHONE_SHORTCUT_URL: string = import.meta.env.VITE_IPHONE_SHORTCUT_URL || "";

/** What the Shortcut can send: the Health type to pick in "Find Health Samples", and VitalLink's code for it. */
export const SHORTCUT_TYPES: [string, string][] = [
  ["Steps", "steps"],
  ["Heart Rate", "heart_rate"],
  ["Sleep", "sleep"],
  ["Weight", "weight"],
  ["Walking + Running Distance", "distance"],
  ["Active Energy", "active_energy"],
  ["Flights Climbed", "flights_climbed"],
  ["Resting Heart Rate", "resting_heart_rate"],
  ["Heart Rate Variability", "hrv"],
  ["Blood Oxygen", "oxygen_saturation"],
  ["Respiratory Rate", "respiratory_rate"],
  ["Blood Pressure Systolic", "bp_systolic"],
  ["Blood Pressure Diastolic", "bp_diastolic"],
  ["Blood Glucose", "glucose"],
  ["Body Fat Percentage", "body_fat"],
];

/** Copies text and says so for a moment. */
function CopyButton({ text, label = "Copy" }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () =>
    navigator.clipboard?.writeText(text).then(
      () => {
        setCopied(true);
        window.setTimeout(() => setCopied(false), 2000);
      },
      () => undefined,
    );
  return (
    <Button variant="tertiary" size="sm" onClick={copy}>
      {copied ? "Copied" : label}
    </Button>
  );
}

/**
 * An Apple Shortcut sends Apple Health data every night (no Figma frame; there's no web API for HealthKit).
 * Three steps: make a key, add the Shortcut on the iPhone, set it to run nightly. The long part (building the
 * Shortcut by hand) stays folded away, and isn't needed at all when IPHONE_SHORTCUT_URL has one to install.
 */
export function IphoneDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const [token, setToken] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const url = api.shortcutUploadUrl();
  const key = token ? `Phone ${token}` : "";
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
    <FormDialog
      overline="Connected Devices › iPhone"
      title="Send Apple Health from your iPhone"
      subtitle="Your iPhone sends its Apple Health readings to VitalLink every night. Set it up once, in three steps."
      onClose={onClose}
    >
      <Steps>
        <Step title="Make your key" done={!!token}>
          <span className="vl-body-s">The key lets your iPhone send readings to your account. It can't read anything.</span>
          {!token && <Button onClick={make}>Make a key</Button>}
          {problem && <Note>{problem}</Note>}
          {token && (
            <>
              <QrCode value={key} label="Scan with the iPhone camera to copy the key" />
              <KeyValueList
                label="For the Shortcut"
                labelWidth={72}
                rows={[
                  { label: "Key", value: <>{key} <CopyButton text={key} /></> },
                  { label: "Address", value: <>{url} <CopyButton text={url} /></> },
                ]}
              />
              <span className="vl-caption">The key is shown only now. Keep this window open until the Shortcut has it.</span>
            </>
          )}
        </Step>
        {IPHONE_SHORTCUT_URL ? (
          <Step title="Add the VitalLink Shortcut on your iPhone">
            <span className="vl-body-s">Scan this with the iPhone camera and tap Add Shortcut. When it asks for the key, paste the one from step 1.</span>
            <QrCode value={IPHONE_SHORTCUT_URL} label="Scan to get the VitalLink Shortcut" />
            <CopyButton text={IPHONE_SHORTCUT_URL} label="Copy the link" />
          </Step>
        ) : (
          <Step title="Build the VitalLink Shortcut on your iPhone">
            <span className="vl-body-s">A one-time job in the Shortcuts app, about 10 minutes. Start with one or two kinds of data; you can add more later.</span>
            <Help title="Show me how to build it">
              <SubSteps
                items={[
                  <>Open Shortcuts, tap +, and name the Shortcut VitalLink.</>,
                  <>Add a Text action containing <strong>start|metric|value|end|unit|source</strong>, then Set Variable, named <strong>Lines</strong>.</>,
                  <>Add Find Health Samples. Set Type to a Health type from the list below, and add the filter Start Date is in the last 2 days.</>,
                  <>Add Repeat with Each. Inside it, add a Text action: <strong>Start Date|code|Value|End Date|Unit|Source</strong>. Pick each part from the Repeat Item, type the code from the list in place of "code", and set both dates to ISO 8601 with time. Then Add to Variable, Lines.</>,
                  <>Repeat the last two steps for every kind of data you want to send.</>,
                  <>At the end, add Combine Text (Lines, with New Lines), then Get Contents of URL: the Address from step 1, Method POST, Headers <strong>Authorization</strong> = the Key and <strong>Content-Type</strong> = text/plain, Request Body = File, the Combined Text.</>,
                  <>Tap play once and allow Health access when the iPhone asks.</>,
                ]}
              />
              <KeyValueList label="Health types and codes" labelWidth={210} rows={SHORTCUT_TYPES.map(([type, code]) => ({ label: type, value: code }))} />
            </Help>
          </Step>
        )}
        <Step title="Make it run every night">
          <SubSteps
            items={[
              <>In Shortcuts, open Automation and tap +.</>,
              <>Choose Time of Day, pick a time such as 9:00 PM, Daily.</>,
              <>Choose Run Immediately, then pick VitalLink.</>,
            ]}
          />
        </Step>
      </Steps>
      <Help
        items={[
          { q: "How do I know it worked?", a: "Run the Shortcut once on the iPhone. An iPhone (Shortcut) card appears in Connected Devices and says when it last sent readings." },
          { q: "The Shortcut says the phone isn't linked", a: "The key is wrong or was removed. The Authorization header must be the whole key, starting with the word Phone. If you lost it, remove the iPhone in Connected Devices and make a new key here." },
          { q: "Some readings are missing", a: "Each night sends the last 2 days and keeps readings from the last 36 hours, so a skipped night is filled in by the next one. Check that the Shortcut has a block for that kind of data and that Health access is allowed for it." },
          { q: "Can I bring in older history?", a: "Yes. Use Add a device, then Apple Health or a file, with an export from the Health app. Readings update rather than double." },
          { q: "How do I stop it?", a: "Disconnect the iPhone (Shortcut) card in Connected Devices. Its key stops working at once; readings already sent stay." },
        ]}
      />
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
      <Steps>
        <Step title="Install VitalLink Sync on the phone">
          <span className="vl-body-s">Open this page on the phone and download the app. Allow installing from your browser when Android asks.</span>
          <Button variant="secondary" onClick={() => window.open(ANDROID_APK_URL, "_blank", "noopener")}>Download the app (APK)</Button>
          <span className="vl-caption">{ANDROID_APK_URL}</span>
        </Step>
        <Step title="Type a code into the app">
          {code && tz ? (
            <KeyValueList label="Link code" rows={[{ label: "Code", value: <Txt s="h5">{code.code}</Txt> }, { label: "Works until", value: timeOfDay(code.expires_at, tz) }]} />
          ) : null}
          <Button variant={code ? "secondary" : "primary"} onClick={newCode}>{code ? "Get a new code" : "Get a code"}</Button>
          {problem && <Note>{problem}</Note>}
        </Step>
        <Step title="Tap Allow access in the app">
          <span className="vl-body-s">Choose what to share. The app then sends new readings about every 3 hours.</span>
        </Step>
      </Steps>
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
      <Help
        items={[
          { q: "Which phones work?", a: "Android 8 or later. Health Connect is built into Android 14 and later; on older phones it is a free app from Google Play, and VitalLink Sync offers to open it." },
          { q: "The app says the code isn't right", a: "A code works once and for 10 minutes. Get a new code here and type it again; dashes and capitals don't matter." },
          { q: "Nothing is arriving", a: "Open the app and tap Allow access, then Sync now. If your watch or fitness app isn't writing to Health Connect, turn that on in that app's settings first." },
          { q: "It only syncs when I open the app", a: "Allow background reading when Android asks (Allow access shows it again), and take the app out of battery saving." },
          { q: "How do I stop a phone?", a: "Remove it in the list above, or disconnect its card in Connected Devices. Readings already sent stay." },
        ]}
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
      <Note>Import again whenever you want newer data: readings update rather than double.</Note>
      <Help
        title="How do I get the file?"
        items={[
          { q: "From an iPhone", a: "Open the Health app, tap your picture, then Export All Health Data. It can take a few minutes. Save export.zip to Files, then choose it here, on the iPhone or on a computer you sent it to." },
          { q: "From another app or a spreadsheet", a: "Save a CSV with the columns start, metric, value. Download the template below to see the format and the metric names." },
          { q: "Some readings were skipped", a: "After an import the page says what was left out and why, for example a kind of data VitalLink doesn't track or a value outside the range a person can have." },
          { q: "The file is too big", a: "Files can be up to 300 MB. The Period here only chooses which readings are kept, so a larger export has to be trimmed before it is sent." },
        ]}
      />
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
      let continuous = false;
      try {
        ch = await service.getCharacteristic(profile.characteristic);
      } catch (e) {
        if (kind !== "pulse_oximeter") throw e;
        ch = await service.getCharacteristic(PLX_CONTINUOUS); // oximeters that only stream
        continuous = true;
      }
      ch.addEventListener("characteristicvaluechanged", () => {
        if (!ch.value) return;
        const r = decodeBle(kind, ch.value, new Date().toISOString(), new Date().getTimezoneOffset(), continuous);
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
      <Help
        items={[
          { q: "My device isn't in the list", a: "Wake it up first (step on the scale, press the cuff's button) and keep it within a few steps. If it is paired with a phone app, close that app or turn the phone's Bluetooth off for a moment: most devices talk to one thing at a time." },
          { q: "It paired but shows no reading", a: "Take a new reading while this window is open. Glucose meters send their stored readings as soon as they connect." },
          { q: "It still doesn't work", a: "Some devices only talk to their own app. Type the reading in with Add Reading on My Health, or connect the maker's app through Android phone or iPhone instead." },
        ]}
      />
    </FormDialog>
  );
}
