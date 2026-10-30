// Connected Devices — Desktop 1440 (Figma 73:635); phone sync panel (proposed 212:3117); Add a device and its
// dialogs live in forms/DeviceForms.tsx (213:1667, 212:3400, 213:1701, 213:1754).
import { importSummary, loadDevices, type DeviceVM, type ImportResult, type PhoneSyncVM } from "@vitallink/api";
import { useState } from "react";
import { FormDialog, KeyValueList, Note, useZone } from "../components/Form";
import { Icon, type IconName } from "../components/Icon";
import { PageHeader, RowButton, Txt } from "../components/kit";
import { Button, Chip } from "../components/ui";
import { useApi, useReloadableScreen } from "../data";
import { AddDeviceDialog, BluetoothDialog, ImportDataDialog, PhoneAppDialog, type DeviceDialog } from "../forms/DeviceForms";
import { ScreenState } from "./ScreenState";
import s from "./Devices.module.css";

export const DEVICE_ICON: Record<DeviceVM["icon"], IconName> = {
  devices: "devices-22",
  monitor: "monitor-22",
  droplet: "droplet-22",
  scale: "scale-22",
  health: "health-22",
};

export function Devices() {
  const api = useApi();
  const [state, reload] = useReloadableScreen(loadDevices);
  const [perms, setPerms] = useState<DeviceVM | null>(null);
  const [dialog, setDialog] = useState<DeviceDialog | null>(null);
  const [imported, setImported] = useState<ImportResult | null>(null);
  const tz = useZone();
  const [checked, setChecked] = useState(false);
  // The web can't reach the phone: "Sync now" fetches again and shows the newest upload the phone has sent.
  const check = () => {
    setChecked(true);
    reload();
  };
  const disconnect = (id: string, name: string) => {
    if (!confirm(`Disconnect ${name}? Its past readings stay in your account.`)) return;
    setPerms(null);
    api.disconnectDevice(id).then(reload, (e: Error) => alert(e.message));
  };
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <>
      <PageHeader title="Connected Devices" subtitle={v.subtitle} />
      {perms && (
        // Permissions (Figma 211:1647): what the device sends; VitalLink never writes to it.
        <FormDialog
          overline={`Connected Devices › ${perms.name}`}
          title="Permissions"
          subtitle="What this device sends to VitalLink."
          onClose={() => setPerms(null)}
          extra={
            <Button variant="secondary" onClick={() => disconnect(perms.id, perms.name)}>
              Disconnect
            </Button>
          }
        >
          <KeyValueList label={`${perms.name} data`} rows={perms.dataTypes.map((t) => ({ label: t, value: "Sent to VitalLink" }))} />
          <Note>
            VitalLink only receives data from your devices; it never writes to them. To stop one kind of data, turn it off in the device's app or your phone's health settings. Disconnecting stops everything; readings already synced stay.
          </Note>
        </FormDialog>
      )}
      {dialog === "add" && <AddDeviceDialog onClose={() => setDialog(null)} onPick={setDialog} />}
      {dialog === "phone" && <PhoneAppDialog onClose={() => setDialog(null)} />}
      {dialog === "import" && <ImportDataDialog onClose={() => setDialog(null)} onImported={setImported} />}
      {dialog === "bluetooth" && <BluetoothDialog onClose={() => setDialog(null)} />}
      {imported && tz && (
        <Note>
          {importSummary(imported, tz)}.{imported.problems.length > 0 && ` Skipped, for example: ${imported.problems.slice(0, 3).join("; ")}.`}
        </Note>
      )}
      {v.phones.map((p) => (
        <PhoneSync key={p.title} p={p} checked={checked} onCheck={check} />
      ))}
      <div className={s.grid}>
        {v.devices.map((d) => (
          <article key={d.id} className={s.card} aria-label={d.name}>
            <div className={s.top}>
              <span className={s.tile}>
                <Icon name={DEVICE_ICON[d.icon]} />
              </span>
              <div className={s.titles}>
                <Txt s="h5" as="h2">{d.name}</Txt>
                <Txt s="caption" c="textTertiary">{d.caption}</Txt>
              </div>
              <Chip chip={d.status} dot />
            </div>
            <Txt s="bodyS" c="textSecondary">{d.data}</Txt>
            <div className={s.actions}>
              <RowButton onClick={v.phones.length ? check : () => setDialog("phone")}>Sync now</RowButton>
              <RowButton onClick={() => setPerms(d)}>Permissions</RowButton>
              <RowButton onClick={() => disconnect(d.id, d.name)}>Disconnect</RowButton>
            </div>
          </article>
        ))}
        <button type="button" className={s.add} onClick={() => setDialog("add")}>
          <Txt s="h5" c="textSecondary">+ Add a device</Txt>
          <Txt s="caption" c="textTertiary">Wearables, monitors and scales</Txt>
        </button>
      </div>
    </>
  );
}

function PhoneSync({ p, checked, onCheck }: { p: PhoneSyncVM; checked: boolean; onCheck: () => void }) {
  return (
    <section className={s.card} aria-label={`Phone sync: ${p.title}`}>
      <div className={s.top}>
        <span className={s.tile}>
          <Icon name="health-22" />
        </span>
        <div className={s.titles}>
          <Txt s="h5" as="h2">{p.title}</Txt>
          <Txt s="caption" c="textTertiary">{p.caption}</Txt>
        </div>
        <Chip chip={p.status} dot />
      </div>
      <Txt s="bodyS" c="textSecondary">{p.summary}</Txt>
      {p.uploads.length > 0 && (
        <>
          <Txt s="labelL">Recent uploads</Txt>
          <KeyValueList label="Recent uploads" labelWidth={160} rows={p.uploads.map((u) => ({ label: u.when, value: u.detail }))} />
        </>
      )}
      <div className={s.phoneActions}>
        <RowButton onClick={onCheck}>Check again</RowButton>
        <span aria-live="polite">
          <Txt s="caption" c="textTertiary">
            {checked
              ? "Checked just now. Open VitalLink on your phone to upload anything newer."
              : "Your phone uploads whenever VitalLink is open on it, and in the background when the phone allows."}
          </Txt>
        </span>
      </div>
    </section>
  );
}
