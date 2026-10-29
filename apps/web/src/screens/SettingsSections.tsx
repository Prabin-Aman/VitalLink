// Settings › Data Export (Figma 205:1541) and Delete Account (205:1781), on page 195:2.
import { ApiError } from "@vitallink/api";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { TextField } from "../components/Form";
import { Panel, Txt } from "../components/kit";
import { Button } from "../components/ui";
import { useApi } from "../data";
import { exportMyData } from "../download";
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
      sessionStorage.removeItem("vl.token");
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
