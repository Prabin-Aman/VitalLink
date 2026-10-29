// Account Settings — Desktop 1440 (Figma 74:760).
import { loadSettings } from "@vitallink/api";
import { useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { cx, NotDesigned, PageHeader, Panel, RowButton, Txt } from "../components/kit";
import { Avatar, Button } from "../components/ui";
import { useApi, useReloadableScreen } from "../data";
import { applyTheme, storedTheme, type Theme } from "../theme";
import { ScreenState } from "./ScreenState";
import { DataExportSection, DeleteAccountSection } from "./SettingsSections";
import s from "./Settings.module.css";

// Sections with their own page link there; Profile and Appearance live here.
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
  { label: "Language" },
  { label: "Data Export" },
  { label: "Delete Account", danger: true },
];

// Figma swatches: bg/canvas, bg/inverse and bg/muted, always in light mode.
const THEMES: { key: Theme; label: string; swatch: string }[] = [
  { key: "light", label: "Light", swatch: s.swLight },
  { key: "dark", label: "Dark", swatch: s.swDark },
  { key: "system", label: "System", swatch: s.swSystem },
];

export function Settings() {
  const api = useApi();
  const [state, reload] = useReloadableScreen(loadSettings);
  const [section, setSection] = useState("Profile");
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [theme, setTheme] = useState<Theme | null>(storedTheme);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const current = theme ?? v.theme;
  const dirty = Object.keys(edits).length > 0;

  const pickTheme = (t: Theme) => {
    setTheme(t);
    applyTheme(t);
    api.updateAccount({ theme: t }).catch(() => undefined); // the device keeps it even if saving fails
  };

  const save = () => {
    const patch = toPatch(edits);
    if (!patch) return;
    api.updateAccount(patch).then(
      () => {
        setEdits({});
        reload();
      },
      (e: Error) => window.alert(`Couldn't save: ${e.message}`),
    );
  };

  let body: ReactNode;
  if (section === "Profile" || section === "Appearance") {
    body = (
      <>
        <Panel title="Profile" gap={18}>
          <div className={s.avatarRow}>
            <Avatar initials={v.person.initials} size={72} fontSize={26} />
            <div className={s.grow}>
              <Txt s="h4">{v.person.name}</Txt>
              <Txt s="bodyS" c="textTertiary">{v.memberLine}</Txt>
            </div>
            <RowButton>Change photo</RowButton>
          </div>
          <form className={s.form} onSubmit={(e) => { e.preventDefault(); save(); }}>
            {v.fields.map((f) => (
              <label key={f.key} className={cx(s.field, f.wide && s.wide)}>
                <Txt s="labelL">{f.label}</Txt>
                <input
                  className={cx("vl-body-m", s.input)}
                  value={edits[f.key] ?? f.value}
                  onChange={(e) => setEdits({ ...edits, [f.key]: e.target.value })}
                />
                {f.help ? <Txt s="caption" c="textTertiary">{f.help}</Txt> : null}
              </label>
            ))}
            <div className={s.save}>
              <RowButton onClick={() => setEdits({})}>Discard</RowButton>
              <Button size="sm" type="submit" className={s.submit} disabled={!dirty}>
                Save Changes
              </Button>
            </div>
          </form>
        </Panel>
        <Panel title="Appearance" gap={14}>
          <div className={s.themes} role="radiogroup" aria-label="Theme">
            {THEMES.map((t) => (
              <button key={t.key} type="button" role="radio" aria-checked={current === t.key} className={cx(s.theme, current === t.key && s.themeOn)} onClick={() => pickTheme(t.key)}>
                <span className={cx("vl-light", s.swatch, t.swatch)} />
                <Txt s="labelL" c={current === t.key ? "textBrand" : "textSecondary"}>{t.label}</Txt>
              </button>
            ))}
          </div>
        </Panel>
      </>
    );
  } else if (section === "Data Export") {
    body = <DataExportSection />;
  } else if (section === "Delete Account") {
    body = <DeleteAccountSection />;
  } else {
    body = <NotDesigned what={section} />;
  }

  return (
    <>
      <PageHeader title="Settings" subtitle="Account, security, appearance and data controls." />
      <div className={s.split}>
        <nav className={s.nav} aria-label="Settings sections">
          {NAV.map((n) => {
            const on = n.label === section || (section === "Appearance" && n.label === "Appearance");
            const cls = cx(on ? "vl-body-m-strong" : "vl-body-m", s.item, on && s.itemOn, n.danger && s.danger);
            return n.to ? (
              <Link key={n.label} to={n.to} className={cls}>{n.label}</Link>
            ) : (
              <button key={n.label} type="button" className={cls} aria-current={on || undefined} onClick={() => setSection(n.label)}>
                {n.label}
              </button>
            );
          })}
        </nav>
        <div className={s.main}>{body}</div>
      </div>
    </>
  );
}

/**
 * Profile edits → PATCH /me/account. Name, phone and ZIP are saved; a blank or "—" clears them. A US
 * number typed any way ("(214) 555-0148") is sent as E.164. Email changes go through sign-in, not here;
 * height and language aren't editable as text.
 */
function toPatch(edits: Record<string, string>): Record<string, string | null> | null {
  const clean = (v: string) => (v.trim() === "" || v.trim() === "—" ? null : v.trim());
  const patch: Record<string, string | null> = {};
  if ("first" in edits) patch.first_name = clean(edits.first);
  if ("last" in edits) patch.last_name = clean(edits.last);
  if ("zip" in edits) patch.zip = clean(edits.zip);
  if ("phone" in edits) {
    const v = clean(edits.phone);
    const digits = v?.replace(/\D/g, "") ?? "";
    patch.phone = v === null ? null : v.startsWith("+") ? `+${digits}` : digits.length === 10 ? `+1${digits}` : `+${digits}`;
  }
  return Object.keys(patch).length ? patch : null;
}
