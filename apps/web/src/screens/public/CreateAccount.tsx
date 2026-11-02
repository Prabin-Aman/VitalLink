// Create Account — Desktop 1440 (Figma 26:2). Step 1 creates the account (Supabase sign-up in production,
// POST /v1/auth/sign-up locally; with email confirmation the person continues from the emailed link); steps
// 2–8 (proposed, Figma 226:2235–226:3348, in Onboarding.tsx) save the rest through the ordinary API and
// can each be skipped, or left with "Save and finish later".
import { checkSignUp, passwordNeeds, todayIn } from "@vitallink/api";
import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { cx, Txt } from "../../components/kit";
import { Logo } from "../../components/ui";
import { applyPendingProfile, hasSession, resendConfirmation, signUp, supabase } from "../../auth";
import { useApi } from "../../data";
import { initialDrafts, OnboardingCard, saveStep, useAccountDefaults, type Created, type Drafts, type StepResult } from "./Onboarding";
import s from "./CreateAccount.module.css";

const STEPS = ["Account", "Personal Information", "Health Profile", "Body Metrics", "Medical Information", "Emergency Contact", "Privacy Preferences", "Finish"];

type Field = { key: string; label: string; type?: string; placeholder: string; help?: string; wide?: boolean; autoComplete?: string };

const FIELDS: Field[] = [
  { key: "first", label: "First name", placeholder: "Alex", autoComplete: "given-name" },
  { key: "last", label: "Last name", placeholder: "Carter", autoComplete: "family-name" },
  { key: "email", label: "Email address", type: "email", placeholder: "alex.carter@email.com", autoComplete: "email" },
  { key: "phone", label: "Mobile number", type: "tel", placeholder: "(214) 555-0148", autoComplete: "tel" },
  { key: "password", label: "Password", type: "password", placeholder: "", help: "At least 12 characters, one number and one symbol.", autoComplete: "new-password" },
  { key: "confirm", label: "Confirm password", type: "password", placeholder: "", autoComplete: "new-password" },
  { key: "zip", label: "ZIP code", placeholder: "75201", help: "Used to find clinics and facilities near you.", autoComplete: "postal-code" },
  { key: "dob", label: "Date of birth", type: "date", placeholder: "", help: "Used to personalise training and health targets.", autoComplete: "bday" },
  { key: "height", label: "Height", placeholder: "5 ft 10 in or 178 cm", help: "Used to calculate BMI.", wide: true },
];

