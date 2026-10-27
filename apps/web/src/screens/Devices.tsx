// Connected Devices — Desktop 1440 (Figma 73:635).
import { loadDevices, type DeviceVM } from "@vitallink/api";
import { Icon, type IconName } from "../components/Icon";
import { PageHeader, RowButton, Txt } from "../components/kit";
import { Chip } from "../components/ui";
import { useApi, useReloadableScreen } from "../data";
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
  const disconnect = (id: string, name: string) => {
    if (!confirm(`Disconnect ${name}? Its past readings stay in your account.`)) return;
    api.disconnectDevice(id).then(reload, (e: Error) => alert(e.message));
  };
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <>
      <PageHeader title="Connected Devices" subtitle={v.subtitle} />
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
              <RowButton>Sync now</RowButton>
              <RowButton>Permissions</RowButton>
              <RowButton onClick={() => disconnect(d.id, d.name)}>Disconnect</RowButton>
            </div>
          </article>
        ))}
        <button type="button" className={s.add}>
          <Txt s="h5" c="textSecondary">+ Add a device</Txt>
          <Txt s="caption" c="textTertiary">Wearables, monitors and scales</Txt>
        </button>
      </div>
    </>
  );
}
