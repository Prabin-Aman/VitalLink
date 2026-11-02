// Create Account steps 2–8 (proposed, Figma page 15: 226:2235 Personal Information, 226:2418 Health Profile,
// 226:2607 Body Metrics, 226:2790 Medical Information, 226:2979 Emergency Contact, 226:3162 Privacy
// Preferences, 226:3348 Finish). The account exists after step 1, so each Continue saves what was filled in
// through the ordinary API; a blank step is skipped. Rules in packages/api/src/forms.ts.
import {
  checkBodyMetrics,
  checkContact,
  checkTargets,
  CONDITION_KINDS,
  SEXES,
  timeZoneOptions,
  UNIT_OPTIONS,
  type Option,
  type TargetsForm,
} from "@vitallink/api";
import { useEffect, useState, type ReactNode } from "react";
import { cx, Txt } from "../../components/kit";
import { useApi } from "../../data";
import s from "./CreateAccount.module.css";

export type StepResult = "saved" | "skipped" | string;

export interface Drafts {
  personal: { sex: string; language: string; units: "imperial" | "metric"; timezone: string };
  targets: TargetsForm;
  body: { weight: string; waist: string; bodyFat: string };
  conditions: { label: string; kind: "chronic" | "allergy" }[];
  contact: { name: string; relation: string; phone: string; hospital: string };
}

const ownZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

export function initialDrafts(): Drafts {
  return {
    personal: { sex: "", language: "en-US", units: "imperial", timezone: ownZone },
    targets: { steps: "10000", move: "500", exercise: "30", stand: "12", sleep: "7.5" },
    body: { weight: "", waist: "", bodyFat: "" },
    conditions: [],
    contact: { name: "", relation: "", phone: "", hospital: "" },
  };
}

/** Fills the drafts with what the new account already has (time zone, units, targets). */
export function useAccountDefaults(setDrafts: (f: (d: Drafts) => Drafts) => void, ready: boolean) {
  const api = useApi();
  useEffect(() => {
    if (!ready || api.isMock) return;
    Promise.all([api.me(), api.targets()]).then(
      ([me, t]) =>
        setDrafts((d) => ({
          ...d,
          personal: { ...d.personal, timezone: me.timezone || d.personal.timezone, units: me.units ?? d.personal.units },
          targets: {
            steps: String(t.daily_steps_goal),
            move: String(t.move_kcal_goal),
            exercise: String(t.exercise_min_goal),
            stand: String(t.stand_hours_goal),
            sleep: String(Math.round((t.sleep_goal_min / 60) * 10) / 10),
          },
        })),
      () => undefined,
    );
  }, [api, ready, setDrafts]);
}

/** What steps 5 and 6 already wrote, so editing them after Back adds only what's new. */
export interface Created { conditions: number; contactId: string | null }

/** Saves one step; resolves with what the Finish summary says about it. Throws with a message to show. */
export async function saveStep(api: ReturnType<typeof useApi>, step: number, d: Drafts, created: Created): Promise<StepResult> {
  const demo = api.isMock;
  switch (step) {
    case 2: {
      if (!demo) await api.updateAccount({ sex: (d.personal.sex || null) as never, language: d.personal.language, units: d.personal.units, timezone: d.personal.timezone } as never);
      return "saved";
    }
    case 3: {
      const c = checkTargets(d.targets);
      if (!c.ok) throw new Error(c.error);
      if (!demo) await api.updateTargets(c.body);
      return "saved";
    }
    case 4: {
      const c = checkBodyMetrics(d.body, d.personal.units, new Date(Date.now() - 60000).toISOString());
      if (!c.ok) throw new Error(c.error);
      if (!c.body.length) return "skipped";
      if (!demo) for (const r of c.body) await api.addReading(r.metric, r.value, r.at);
      return `${c.body.length} saved`;
    }
    case 5: {
      if (!d.conditions.length) return "skipped";
      if (!demo) for (const x of d.conditions.slice(created.conditions)) await api.addCondition(x.label, x.kind);
      created.conditions = Math.max(created.conditions, d.conditions.length);
      return `${d.conditions.length} added`;
    }
    case 6: {
      const c = checkContact(d.contact);
      if (c && !c.ok) throw new Error(c.error);
      if (!c && !d.contact.hospital.trim()) return "skipped";
      if (!demo) {
        if (c && created.contactId) await api.updateContact(created.contactId, c.body);
        else if (c) created.contactId = (await api.addContact({ ...c.body, primary: true }))?.id ?? null;
        if (d.contact.hospital.trim()) await api.updateEmergencyProfile({ preferred_hospital: d.contact.hospital.trim() });
      }
      return "saved";
    }
    default:
      return "saved";
  }
}

