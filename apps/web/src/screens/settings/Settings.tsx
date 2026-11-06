// Account Settings — Desktop 1440 (Figma 74:760).
import { checkPhoto, loadSettings, parseHeight, PHOTO_TYPES } from "@vitallink/api";
import { useRef, useState, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { cx, Panel, RowButton, Txt } from "../../components/kit";
import { Avatar, Button } from "../../components/ui";
import { notifyChanged, useApi, useReloadableScreen } from "../../data";
import { applyTheme, storedTheme, type Theme } from "../../theme";
import { ScreenState } from "../ScreenState";
import { SettingsFrame } from "./SettingsFrame";
import { AccountSection, DataExportSection, DeleteAccountSection, SecuritySection } from "./SettingsSections";
import s from "./Settings.module.css";

// Figma swatches: bg/canvas, bg/inverse and bg/muted, always in light mode.
const THEMES: { key: Theme; label: string; swatch: string }[] = [
  { key: "light", label: "Light", swatch: s.swLight },
  { key: "dark", label: "Dark", swatch: s.swDark },
  { key: "system", label: "System", swatch: s.swSystem },
];

export function Settings() {
  const api = useApi();
  const [state] = useReloadableScreen(loadSettings);
  // Opened from Privacy or Connected Devices, whose menu says which section.
  const [section, setSection] = useState<string>((useLocation().state as { section?: string } | null)?.section ?? "Profile");
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [theme, setTheme] = useState<Theme | null>(storedTheme);
  const [photoBusy, setPhotoBusy] = useState(false);
  const picker = useRef<HTMLInputElement>(null);
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
    const patch = toPatch(edits, v.units);
    if (typeof patch === "string") return window.alert(patch);
    if (!patch) return;
    if (api.isMock) return window.alert("This is demo data, so nothing was saved. Run the portal with the API to save.");
    api.updateAccount(patch).then(
      () => {
        setEdits({});
        notifyChanged(); // the top bar shows the new name too
      },
      (e: Error) => window.alert(`Couldn't save: ${e.message}`),
    );
  };

  // Change photo: JPEG, PNG or WebP up to 2 MB, uploaded to storage and checked by the API.
  const uploadPhoto = (file: File) => {
    const c = checkPhoto(file);
    if (!c.ok) return window.alert(c.error);
    if (api.isMock) return window.alert("This is demo data, so the photo wasn't saved. Run the portal with the API to save.");
    setPhotoBusy(true);
    api
      .uploadAvatar(file)
      .then(
        () => notifyChanged(),
        (e: Error) => window.alert(`Couldn't change the photo: ${e.message}`),
      )
      .finally(() => setPhotoBusy(false));
  };
  const removePhoto = () => {
    if (!confirm("Remove your profile photo? Your initials show instead.")) return;
    api.removeAvatar().then(
      () => notifyChanged(),
      (e: Error) => window.alert(e.message),
    );
  };

  let body: ReactNode;
  if (section === "Profile" || section === "Appearance") {
    body = (
      <>
        <Panel title="Profile" gap={18}>
          <div className={s.avatarRow}>
            <Avatar initials={v.person.initials} photo={v.photo} size={72} fontSize={26} />
            <div className={s.grow}>
              <Txt s="h4">{v.person.name}</Txt>
              <Txt s="bodyS" c="textTertiary">{v.memberLine}</Txt>
            </div>
            <span className={s.photoActions}>
              {v.photo && <RowButton onClick={removePhoto}>Remove</RowButton>}
              <RowButton onClick={() => picker.current?.click()}>{photoBusy ? "Uploading…" : "Change photo"}</RowButton>
            </span>
            <input
              ref={picker}
              type="file"
              accept={PHOTO_TYPES.join(",")}
              hidden
              onChange={(e) => {
                const f = e.target.files?.[0];
                if (f) uploadPhoto(f);
                e.target.value = "";
              }}
            />
          </div>
          <form className={s.form} onSubmit={(e) => { e.preventDefault(); save(); }}>
            {v.fields.map((f) => (
              <label key={f.key} className={cx(s.field, f.wide && s.wide)}>
                <Txt s="labelL">{f.label}</Txt>
                {f.kind === "select" ? (
                  <select className={cx("vl-body-m", s.input)} value={edits[f.key] ?? f.value} onChange={(e) => setEdits({ ...edits, [f.key]: e.target.value })}>
                    {f.options!.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    className={cx("vl-body-m", s.input)}
                    value={edits[f.key] ?? f.value}
                    readOnly={f.kind === "readonly"}
                    onChange={(e) => setEdits({ ...edits, [f.key]: e.target.value })}
                  />
                )}
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
  } else if (section === "Account") {
    body = <AccountSection email={v.email} memberDate={v.memberDate} />;
  } else if (section === "Security") {
    body = <SecuritySection />;
  } else if (section === "Data Export") {
    body = <DataExportSection />;
  } else if (section === "Delete Account") {
    body = <DeleteAccountSection />;
  }

  return (
    <SettingsFrame current={section} onPick={setSection}>
      {body}
    </SettingsFrame>
  );
}

/**
 * Profile edits → PATCH /me/account. Name, phone and ZIP are saved; a blank or "N/A" clears them. A US
 * number typed any way ("(214) 555-0148") is sent as E.164. Email changes go through sign-in, not here;
 * height is read from ft/in or cm.
 */
/** The edited fields as a PATCH body, null when nothing changed, or a message when a value can't be saved. */
function toPatch(edits: Record<string, string>, units: "metric" | "imperial"): Record<string, string | number | null> | null | string {
  const clean = (v: string) => (v.trim() === "" || v.trim() === "N/A" ? null : v.trim());
  const patch: Record<string, string | number | null> = {};
  if ("units" in edits) patch.units = edits.units;
  if ("height" in edits) {
    const v = clean(edits.height);
    const cm = v === null ? null : parseHeight(v, units);
    if (v !== null && cm === null) return "Enter a height like 5 ft 10 in or 178 cm.";
    patch.height_cm = cm;
  }
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
