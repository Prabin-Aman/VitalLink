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
  stayOpen,
  busyLabel = "Saving…",
  extra,
  children,
}: {
  overline: string;
  title: string;
  subtitle: ReactNode;
  /** Omit (with `save`) for a dialog whose rows act on their own (Manage access): one Done button. */
  action?: string;
  onClose: () => void;
  save?: () => Promise<void>;
  /** `save` only reads (Search doctors): the dialog stays open to show what it found. */
  stayOpen?: boolean;
  busyLabel?: string;
  /** Buttons before the main one (Permissions' Disconnect); with no `action`, Done becomes primary. */
  extra?: ReactNode;
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
    // A dialog opened from this one (in a portal) submits its own form; React would bubble it up to here.
    e.stopPropagation();
    if (!save) return onClose();
    setBusy(true);
    setError(null);
    try {
      await save();
      if (stayOpen) return;
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
          <p role="status" className={cx("vl-body-s", s.demo)}>
            <strong>Not saved: the portal is showing demo data.</strong> Everything you entered checks out. To save, run the
            API and start the portal with <code>VITE_API_URL</code> set (it is in <code>apps/web/.env.local</code>).
          </p>
        )}
        <div className={s.actions}>
          {extra && <span className={s.extra}>{extra}</span>}
          <Button variant={!action && extra ? "primary" : "secondary"} onClick={onClose} disabled={busy}>
            {!action ? "Done" : demoDone ? "Close" : "Cancel"}
          </Button>
          {action && (
            <Button type="submit" disabled={busy || demoDone}>
              {busy ? busyLabel : action}
            </Button>
          )}
        </div>
      </form>
    </dialog>
  );
}

/** The account's time zone (dates and times in forms are in it), or null until /me has loaded. */
export function useZone(): string | null {
  return useAccount()?.tz ?? null;
}

/** Time zone and Settings › Units (goal targets in miles or lb), or null until /me has loaded. */
export function useAccount(): { tz: string; units: "metric" | "imperial" } | null {
  const api = useApi();
  const [v, setV] = useState<{ tz: string; units: "metric" | "imperial" } | null>(null);
  useEffect(() => {
    let live = true;
    api.me().then(
      (m) => live && setV({ tz: m.timezone, units: m.units ?? "metric" }),
      () => live && setV({ tz: Intl.DateTimeFormat().resolvedOptions().timeZone, units: "metric" }),
    );
    return () => {
      live = false;
    };
  }, [api]);
  return v;
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

/** A multi-line Form / Field (Add a note) with a character count. */
export function TextAreaField({ label, value, onChange, helper, max, placeholder }: { label: string; value: string; onChange: (v: string) => void; helper?: ReactNode; max: number; placeholder?: string }) {
  const id = useId();
  return (
    <div className={s.field}>
      <label htmlFor={id} className="vl-label-l">
        {label}
      </label>
      <div className={cx(s.input, s.area)}>
        <textarea id={id} className={cx("vl-body-m", s.control)} rows={4} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      </div>
      <span className={s.helperRow}>
        <span className={cx("vl-caption", s.helper)}>{helper}</span>
        <span className={cx("vl-caption", value.length > max ? s.error : s.helper)}>
          {value.length} / {max}
        </span>
      </span>
    </div>
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

/**
 * A closed "Need help?" row that opens to short questions and answers (no Figma frame; built from the Note
 * and list styles). Used wherever a step happens outside VitalLink or the wording alone may not be enough.
 * `children` come before the questions, for help that is a list of steps.
 */
export function Help({ title = "Need help?", items = [], children }: { title?: string; items?: { q: string; a: ReactNode }[]; children?: ReactNode }) {
  return (
    <details className={s.help}>
      <summary className={cx("vl-label-l", s.helpSummary)}>
        <Icon name="help-18" color="iconSubtle" />
        {title}
      </summary>
      <div className={s.helpBody}>
        {children}
        {items.length > 0 && (
          <dl className={s.helpItems}>
            {items.map((it) => (
              <div key={it.q}>
                <dt className="vl-body-s-strong">{it.q}</dt>
                <dd className={cx("vl-body-s", s.helpAnswer)}>{it.a}</dd>
              </div>
            ))}
          </dl>
        )}
      </div>
    </details>
  );
}

/** Numbered steps, each with a title and its own content (no Figma frame). `done` ticks a step off. */
export function Steps({ children }: { children: ReactNode }) {
  return <ol className={s.steps}>{children}</ol>;
}

export function Step({ title, done, children }: { title: string; done?: boolean; children?: ReactNode }) {
  return (
    <li className={cx(s.step, done && s.stepDone)}>
      <span className={cx("vl-label-l", s.stepMark)} aria-hidden="true">
        {done && <Icon name="check-16" />}
      </span>
      <div className={s.stepMain}>
        <span className="vl-body-m-strong">
          {title}
          {done && <span className="sr-only"> (done)</span>}
        </span>
        {children}
      </div>
    </li>
  );
}

/** A plain numbered list inside a Step or Help (things to tap, in order). */
export function SubSteps({ items }: { items: ReactNode[] }) {
  return (
    <ol className={cx("vl-body-s", s.subSteps)}>
      {items.map((it, i) => (
        <li key={i}>{it}</li>
      ))}
    </ol>
  );
}

/** Upload Document drop zone: drag a file in, or Choose File. */
/** `prompt`/`hint` default to Upload Document's wording. */
export function DropZone({ file, onFile, accept, prompt = "Drop a PDF or image here", hint = "PDF, JPG or PNG · up to 25 MB" }: { file: File | null; onFile: (f: File) => void; accept: string; prompt?: string; hint?: string }) {
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
      <span className="vl-body-m-strong">{file ? file.name : prompt}</span>
      <span className={cx("vl-caption", s.helper)}>{file ? `${Math.max(1, Math.round(file.size / 1024)).toLocaleString("en-US")} KB` : hint}</span>
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

/** Rows with a Remove-style action (and Edit with `onEdit`); a row without `onRemove` shows no button. */
export function ItemList({ label, items, empty, add }: { label: string; items: { key: string; title: string; caption: string; onRemove?: () => void; removeLabel?: string; onEdit?: () => void }[]; empty: string; add: ReactNode }) {
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
            {it.onEdit && (
              <Button variant="tertiary" size="sm" onClick={it.onEdit} aria-label={`Edit ${it.title}`}>
                Edit
              </Button>
            )}
            {it.onRemove && (
              <Button variant="tertiary" size="sm" onClick={it.onRemove} aria-label={`${it.removeLabel ?? "Remove"} ${it.title}`}>
                {it.removeLabel ?? "Remove"}
              </Button>
            )}
          </div>
        ))}
      </div>
      {add}
    </div>
  );
}

/** Label / value rows in a bordered list (Access details, Permissions). */
export function KeyValueList({ rows, label, labelWidth = 120 }: { rows: { label: string; value: ReactNode }[]; label: string; labelWidth?: number }) {
  return (
    <dl className={s.items} aria-label={label}>
      {rows.map((r) => (
        <div key={r.label} className={s.kv} style={{ gridTemplateColumns: `${labelWidth}px minmax(0, 1fr)` }}>
          <dt className={cx("vl-body-s", s.kvLabel)}>{r.label}</dt>
          <dd className="vl-body-s-strong">{r.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export { s as formStyles };
