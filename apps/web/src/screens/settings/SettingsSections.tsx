// Settings › Data Export (Figma 205:1541), Delete Account (205:1781) and Security › Change password (221:2114).
import { ApiError, checkPasswordChange } from "@vitallink/api";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { FormDialog, must, Note, TextField } from "../../components/Form";
import { Panel, RowButton, Txt } from "../../components/kit";
import { Button } from "../../components/ui";
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

export function SecuritySection() {
  const [open, setOpen] = useState(false);
  return (
    <Panel title="Security" gap={14}>
      <div className={s.securityRow}>
        <div>
          <Txt s="bodyMStrong" as="p">Password</Txt>
          <Txt s="caption" c="textTertiary">Change it any time. You stay signed in on this device.</Txt>
        </div>
        <RowButton onClick={() => setOpen(true)}>Change password</RowButton>
      </div>
      {open && <ChangePasswordDialog onClose={() => setOpen(false)} />}
    </Panel>
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
        if (!api.isMock) await api.changePassword(c.current, c.next);
      }}
    >
      <TextField label="Current password" type="password" autoComplete="current-password" value={f.current} onChange={set("current")} autoFocus />
      <TextField label="New password" type="password" autoComplete="new-password" value={f.next} onChange={set("next")} helper="12+ characters with a number and a symbol." />
      <TextField label="Confirm new password" type="password" autoComplete="new-password" value={f.confirm} onChange={set("confirm")} />
      <Note>Signed in with Google? Use Forgot password to set a password first.</Note>
    </FormDialog>
  );
}