const HEADINGS: Record<number, { title: string; sub: string }> = {
  2: { title: "About you", sub: "Used for energy needs, heart-rate estimates and how numbers are shown. Everything here is optional." },
  3: { title: "Your daily targets", sub: "Rings, streaks and goals measure against these. Change them any time in Goals › Daily Targets." },
  4: { title: "Body measurements", sub: "Saved as today's readings. Leave any blank; your scale or watch can fill them in later." },
  5: { title: "Medical information", sub: "Conditions and allergies show on your Emergency Medical ID. Add medications from Medications once you're in." },
  6: { title: "Emergency contact", sub: "Who first responders should call. They're shown on your Emergency Medical ID." },
  7: { title: "Your privacy", sub: "How VitalLink treats your data. You can change sharing any time in Privacy." },
  8: { title: "You're all set", sub: "Here's what you added. Anything you skipped can be filled in later from Settings and Emergency Profile." },
};

const PRIVACY = [
  "Nobody sees your health data until you share it with a person you name.",
  "Shares end on the date you pick, and you can stop one at any time.",
  "Your Emergency Medical ID shows only allergies, conditions, medications and contacts.",
  "Export or delete everything from Settings › Data Export and Delete Account.",
];

export const SUMMARY_LABELS: Record<number, string> = {
  1: "Account",
  2: "Personal information",
  3: "Daily targets",
  4: "Body measurements",
  5: "Medical information",
  6: "Emergency contact",
};

