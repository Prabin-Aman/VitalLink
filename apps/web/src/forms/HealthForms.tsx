// Add Reading — Dialog 560 (Figma 197:16).
import { checkReading, READING_METRICS, timeIn, todayIn } from "@vitallink/api";
import { useState } from "react";
import { FormDialog, must, Note, Row, SelectField, TextField, useZone } from "../components/Form";
import { useApi } from "../data";

export function AddReadingDialog({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <AddReading tz={tz} onClose={onClose} /> : null;
}

function AddReading({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ metric: "blood_pressure", value: "", value2: "", date: todayIn(tz), time: timeIn(tz) });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const m = READING_METRICS.find((x) => x.value === f.metric)!;

  return (
    <FormDialog
      overline="My Health › Add Reading"
      title="Add Reading"
      subtitle="Log a measurement your devices didn't sync."
      action="Save Reading"
      onClose={onClose}
      save={async () => {
        for (const r of must(checkReading(f, tz))) await api.addReading(r.metric, r.value, r.at);
      }}
    >
      <SelectField label="Measurement" value={f.metric} onChange={set("metric")} options={READING_METRICS} />
      {m.pair ? (
        <Row>
          <TextField label="Systolic" value={f.value} onChange={set("value")} inputMode="numeric" placeholder="118" helper="mmHg" autoFocus />
          <TextField label="Diastolic" value={f.value2} onChange={set("value2")} inputMode="numeric" placeholder="76" helper="mmHg" />
        </Row>
      ) : (
        <TextField label={m.label} value={f.value} onChange={set("value")} inputMode="decimal" helper={m.unit} autoFocus />
      )}
      <Row>
        <TextField label="Date" type="date" icon="appointments-16" value={f.date} max={todayIn(tz)} onChange={set("date")} />
        <TextField label="Time" type="time" icon="clock-16" value={f.time} onChange={set("time")} />
      </Row>
      <Note>Readings you add show in your charts alongside the ones your devices sync.</Note>
    </FormDialog>
  );
}
