// Settings › Data Export (Figma 205:1541), Delete Account (205:1781), Security › Change password (221:2114) and,
// proposed: Account (232:2247), Security (232:2446), Change email (232:2637), two-step set up and off (232:2670, 232:2705).
import { ApiError, checkPasswordChange } from "@vitallink/api";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { FormDialog, FormProblem, Help, must, Note, TextField } from "../../components/Form";
import { cx, Panel, RowButton, Txt } from "../../components/kit";
import { Button } from "../../components/ui";
import { accountAccess, changeEmail, changePassword, confirmTwoStep, hostedSignIn, removeTwoStep, signOutEverywhere, startTwoStep } from "../../auth";
import { endSession, useApi } from "../../data";
import { exportMyData } from "../../download";
import s from "./Settings.module.css";

const INCLUDED = [
  "Your profile, targets and settings",
  "Health readings, devices and sync history",
  "Workouts, training plans, meals and water",
  "Medications, doses, appointments, conditions and emergency contacts",
  "Medical records and lab results (details; files download from Medical Records)",
  "Goals, notifications, who you've shared with and the access log",
];

export function DataExportSection() {
  const api = useApi();
  const [busy, setBusy] = useState(false);
  return (
    <Panel title="Data Export" gap={18}>
      <Txt s="bodyM" c="textSecondary" as="p">
        Download everything VitalLink holds about you as one JSON file you can keep or move to another service.
      </Txt>
      <div className={s.included}>
        <Txt s="overline" c="textTertiary">What's included</Txt>
        <ul>
          {INCLUDED.map((x) => (
            <li key={x} className="vl-body-s">{x}</li>
          ))}
        </ul>
      </div>
      <div className={s.exportRow}>
        <Button
          disabled={busy}
          onClick={() => {
            setBusy(true);
            exportMyData(api).finally(() => setBusy(false));
          }}
        >
          {busy ? "Preparing…" : "Download my data"}
        </Button>
        <Txt s="caption" c="textTertiary">One JSON file, usually under 5 MB.</Txt>
      </div>
    </Panel>
  );
}

