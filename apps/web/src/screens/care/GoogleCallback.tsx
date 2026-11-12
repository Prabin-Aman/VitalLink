// Google Health › connecting (no Figma frame): where Google sends the browser back after the consent page.
// It hands the code to the API, which stores the grant and pulls the last 30 days, then says what was added.
import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Note } from "../../components/Form";
import { Txt } from "../../components/kit";
import { Button } from "../../components/ui";
import { notifyChanged, useApi } from "../../data";
import { syncSummary } from "../../forms/DeviceForms";
import s from "./Devices.module.css";

/** A code works once; React's development double-run must not send it twice. */
const sent = new Set<string>();

export function GoogleCallback() {
  const api = useApi();
  const nav = useNavigate();
  const [params] = useSearchParams();
  const [message, setMessage] = useState("Connecting Google Health and reading the last 30 days. This can take a minute.");
  const [done, setDone] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const code = params.get("code");
    const state = params.get("state");
    const error = params.get("error");
    if (error || !code || !state) {
      setMessage(error === "access_denied" ? "Google Health wasn't connected: access wasn't allowed. You can try again from Add a device." : "Google didn't send VitalLink what it needs. Try connecting again from Add a device.");
      setDone(true);
      return;
    }
    if (sent.has(code)) return;
    sent.add(code);
    // The code must not stay in the address bar or history.
    window.history.replaceState(null, "", window.location.pathname);
    api.finishGoogleHealth(code, state).then(
      (r) => {
        setMessage(r ? `Google Health is connected. ${syncSummary(r)} New readings arrive every night, or press Sync now in Add a device.` : "Connecting needs the live server.");
        notifyChanged();
        setDone(true);
      },
      (e: Error) => {
        setMessage(e.message);
        setDone(true);
      },
    );
  }, [api, params]);

  return (
    <>
      <header className={s.heading}>
        <Txt s="h3" as="h2">Fitbit or Pixel Watch</Txt>
        <Txt s="bodyM" c="textSecondary" as="p">Connecting through Google Health</Txt>
      </header>
      <Note>{message}</Note>
      {done && (
        <span>
          <Button variant="primary" onClick={() => nav("/devices")}>Back to Connected Devices</Button>
        </span>
      )}
    </>
  );
}
