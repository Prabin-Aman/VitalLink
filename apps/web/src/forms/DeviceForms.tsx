// Connected Devices › Add (proposed, Figma 213:1667) and its two ways in: Import health data (213:1701) and
// Pair a Bluetooth device (213:1754).
import {
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
  type ImportResult,
} from "@vitallink/api";
import { useEffect, useRef, useState } from "react";
import { Choices, DropZone, FormDialog, FormProblem, ItemList, KeyValueList, must, Note, useZone } from "../components/Form";
import { Button } from "../components/ui";
import { useApi } from "../data";
import { saveFile } from "../download";

export type DeviceDialog = "add" | "import" | "bluetooth";

export function AddDeviceDialog({ onClose, onPick }: { onClose: () => void; onPick: (d: DeviceDialog) => void }) {
  return (
    <FormDialog overline="Connected Devices › Add" title="Add a device" subtitle="Choose how its readings reach VitalLink." onClose={onClose}>
      <ItemList
        label="Ways to connect"
        empty=""
        add={null}
        items={[
          { key: "import", title: "Apple Health or a file", caption: "An Apple Health export (export.zip) or a CSV of readings", onRemove: () => onPick("import"), removeLabel: "Import file" },
          { key: "bluetooth", title: "Bluetooth", caption: "Strap, cuff, scale, oximeter or glucose meter · Chrome or Edge", onRemove: () => onPick("bluetooth"), removeLabel: "Pair device" },
        ]}
      />
      <Note>VitalLink only reads from your devices; it never changes their settings.</Note>
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