export function DeleteAccountSection() {
  const api = useApi();
  const nav = useNavigate();
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ready = typed.trim() === "DELETE";

  const remove = async () => {
    if (!ready) return;
    setBusy(true);
    setError(null);
    try {
      if (api.isMock) {
        setError("This is demo data, so there's no account to delete.");
        return;
      }
      await api.deleteAccount();
      endSession();
      nav("/welcome", { replace: true });
    } catch (e) {
      setError(
        e instanceof ApiError && e.code === "reauth_required"
          ? "For your security, sign in again, then delete within 10 minutes."
          : e instanceof Error
            ? e.message
            : String(e),
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Delete Account" titleColor="textCritical" gap={18}>
      <div className={s.warning} role="note">
        <Txt s="bodyMStrong" c="textCritical">This can't be undone</Txt>
        <Txt s="bodyS" c="textSecondary" as="p">
          Deleting your account permanently erases your readings, workouts, meals, medications, records and files, ends every share you've given, and signs you out everywhere.
        </Txt>
      </div>
      <Txt s="bodyM" c="textSecondary" as="p">Want a copy first? Download your data before you delete.</Txt>
      <div className={s.confirm}>
        <TextField
          label="Type DELETE to confirm"
          value={typed}
          onChange={setTyped}
          placeholder="DELETE"
          autoComplete="off"
          helper="If you haven't signed in during the last 10 minutes, you'll be asked to sign in again first."
        />
      </div>
      {error && (
        <p className={`vl-body-s ${s.error}`} role="alert">
          {error}
        </p>
      )}
      <div className={s.exportRow}>
        <Button variant="secondary" onClick={() => exportMyData(api)}>
          Download my data
        </Button>
        <Button variant="danger" disabled={!ready || busy} onClick={remove}>
          {busy ? "Deleting…" : "Delete my account"}
        </Button>
      </div>
    </Panel>
  );
}

function SettingRow({ title, caption, children }: { title: string; caption: string; children?: ReactNode }) {
  return (
    <div className={s.securityRow}>
      <div>
        <Txt s="bodyMStrong" as="p">{title}</Txt>
        <Txt s="caption" c="textTertiary">{caption}</Txt>
      </div>
      {children}
    </div>
  );
}

/** How the person signs in, or null while it loads. */
function useAccess() {
  const [access, setAccess] = useState<{ methods: string[]; factorId: string | null } | null>(null);
  const load = useCallback(() => {
    accountAccess().then(setAccess, () => setAccess({ methods: [], factorId: null }));
  }, []);
  useEffect(load, [load]);
  return [access, load] as const;
}

export function AccountSection({ email, memberDate }: { email: string; memberDate: string }) {
  const nav = useNavigate();
  const [access] = useAccess();
  const [changing, setChanging] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const everywhere = () => {
    if (!confirm("Sign out on every phone and browser, including this one?")) return;
    signOutEverywhere().then(
      () => nav("/sign-in", { replace: true }),
      (e: Error) => setProblem(e.message),
    );
  };
  return (
    <Panel title="Account" gap={14}>
      <Txt s="bodyM" c="textSecondary" as="p">How you sign in to VitalLink and where you're signed in.</Txt>
      <SettingRow
        title="Email address"
        caption={hostedSignIn ? `${email}. A change takes effect when you open the link we email you.` : `${email}. Changing it needs the hosted sign-in.`}
      >
        {hostedSignIn && <RowButton onClick={() => setChanging(true)}>Change email</RowButton>}
      </SettingRow>
      <SettingRow title="Sign-in methods" caption={access ? access.methods.join(", ") || "N/A" : "Loading…"} />
      <SettingRow title="Member since" caption={memberDate} />
      <SettingRow
        title="Signed-in devices"
        caption={hostedSignIn ? "Signs you out on every phone and browser, including this one." : "Sign out from the profile menu. Signing out everywhere needs the hosted sign-in."}
      >
        {hostedSignIn && <RowButton onClick={everywhere}>Sign out everywhere</RowButton>}
      </SettingRow>
      {problem && <Txt s="bodyS" c="textCritical" as="p">{problem}</Txt>}
      {changing && <ChangeEmailDialog onClose={() => setChanging(false)} />}
    </Panel>
  );
}

function ChangeEmailDialog({ onClose }: { onClose: () => void }) {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  return (
    <FormDialog
      overline="Settings › Account"
      title="Change email"
      subtitle="We email a confirmation link. If both your old and new address get one, open both. Your sign-in changes after that."
      action={sent ? undefined : "Send Link"}
      busyLabel="Sending…"
      stayOpen
      onClose={onClose}
      save={
        sent
          ? undefined
          : async () => {
              const next = email.trim().toLowerCase();
              if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(next)) throw new FormProblem("Enter a valid email address.");
              await changeEmail(next);
              setSent(true);
            }
      }
    >
      {sent ? (
        <Note>Check your email. Until you open the link, keep using your current email to sign in.</Note>
      ) : (
        <>
          <TextField label="New email address" type="email" autoComplete="email" value={email} onChange={setEmail} placeholder="name@example.com" autoFocus />
          <Note>Until then, keep using your current email to sign in.</Note>
        </>
      )}
    </FormDialog>
  );
}

export function SecuritySection() {
  const [open, setOpen] = useState(false);
  const [access, reload] = useAccess();
  const [twoStep, setTwoStep] = useState<"setup" | "off" | null>(null);
  const on = !!access?.factorId;
  return (
    <Panel title="Security" gap={14}>
      <Txt s="bodyM" c="textSecondary" as="p">Protect your account and your health data.</Txt>
      <SettingRow title="Password" caption="Change it any time. You stay signed in on this device.">
        <RowButton onClick={() => setOpen(true)}>Change password</RowButton>
      </SettingRow>
      <SettingRow
        title="Two-step verification"
        caption={
          !hostedSignIn
            ? "Needs the hosted sign-in, so it isn't available here."
            : !access
              ? "Loading…"
              : on
                ? "On. Signing in asks for a code from your authenticator app."
                : "Off. Asks for a code from an authenticator app when you sign in."
        }
      >
        {hostedSignIn && access && <RowButton onClick={() => setTwoStep(on ? "off" : "setup")}>{on ? "Turn off" : "Set up"}</RowButton>}
      </SettingRow>
      {open && <ChangePasswordDialog onClose={() => setOpen(false)} />}
      {twoStep === "setup" && <TwoStepSetupDialog onClose={() => { setTwoStep(null); reload(); }} />}
      {twoStep === "off" && access?.factorId && <TwoStepOffDialog factorId={access.factorId} onClose={() => { setTwoStep(null); reload(); }} />}
    </Panel>
  );
}

function TwoStepSetupDialog({ onClose }: { onClose: () => void }) {
  const [setup, setSetup] = useState<{ factorId: string; qr: string; key: string } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const done = useRef(false);
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    startTwoStep().then(setSetup, (e: Error) => setProblem(e.message));
  }, []);
  return (
    <FormDialog
      overline="Settings › Security"
      title="Set up two-step verification"
      subtitle="Scan this with an authenticator app such as Google Authenticator or Authy, then enter the code it shows."
      action="Turn On"
      onClose={() => {
        // Closed without confirming: drop the unfinished setup.
        if (setup && !done.current) removeTwoStep(setup.factorId).catch(() => undefined);
        onClose();
      }}
      save={async () => {
        if (!setup) throw new FormProblem(problem ?? "The code isn't ready yet.");
        if (!/^\d{6}$/.test(code.trim())) throw new FormProblem("Enter the 6-digit code from your authenticator app.");
        await confirmTwoStep(setup.factorId, code.trim());
        done.current = true;
      }}
    >
      {setup ? <img className={cx("vl-light", s.qr)} src={setup.qr} alt="QR code for your authenticator app" /> : <Txt s="bodyS" c={problem ? "textCritical" : "textTertiary"} as="p">{problem ?? "Preparing your code…"}</Txt>}
      <TextField label="6-digit code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={setCode} />
      {setup && <Note>If you lose the app, you'll need its key to get back in. Keep this key safe: {setup.key}</Note>}
      <Help
        items={[
          { q: "What is an authenticator app?", a: "A free app that shows a new 6-digit code every 30 seconds, such as Google Authenticator, Microsoft Authenticator or Authy. Install one on your phone first." },
          { q: "I can't scan the code", a: "In the app, choose to enter a setup key by hand and type the key shown above." },
          { q: "The code isn't accepted", a: "Codes change every 30 seconds, so type the newest one. Check that your phone's clock is set automatically." },
          { q: "What if I get a new phone?", a: "Add the same key to the authenticator app on the new phone, or turn two-step verification off here before you switch and set it up again afterwards." },
        ]}
      />
    </FormDialog>
  );
}

