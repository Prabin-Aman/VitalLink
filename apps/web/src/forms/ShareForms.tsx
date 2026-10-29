// Share your data (Figma 206:1452) and Manage access (206:1516) — Dialog 560.
import { checkShare, loadManageAccess, SHARE_ENDS, SHARE_KINDS, SHARE_SCOPES, todayIn } from "@vitallink/api";
import { useCallback, useState } from "react";
import { Choices, FormDialog, ItemList, must, Note, Row, TextField, useZone } from "../components/Form";
import { Button } from "../components/ui";
import { notifyChanged, useApi, useScreen } from "../data";

export type SharePreset = { kind?: "doctor" | "family" | "other"; scopes?: string[]; overline?: string; note?: string };

export function ShareDialog({ preset, onClose }: { preset?: SharePreset; onClose: () => void }) {
  const tz = useZone();
  return tz ? <Share tz={tz} preset={preset} onClose={onClose} /> : null;
}

function Share({ tz, preset, onClose }: { tz: string; preset?: SharePreset; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({
    label: "",
    email: "",
    kind: preset?.kind ?? "doctor",
    scopes: preset?.scopes ?? ["vitals"],
    ends: "90",
    until: "",
    purpose: "",
  });
  const set = (k: "label" | "email" | "until" | "purpose") => (v: string) => setF((x) => ({ ...x, [k]: v }));

  return (
    <FormDialog
      overline={preset?.overline ?? "Privacy Centre › Share"}
      title="Share your data"
      subtitle="Give someone read-only access to the parts of your record you choose."
      action="Share"
      onClose={onClose}
      save={async () => {
        await api.createShare(must(checkShare(f, tz)));
      }}
    >
      <Row>
        <TextField label="Name" value={f.label} onChange={set("label")} placeholder="e.g. Dr. Laura Bennett" autoFocus />
        <TextField label="Their email" type="email" value={f.email} onChange={set("email")} placeholder="name@example.com" autoComplete="off" />
      </Row>
      <Choices label="Relationship" options={SHARE_KINDS} selected={[f.kind]} onChange={([kind]) => setF((x) => ({ ...x, kind: kind as typeof x.kind }))} />
      <Choices label="What they can see" multiple options={SHARE_SCOPES} selected={f.scopes} onChange={(scopes) => setF((x) => ({ ...x, scopes }))} />
      <Choices label="Access ends" options={SHARE_ENDS} selected={[f.ends]} onChange={([ends]) => setF((x) => ({ ...x, ends }))} />
      {f.ends === "date" && <TextField label="Last day they can see it" type="date" icon="appointments-16" value={f.until} min={todayIn(tz)} onChange={set("until")} />}
      <TextField label="Why (optional)" value={f.purpose} onChange={set("purpose")} placeholder="e.g. Cardiology follow-up" />
      {preset?.note && <Note>{preset.note}</Note>}
      <Note>
        They see only what you pick, read-only, once they sign in to VitalLink with this email. Every time they look shows in your access history, and you can stop sharing any time.
      </Note>
    </FormDialog>
  );
}

export function ManageAccessDialog({ kind, onClose, onShare }: { kind: "doctor" | "family" | null; onClose: () => void; onShare: () => void }) {
  const api = useApi();
  const state = useScreen(useCallback((a) => loadManageAccess(a, kind), [kind]));
  const [error, setError] = useState<string | null>(null);
  if (state.status !== "ready") return null;
  const v = state.data;
  const stop = (id: string, who: string) => {
    if (!confirm(`Stop sharing with ${who}? They lose access straight away.`)) return;
    setError(null);
    api.revokeShare(id).then(
      () => (api.isMock ? setError("This is demo data, so nothing changed.") : notifyChanged()),
      (e: Error) => setError(e.message),
    );
  };
  return (
    <FormDialog overline={`Privacy Centre › ${v.title}`} title="Manage access" subtitle="People who can see parts of your record right now." onClose={onClose}>
      <ItemList
        label={v.title}
        empty={v.empty ?? ""}
        items={v.rows.map((r) => ({ key: r.id, title: r.who, caption: `${r.line} · ${r.until}`, onRemove: () => stop(r.id, r.who), removeLabel: "Stop sharing" }))}
        add={
          <span>
            <Button variant="tertiary" size="sm" onClick={onShare}>
              + Share with someone
            </Button>
          </span>
        }
      />
      {error && <Note>{error}</Note>}
      <Note>Stopping a share takes effect immediately. What they already viewed stays in your access history.</Note>
    </FormDialog>
  );
}
