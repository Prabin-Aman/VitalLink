// Entry form parts — Figma "15 — Proposed · Entry forms" (page 195:2): Dialog 560, Form / Field (195:47),
// Form / Choice (195:52). Rules about values live in packages/api/src/forms.ts; these only bind inputs.
import type { Option } from "@vitallink/api";
import { useEffect, useId, useRef, useState, type FormEvent, type InputHTMLAttributes, type ReactNode } from "react";
import { notifyChanged, useApi } from "../data";
import { Icon, type IconName } from "./Icon";
import { Button } from "./ui";
import s from "./Form.module.css";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");

/** Thrown by a form's `save` to show a message instead of closing (validation or an API error). */
export class FormProblem extends Error {}

/**
 * A modal form: overline, title, subtitle, fields, then Cancel + the primary action. `save` sends the
 * request; when it resolves the dialog closes and every screen on view reloads. In mock mode nothing can
 * be saved, so after the checks pass the dialog says so instead of pretending.
 */
export function FormDialog({
  overline,
  title,
  subtitle,
  action,
  onClose,
  save,
  children,
}: {
  overline: string;
  title: string;
  subtitle: ReactNode;
  /** Omit (with `save`) for a dialog whose rows act on their own (Manage access): one Done button. */
  action?: string;
  onClose: () => void;
  save?: () => Promise<void>;
  children: ReactNode;
}) {
  const api = useApi();
  const ref = useRef<HTMLDialogElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [demoDone, setDemoDone] = useState(false);
  const titleId = useId();

  useEffect(() => {
    const d = ref.current!;
    d.showModal();
    return () => d.close();
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!save) return onClose();
    setBusy(true);
    setError(null);
    try {
      await save();
      if (api.isMock) setDemoDone(true);
      else {
        notifyChanged();
        onClose();
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <dialog
      ref={ref}
      className={s.dialog}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
    >
      <form className={s.form} onSubmit={submit} noValidate>
        <header className={s.head}>
          <span className={cx("vl-overline", s.overline)}>{overline}</span>
          <h2 id={titleId} className="vl-h3">
            {title}
          </h2>
          <p className={cx("vl-body-m", s.sub)}>{subtitle}</p>
        </header>
        <div className={s.fields}>{children}</div>
        {error && (
          <p role="alert" className={cx("vl-body-s", s.error)}>
            {error}
          </p>
        )}
        {demoDone && (
          <p role="status" className={cx("vl-body-s", s.done)}>
            Everything checks out, but this is demo data, so nothing was saved. Connect the portal to your account to save.
          </p>
        )}
        <div className={s.actions}>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            {!action ? "Done" : demoDone ? "Close" : "Cancel"}
          </Button>
          {action && (
            <Button type="submit" disabled={busy || demoDone}>
              {busy ? "Saving…" : action}
            </Button>
          )}
        </div>
      </form>
    </dialog>
  );
}

/** The account's time zone (dates and times in forms are in it), or null until /me has loaded. */
export function useZone(): string | null {
  const api = useApi();
  const [tz, setTz] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    api.me().then(
      (m) => live && setTz(m.timezone),
      () => live && setTz(Intl.DateTimeFormat().resolvedOptions().timeZone),
    );
    return () => {
      live = false;
    };
  }, [api]);
  return tz;
}

/** Runs a forms.ts check: returns the body, or stops the save with its message. */
export function must<T>(c: { ok: true; body: T } | { ok: false; error: string }): T {
  if (!c.ok) throw new FormProblem(c.error);
  return c.body;
}

function FieldShell({ label, helper, icon, children, htmlFor }: { label: string; helper?: ReactNode; icon?: IconName; children: ReactNode; htmlFor: string }) {
  return (
    <div className={s.field}>
      <label htmlFor={htmlFor} className="vl-label-l">
        {label}
      </label>
      <div className={s.input}>
        {icon && <Icon name={icon} color="iconSubtle" />}
        {children}
      </div>
      {helper && <span className={cx("vl-caption", s.helper)}>{helper}</span>}
    </div>
  );
}

type TextProps = Omit<InputHTMLAttributes<HTMLInputElement>, "onChange" | "value"> & {
  label: string;
  value: string;
  onChange: (v: string) => void;
  helper?: ReactNode;
  icon?: IconName;
};

/** Form / Field, Kind=Text or Icon. Date and time inputs open the browser's picker on click. */
export function TextField({ label, value, onChange, helper, icon, type = "text", ...rest }: TextProps) {
  const id = useId();
  const picker = type === "date" || type === "time";
  return (
    <FieldShell label={label} helper={helper} icon={icon} htmlFor={id}>
      <input
        id={id}
        className={cx("vl-body-m", s.control)}
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onClick={picker ? (e) => (e.currentTarget as HTMLInputElement & { showPicker?: () => void }).showPicker?.() : undefined}
        {...rest}
      />
    </FieldShell>
  );
}