export function OnboardingCard({ step, drafts, setDrafts, results, demo }: { step: number; drafts: Drafts; setDrafts: (f: (d: Drafts) => Drafts) => void; results: Record<number, StepResult>; demo: boolean }) {
  const h = HEADINGS[step];
  const set = <K extends keyof Drafts>(k: K) => (patch: Partial<Drafts[K]>) => setDrafts((d) => ({ ...d, [k]: { ...(d[k] as object), ...patch } }));
  const imperial = drafts.personal.units === "imperial";
  const [adding, setAdding] = useState<{ label: string; kind: "chronic" | "allergy" }>({ label: "", kind: "chronic" });

  let body: ReactNode = null;
  if (step === 2) {
    const p = drafts.personal, up = set("personal");
    body = (
      <div className={s.grid}>
        <Select label="Sex" value={p.sex} options={SEXES} onChange={(sex) => up({ sex })} help="Used for energy and heart-rate estimates." />
        <Select label="Units" value={p.units} options={UNIT_OPTIONS} onChange={(units) => up({ units: units as "imperial" | "metric" })} help="Distance, pace, body weight and goals." />
        <Select label="Time zone" value={p.timezone} options={timeZoneOptions(p.timezone)} onChange={(timezone) => up({ timezone })} help="Days and reminders follow it." />
      </div>
    );
  } else if (step === 3) {
    const t = drafts.targets, up = set("targets");
    body = (
      <div className={s.grid}>
        <Input label="Steps a day" value={t.steps} onChange={(steps) => up({ steps })} inputMode="numeric" />
        <Input label="Sleep a night" value={t.sleep} onChange={(sleep) => up({ sleep })} inputMode="decimal" help="Hours, e.g. 7.5" />
        <Input label="Exercise a day" value={t.exercise} onChange={(exercise) => up({ exercise })} inputMode="numeric" help="Minutes" />
        <Input label="Stand hours" value={t.stand} onChange={(stand) => up({ stand })} inputMode="numeric" help="Hours with a minute of standing" />
        <Input label="Move a day" value={t.move} onChange={(move) => up({ move })} inputMode="numeric" help="Active energy in kcal, not counting resting burn." wide />
      </div>
    );
  } else if (step === 4) {
    const b = drafts.body, up = set("body");
    body = (
      <div className={s.grid}>
        <Input label="Weight" value={b.weight} onChange={(weight) => up({ weight })} inputMode="decimal" placeholder={imperial ? "lb" : "kg"} help={imperial ? "Pounds" : "Kilograms"} />
        <Input label="Waist" value={b.waist} onChange={(waist) => up({ waist })} inputMode="decimal" placeholder={imperial ? "in" : "cm"} help={imperial ? "Inches" : "Centimetres"} />
        <Input label="Body fat" value={b.bodyFat} onChange={(bodyFat) => up({ bodyFat })} inputMode="decimal" placeholder="%" help="Optional." />
      </div>
    );
  } else if (step === 5) {
    const add = () => {
      if (!adding.label.trim()) return;
      setDrafts((d) => ({ ...d, conditions: [...d.conditions, { label: adding.label.trim().slice(0, 80), kind: adding.kind }] }));
      setAdding({ label: "", kind: adding.kind });
    };
    body = (
      <>
        <div className={s.grid}>
          <Input label="Condition or allergy" value={adding.label} onChange={(label) => setAdding({ ...adding, label })} placeholder="e.g. Asthma" onEnter={add} />
          <Select label="Kind" value={adding.kind} options={CONDITION_KINDS} onChange={(kind) => setAdding({ ...adding, kind: kind as "chronic" | "allergy" })} />
        </div>
        <span>
          <button type="button" className={cx("vl-label-l", s.link, s.textButton)} onClick={add}>+ Add</button>
        </span>
        {drafts.conditions.length > 0 && (
          <div className={s.consent}>
            <Txt s="overline" c="textBrand">ADDED</Txt>
            {drafts.conditions.map((x, i) => (
              <span key={`${x.label}-${i}`} className={s.listRow}>
                <Txt s="bodyM" c="textSecondary">{x.label} · {x.kind === "allergy" ? "Allergy" : "Condition"}</Txt>
                <button type="button" className={cx("vl-label-m", s.link, s.textButton)} onClick={() => setDrafts((d) => ({ ...d, conditions: d.conditions.filter((_, j) => j !== i) }))} aria-label={`Remove ${x.label}`}>
                  Remove
                </button>
              </span>
            ))}
          </div>
        )}
      </>
    );
  } else if (step === 6) {
    const c = drafts.contact, up = set("contact");
    body = (
      <div className={s.grid}>
        <Input label="Contact name" value={c.name} onChange={(name) => up({ name })} placeholder="Name" autoComplete="off" />
        <Input label="Relationship" value={c.relation} onChange={(relation) => up({ relation })} placeholder="e.g. Mother" />
        <Input label="Phone" value={c.phone} onChange={(phone) => up({ phone })} placeholder="(214) 555-0193" type="tel" />
        <Input label="Preferred hospital" value={c.hospital} onChange={(hospital) => up({ hospital })} placeholder="Optional" />
      </div>
    );
  } else if (step === 7) {
    body = (
      <div className={s.consent}>
        <Txt s="overline" c="textBrand">BY DEFAULT</Txt>
        {PRIVACY.map((x) => (
          <Txt key={x} s="bodyM" c="textSecondary" as="p">• {x}</Txt>
        ))}
      </div>
    );
  } else if (step === 8) {
    body = (
      <div className={s.consent}>
        <Txt s="overline" c="textBrand">SUMMARY</Txt>
        {Object.entries(SUMMARY_LABELS).map(([k, label]) => (
          <Txt key={k} s="bodyM" c="textSecondary" as="p">
            {label}: {k === "1" ? "created" : (results[Number(k)] ?? "skipped")}
          </Txt>
        ))}
        {demo && <Txt s="caption" c="textTertiary" as="p">This is demo data, so nothing was saved.</Txt>}
      </div>
    );
  }

  return (
    <section className={s.card} aria-labelledby="create-title">
      <div className={s.heading}>
        <Txt s="overline" c="textBrand">STEP {step} OF 8</Txt>
        <Txt s="h2" as="h1"><span id="create-title">{h.title}</span></Txt>
        <Txt s="bodyM" c="textSecondary" as="p">{h.sub}</Txt>
      </div>
      {body}
    </section>
  );
}

function Input({ label, value, onChange, help, wide, onEnter, ...rest }: { label: string; value: string; onChange: (v: string) => void; help?: string; wide?: boolean; onEnter?: () => void; placeholder?: string; inputMode?: "numeric" | "decimal"; type?: string; autoComplete?: string }) {
  return (
    <label className={cx(s.field, wide && s.wide)}>
      <Txt s="labelL">{label}</Txt>
      <input
        className={cx("vl-body-m", s.input)}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onEnter ? (e) => { if (e.key === "Enter") { e.preventDefault(); onEnter(); } } : undefined}
        {...rest}
      />
      {help ? <Txt s="caption" c="textTertiary">{help}</Txt> : null}
    </label>
  );
}

function Select({ label, value, options, onChange, help }: { label: string; value: string; options: Option[]; onChange: (v: string) => void; help?: string }) {
  return (
    <label className={s.field}>
      <Txt s="labelL">{label}</Txt>
      <select className={cx("vl-body-m", s.input)} value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>{o.label}</option>
        ))}
      </select>
      {help ? <Txt s="caption" c="textTertiary">{help}</Txt> : null}
    </label>
  );
}
