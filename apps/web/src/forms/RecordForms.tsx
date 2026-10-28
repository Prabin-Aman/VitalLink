// Upload Document — Dialog 560 (Figma 197:468).
import { checkUpload, RECORD_TYPES, titleFromFile, todayIn, UPLOAD_TYPES } from "@vitallink/api";
import { useState } from "react";
import { DropZone, FormDialog, must, Row, SelectField, TextField, useZone } from "../components/Form";
import { useApi } from "../data";

export function UploadDocumentDialog({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <UploadDocument tz={tz} onClose={onClose} /> : null;
}

function UploadDocument({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const today = todayIn(tz);
  const [file, setFile] = useState<File | null>(null);
  const [f, setF] = useState({ title: "", type: "upload", date: today, source: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  return (
    <FormDialog
      overline="Medical Records › Upload Document"
      title="Upload Document"
      subtitle="Keep a copy of a report, prescription or scan."
      action="Upload"
      onClose={onClose}
      save={async () => {
        const body = must(checkUpload(f, file, today));
        await api.uploadRecord(body, file!);
      }}
    >
      <DropZone
        file={file}
        accept={UPLOAD_TYPES.join(",")}
        onFile={(next) => {
          setFile(next);
          setF((x) => ({ ...x, title: x.title || titleFromFile(next.name) }));
        }}
      />
      <TextField label="Title" value={f.title} onChange={set("title")} placeholder="e.g. Chest X-ray report" />
      <Row>
        <SelectField label="Type" value={f.type} onChange={set("type")} options={RECORD_TYPES} />
        <TextField label="Date" type="date" icon="appointments-16" value={f.date} max={today} onChange={set("date")} />
      </Row>
      <TextField label="Source" value={f.source} onChange={set("source")} placeholder="Clinic or lab (optional)" />
    </FormDialog>
  );
}