export function CreateAccount() {
  const nav = useNavigate();
  const api = useApi();
  const [v, setV] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [terms, setTerms] = useState(false);
  const [reminders, setReminders] = useState(true);
  const [research, setResearch] = useState(false);
  const [step, setStep] = useState(1);
  const [drafts, setDraftsState] = useState<Drafts>(initialDrafts);
  const setDrafts = useCallback((f: (d: Drafts) => Drafts) => setDraftsState(f), []);
  const [results, setResults] = useState<Record<number, StepResult>>({});
  // What each step last saved: after Back, Continue on an unchanged step doesn't add the same rows again.
  const [saved, setSaved] = useState<Record<number, string>>({});
  const created = useRef<Created>({ conditions: 0, contactId: null });
  // Supabase sent a confirmation link to this address; the account continues from that link.
  const [confirming, setConfirming] = useState<string | null>(null);
  // Back from the confirmation link (signed in by it): save the sign-up details that waited, then step 2.
  useEffect(() => {
    if (!supabase || !hasSession()) return;
    setStep(2);
    applyPendingProfile(api).catch(() => undefined);
  }, [api]);
  useAccountDefaults(setDrafts, step > 1);

  const pw = v.password ?? "";
  const matches = pw.length > 0 && pw === (v.confirm ?? "");
  const needs = pw ? passwordNeeds(pw) : null;
  const ready = !!(v.first && v.last && v.email && pw && !needs && matches && terms);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (step > 1) return next();
    if (!ready) return;
    if (api.isMock) return setStep(2);
    const today = todayIn(Intl.DateTimeFormat().resolvedOptions().timeZone);
    const form = { first: "", last: "", email: "", phone: "", password: "", confirm: "", zip: "", dob: "", height: "", ...v };
    const c = checkSignUp(form, today);
    if (!c.ok) return setError(c.error);
    setBusy(true);
    setError(null);
    signUp(api, c.body)
      .then(
        (r) => (r === "confirm-email" ? setConfirming(c.body.email) : setStep(2)),
        (err: Error) => setError(err.message),
      )
      .finally(() => setBusy(false));
  };

  // Steps 2–8: save what was filled in, then move on; the last step enters the app.
  const next = () => {
    if (step === 8) return nav("/");
    const key = JSON.stringify(slice(step, drafts));
    if (saved[step] === key) return setStep(step + 1);
    setBusy(true);
    setError(null);
    saveStep(api, step, drafts, created.current)
      .then(
        (r) => {
          setResults((x) => ({ ...x, [step]: r }));
          setSaved((x) => ({ ...x, [step]: key }));
          setStep(step + 1);
          window.scrollTo(0, 0);
        },
        (err: Error) => setError(err.message),
      )
      .finally(() => setBusy(false));
  };

  const help = (f: Field) => {
    if (f.key === "confirm") return matches ? "Both entries match." : v.confirm ? "Entries don't match yet." : "";
    if (f.key === "password" && needs) return needs;
    return f.help;
  };
  // Why Continue is still off, so the button never just looks broken.
  const blocker = !v.first || !v.last ? "Enter your first and last name." : !v.email ? "Enter your email." : !pw || needs ? "Choose a password that meets the rules." : !matches ? "Confirm your password." : !terms ? "Agree to the Terms of Service to continue." : null;

  return (
    <form className={s.page} onSubmit={submit}>
      <header className={s.header}>
        <Logo />
        {step === 1 && (
          <Txt s="bodyM" c="textSecondary">
            Already have an account?{" "}
            <Link to="/sign-in" className={cx("vl-body-m-strong", s.link)}>Sign In</Link>
          </Txt>
        )}
      </header>

      <ol className={s.stepper} aria-label="Sign-up steps">
        {STEPS.map((name, i) => (
          <li key={name} aria-current={i === step - 1 ? "step" : undefined}>
            <span className={s.track}>
              <span className={cx("vl-label-m", s.marker, i < step && s.markerOn)}>{i + 1}</span>
              {i < STEPS.length - 1 && <span className={s.connector} />}
            </span>
            <Txt s={i === step - 1 ? "labelL" : "bodyS"} c={i === step - 1 ? "textBrand" : "textTertiary"}>{name}</Txt>
          </li>
        ))}
      </ol>

      <main className={s.body}>
        {confirming ? (
          <section className={s.card} aria-labelledby="confirm-title">
            <div className={s.heading}>
              <Txt s="overline" c="textBrand">STEP 1 OF {STEPS.length}</Txt>
              <Txt s="h2" as="h1"><span id="confirm-title">Check your email</span></Txt>
              <Txt s="bodyM" c="textSecondary" as="p">
                We sent a link to {confirming}. Open it to confirm your email, and you'll continue here with step 2.
              </Txt>
            </div>
            <Txt s="bodyS" c="textTertiary" as="p">
              Didn't get it? Check spam, or{" "}
              <button
                type="button"
                className={cx("vl-body-s-strong", s.textButton)}
                disabled={busy}
                onClick={() => {
                  setBusy(true);
                  setError(null);
                  resendConfirmation(confirming)
                    .then(() => setError(null), (e: Error) => setError(e.message))
                    .finally(() => setBusy(false));
                }}
              >
                send it again
              </button>
              .
            </Txt>
          </section>
        ) : step > 1 ? (
          <OnboardingCard step={step} drafts={drafts} setDrafts={setDrafts} results={results} demo={api.isMock} />
        ) : (
        <section className={s.card} aria-labelledby="create-title">
          <div className={s.heading}>
            <Txt s="overline" c="textBrand">STEP 1 OF {STEPS.length}</Txt>
            <Txt s="h2" as="h1"><span id="create-title">Create your account</span></Txt>
            <Txt s="bodyM" c="textSecondary" as="p">
              This is the only step you need to finish now. Health, training and emergency details can be added later.
            </Txt>
          </div>
          <div className={s.grid}>
            {FIELDS.map((f) => (
              <label key={f.key} className={cx(s.field, f.wide && s.wide)}>
                <Txt s="labelL">{f.label}</Txt>
                <input
                  className={cx("vl-body-m", s.input)}
                  type={f.type ?? "text"}
                  placeholder={f.placeholder}
                  autoComplete={f.autoComplete}
                  value={v[f.key] ?? ""}
                  onChange={(e) => setV({ ...v, [f.key]: e.target.value })}
                  max={f.type === "date" ? new Date().toISOString().slice(0, 10) : undefined}
                  aria-invalid={(f.key === "confirm" && !!v.confirm && !matches) || (f.key === "password" && !!needs) ? true : undefined}
                />
                {help(f) ? <Txt s="caption" c={(f.key === "confirm" && v.confirm && !matches) || (f.key === "password" && needs) ? "textCritical" : "textTertiary"}>{help(f)}</Txt> : null}
              </label>
            ))}
          </div>
          <div className={s.consent}>
            <Check checked={terms} onChange={setTerms}>I agree to the VitalLink Terms of Service and Privacy Policy.</Check>
            <Check checked={reminders} onChange={setReminders}>Send me training reminders and goal alerts.</Check>
            <Check checked={research} onChange={setResearch}>Share anonymised health data to improve VitalLink. You can turn this off at any time.</Check>
          </div>
        </section>
        )}
      </main>

      <footer className={s.footer}>
        {/* The account exists after step 1, so Back stops at step 2. */}
        <button type="button" className={cx("vl-label-l", s.back)} disabled={step <= 2 || busy} onClick={() => setStep(step - 1)}>Back</button>
        <span className={s.spacer} />
        {(error || (step === 1 && blocker)) && (
          <Txt s="bodyS" c={error ? "textCritical" : "textTertiary"}>
            <span role={error ? "alert" : undefined}>{error ?? blocker}</span>
          </Txt>
        )}
        {confirming ? (
          <Link to="/sign-in" className={cx("vl-body-m-strong", s.link)}>Back to Sign In</Link>
        ) : (
          <>
            {step < 8 && <Link to={step === 1 ? "/sign-in" : "/"} className={cx("vl-body-m-strong", s.link)}>Save and finish later</Link>}
            <button type="submit" className={cx("vl-label-l", s.continue)} disabled={(step === 1 && !ready) || busy}>
              {busy ? (step === 1 ? "Creating…" : "Saving…") : step === 8 ? "Go to Dashboard" : "Continue"}
            </button>
          </>
        )}
      </footer>
    </form>
  );
}

/** The drafts a step saves (steps 7 and 8 save nothing). */
function slice(step: number, d: Drafts): unknown {
  return [null, null, d.personal, d.targets, d.body, d.conditions, d.contact][step] ?? step;
}

function Check({ checked, onChange, children }: { checked: boolean; onChange: (v: boolean) => void; children: string }) {
  return (
    <label className={s.check}>
      <input type="checkbox" className={s.box} checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <Txt s="bodyM" c="textSecondary">{children}</Txt>
    </label>
  );
}
