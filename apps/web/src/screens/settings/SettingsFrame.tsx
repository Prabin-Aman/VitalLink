// The Settings page around its sections: the heading and the section menu (Figma 74:760). Privacy and Connected
// Devices are pages of their own that open inside it, so the menu stays on screen.
import type { ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { cx, PageHeader } from "../../components/kit";
import s from "./Settings.module.css";

// Items with `to` are pages; the rest are sections of the Settings screen itself.
const NAV: { label: string; to?: string; danger?: boolean }[] = [
  { label: "Profile" },
  { label: "Account" },
  { label: "Security" },
  { label: "Privacy", to: "/privacy" },
  { label: "Notifications", to: "/notifications" },
  { label: "Connected Devices", to: "/devices" },
  { label: "Family", to: "/family" },
  { label: "Emergency", to: "/emergency" },
  { label: "Appearance" },
  { label: "Data Export" },
  { label: "Delete Account", danger: true },
];

/** `onPick` switches section on the Settings screen; on any other page a section opens the Settings screen at it. */
export function SettingsFrame({ current, onPick, children }: { current: string; onPick?: (section: string) => void; children: ReactNode }) {
  const nav = useNavigate();
  return (
    <>
      <PageHeader title="Settings" subtitle="Account, security, appearance and data controls." />
      <div className={s.split}>
        <nav className={s.nav} aria-label="Settings sections">
          {NAV.map((n) => {
            const on = n.label === current;
            const cls = cx(on ? "vl-body-m-strong" : "vl-body-m", s.item, on && s.itemOn, n.danger && s.danger);
            return n.to ? (
              <Link key={n.label} to={n.to} className={cls} aria-current={on ? "page" : undefined}>
                {n.label}
              </Link>
            ) : (
              <button
                key={n.label}
                type="button"
                className={cls}
                aria-current={on || undefined}
                onClick={() => (onPick ? onPick(n.label) : nav("/settings", { state: { section: n.label } }))}
              >
                {n.label}
              </button>
            );
          })}
        </nav>
        <div className={s.main}>{children}</div>
      </div>
    </>
  );
}