function TwoStepOffDialog({ factorId, onClose }: { factorId: string; onClose: () => void }) {
  return (
    <FormDialog
      overline="Settings › Security"
      title="Turn off two-step verification"
      subtitle="Signing in will only need your password again."
      action="Turn Off"
      onClose={onClose}
      save={() => removeTwoStep(factorId)}
    >
      <Note>You can set it up again at any time.</Note>
    </FormDialog>
  );
}

function ChangePasswordDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ current: "", next: "", confirm: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormDialog
      overline="Settings › Security"
      title="Change password"
      subtitle="You stay signed in here. Links from Forgot password stop working."
      action="Change Password"
      onClose={onClose}
      save={async () => {
        const c = must(checkPasswordChange(f));
        if (!api.isMock) await changePassword(api, c.current, c.next);
      }}
    >
      <TextField label="Current password" type="password" autoComplete="current-password" value={f.current} onChange={set("current")} autoFocus />
      <TextField label="New password" type="password" autoComplete="new-password" value={f.next} onChange={set("next")} helper="12+ characters with a number and a symbol." />
      <TextField label="Confirm new password" type="password" autoComplete="new-password" value={f.confirm} onChange={set("confirm")} />
      <Note>Signed in with Google? Use Forgot password to set a password first.</Note>
    </FormDialog>
  );
}