/** Form / Field, Kind=Select (the chevron is Icon / chevron-right turned down; the set has no chevron-down). */
export function SelectField({ label, value, onChange, options, helper }: { label: string; value: string; onChange: (v: string) => void; options: Option[]; helper?: ReactNode }) {
  const id = useId();
  return (
    <FieldShell label={label} helper={helper} htmlFor={id}>
      <select id={id} className={cx("vl-body-m", s.control)} value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      <Icon name="chevron-right-16" className={s.chevron} color="iconSubtle" />
    </FieldShell>
  );
}

export function Row({ children }: { children: ReactNode }) {
  return <div className={s.row}>{children}</div>;
}

/** Form / Choice chips: one value (radio) or several (`multiple`, checkboxes). */
export function Choices({
  label,
  options,
  selected,
  onChange,
  multiple,
  extra,
  hideLabel,
}: {
  label: string;
  options: Option[];
  selected: string[];
  onChange: (next: string[]) => void;
  multiple?: boolean;
  extra?: ReactNode;
  /** Filter rows (Workouts › History) name the group for screen readers only. */
  hideLabel?: boolean;
}) {
  const id = useId();
  // Two quick clicks can land before a re-render; toggle against the newest selection, not the rendered one.
  const latest = useRef(selected);
  latest.current = selected;
  return (
    <div className={s.field} role="group" aria-labelledby={id}>
      <span id={id} className={hideLabel ? "sr-only" : "vl-label-l"}>
        {label}
      </span>
      <div className={s.choices} role={multiple ? undefined : "radiogroup"} aria-labelledby={id}>
        {options.map((o) => {
          const on = selected.includes(o.value);
          const toggle = () => {
            const cur = latest.current;
            const next = multiple ? (cur.includes(o.value) ? cur.filter((v) => v !== o.value) : [...cur, o.value]) : [o.value];
            latest.current = next;
            onChange(next);
          };
          return multiple ? (
            <button key={o.value} type="button" className={cx("vl-label-l", s.choice)} aria-pressed={on} onClick={toggle}>
              {o.label}
            </button>
          ) : (
            <button key={o.value} type="button" role="radio" className={cx("vl-label-l", s.choice)} aria-checked={on} onClick={toggle}>
              {o.label}
            </button>
          );
        })}
        {extra}
      </div>
    </div>
  );
}

export function Note({ children }: { children: ReactNode }) {
  return <p className={cx("vl-body-s", s.note)}>{children}</p>;
}

/** Upload Document drop zone: drag a file in, or Choose File. */
export function DropZone({ file, onFile, accept }: { file: File | null; onFile: (f: File) => void; accept: string }) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  return (
    <div
      className={cx(s.drop, over && s.dropOver)}
      onDragOver={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        const f = e.dataTransfer.files[0];
        if (f) onFile(f);
      }}
    >
      <Icon name="file-text-24" color="iconSubtle" />
      <span className="vl-body-m-strong">{file ? file.name : "Drop a PDF or image here"}</span>
      <span className={cx("vl-caption", s.helper)}>{file ? `${Math.max(1, Math.round(file.size / 1024)).toLocaleString("en-US")} KB` : "PDF, JPG or PNG · up to 25 MB"}</span>
      <Button variant="secondary" size="sm" onClick={() => input.current?.click()}>
        {file ? "Choose Another" : "Choose File"}
      </Button>
      <input
        ref={input}
        type="file"
        accept={accept}
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) onFile(f);
          e.target.value = "";
        }}
      />
    </div>
  );
}

/** Edit Emergency Info lists: rows with Remove, then an add action under the box. */
export function ItemList({ label, items, empty, add }: { label: string; items: { key: string; title: string; caption: string; onRemove: () => void; removeLabel?: string }[]; empty: string; add: ReactNode }) {
  const id = useId();
  return (
    <div className={s.field} role="group" aria-labelledby={id}>
      <span id={id} className="vl-label-l">
        {label}
      </span>
      <div className={s.items}>
        {items.length === 0 && <span className={cx("vl-body-s", s.empty)}>{empty}</span>}
        {items.map((it) => (
          <div key={it.key} className={s.item}>
            <span className={s.itemText}>
              <span className="vl-body-m-strong">{it.title}</span>
              <span className={cx("vl-caption", s.helper)}>{it.caption}</span>
            </span>
            <Button variant="tertiary" size="sm" onClick={it.onRemove} aria-label={`${it.removeLabel ?? "Remove"} ${it.title}`}>
              {it.removeLabel ?? "Remove"}
            </Button>
          </div>
        ))}
      </div>
      {add}
    </div>
  );
}

export { s as formStyles };
