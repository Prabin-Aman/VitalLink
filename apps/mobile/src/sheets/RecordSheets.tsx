// Upload Document — Mobile sheet (Figma 198:317 › Sheet / Upload Document).
import { checkUpload, RECORD_TYPES, titleFromFile, todayIn, UPLOAD_TYPES } from "@vitallink/api";
import { getDocumentAsync } from "expo-document-picker";
import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { useApi } from "../data";
import { c, t } from "../theme";
import { Icon } from "../components/Icon";
import { SmallButton } from "../components/kit";
import { DateField, Field, FormSheet, must, PickField, Row, useZone } from "../components/Sheet";

type Picked = { uri: string; name: string; type: string; size: number; file?: File };

export function UploadDocumentSheet({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <UploadDocument tz={tz} onClose={onClose} /> : null;
}

function UploadDocument({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const today = todayIn(tz);
  const [file, setFile] = useState<Picked | null>(null);
  const [f, setF] = useState({ title: "", type: "upload", date: today, source: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  const choose = async () => {
    const r = await getDocumentAsync({ type: UPLOAD_TYPES, copyToCacheDirectory: true, multiple: false });
    const a = r.canceled ? null : r.assets[0];
    if (!a) return;
    setFile({ uri: a.uri, name: a.name, type: a.mimeType ?? "", size: a.size ?? 0, file: a.file });
    setF((x) => ({ ...x, title: x.title || titleFromFile(a.name) }));
  };

  return (
    <FormSheet
      overline="Records › Add"
      title="Upload Document"
      subtitle="Keep a copy of a report, prescription or scan."
      action="Upload"
      onClose={onClose}
      save={async () => {
        const body = must(checkUpload(f, file, today));
        // On the web build the picker hands back the File; on a phone, read the cached copy as a Blob.
        const blob = file!.file ?? (await (await fetch(file!.uri)).blob());
        await api.uploadRecord(body, blob, file!.name);
      }}
    >
      <View style={st.drop}>
        <Icon name="file-text-20" color="iconDefault" />
        <Text style={t("bodyMStrong", "textPrimary")} numberOfLines={1}>
          {file ? file.name : "Take a photo or choose a file"}
        </Text>
        <Text style={t("caption", "textTertiary")}>{file ? `${(file.size / 1024 / 1024).toFixed(1)} MB` : "PDF, JPG or PNG · up to 25 MB"}</Text>
        <SmallButton label={file ? "Choose another" : "Choose File"} onPress={choose} />
      </View>
      <Field label="Title" value={f.title} onChange={set("title")} placeholder="e.g. Chest X-ray report" />
      <Row>
        <PickField label="Type" value={f.type} onChange={set("type")} options={RECORD_TYPES} />
        <DateField label="Date" value={f.date} onChange={set("date")} tz={tz} past />
      </Row>
    </FormSheet>
  );
}

const st = StyleSheet.create({
  drop: {
    alignItems: "center",
    gap: 6,
    paddingVertical: 20,
    paddingHorizontal: 16,
    borderWidth: 1,
    borderStyle: "dashed",
    borderColor: c.borderStrong,
    borderRadius: 12,
    backgroundColor: c.bgCanvas,
  },
});
