// Add Reading — Mobile sheet (Figma 198:317 › Sheet / Add Reading).
import { checkReading, READING_METRICS, timeIn, todayIn } from "@vitallink/api";
import { useState } from "react";
import { useApi } from "../data";
import { DateField, Field, FormSheet, must, Note, PickField, Row, TimeField, useZone } from "../components/Sheet";

export function AddReadingSheet({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <AddReading tz={tz} onClose={onClose} /> : null;
}

function AddReading({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ metric: "blood_pressure", value: "", value2: "", date: todayIn(tz), time: timeIn(tz) });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const m = READING_METRICS.find((x) => x.value === f.metric)!;

  return (
    <FormSheet
      overline="Health › Add"
      title="Add Reading"
      subtitle="Log a measurement your devices didn't sync."
      action="Save Reading"
      onClose={onClose}
      save={async () => {
        for (const r of must(checkReading(f, tz))) await api.addReading(r.metric, r.value, r.at);
      }}
    >
      <PickField label="Measurement" value={f.metric} onChange={set("metric")} options={READING_METRICS} />
      {m.pair ? (
        <Row>
          <Field label="Systolic" value={f.value} onChange={set("value")} keyboard="number-pad" placeholder="118" helper="mmHg" />
          <Field label="Diastolic" value={f.value2} onChange={set("value2")} keyboard="number-pad" placeholder="76" helper="mmHg" />
        </Row>
      ) : (
        <Field label={m.label} value={f.value} onChange={set("value")} keyboard="decimal-pad" helper={m.unit} />
      )}
      <Row>
        <DateField label="Date" value={f.date} onChange={set("date")} tz={tz} past />
        <TimeField label="Time" value={f.time} onChange={set("time")} />
      </Row>
      <Note>Readings you add show in your charts alongside the ones your devices sync.</Note>
    </FormSheet>
  );
}
