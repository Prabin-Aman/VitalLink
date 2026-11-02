// Records — Mobile 390 (Figma 145:343).
import { loadRecords, type RecordType } from "@vitallink/api";
import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { Icon } from "../components/Icon";
import { ChipRow, Row, Screen, Section, Tile } from "../components/kit";
import { Button, ScreenState } from "../components/ui";
import { useScreen } from "../data";
import { UploadDocumentSheet } from "../sheets/RecordSheets";
import { t } from "../theme";

const CHIPS: { label: string; type: RecordType | null }[] = [
  { label: "All", type: null },
  { label: "Labs", type: "lab" },
  { label: "Prescriptions", type: "prescription" },
  { label: "Imaging", type: "imaging" },
];

export function RecordsScreen() {
  const state = useScreen(loadRecords);
  const [chip, setChip] = useState(CHIPS[0].label);
  const [uploading, setUploading] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const type = CHIPS.find((c) => c.label === chip)?.type ?? null;
  // Mobile shows the five most recent.
  const rows = (type ? v.rows.filter((r) => r.type === type) : v.rows).slice(0, 5);

  return (
    <Screen title="Records" subtitle={v.shortSubtitle} add={() => setUploading(true)} chips={<ChipRow label="Record types" options={CHIPS.map((c) => c.label)} value={chip} onChange={setChip} />}>
      {uploading && <UploadDocumentSheet onClose={() => setUploading(false)} />}
      <Button label="Upload a document" icon="plus-18" onPress={() => setUploading(true)} />
      <Section title="Recent records" gap={8}>
        {rows.length === 0 && <Text style={t("bodyS", "textTertiary")}>No recent records of this type.</Text>}
        {rows.map((r) => (
          <Row key={r.id} pad={11} style={st.gap11}>
            <Tile icon="file-text-16" size={32} />
            <View style={st.flex}>
              <Text style={t("bodySStrong", "textPrimary")}>{r.title}</Text>
              <Text style={t("caption", r.shared ? "goalOnTrack" : "textTertiary")}>
                {r.mobileLine}
                {r.shared ? " · shared" : ""}
              </Text>
            </View>
            <Icon name="chevron-right-16" color="iconSubtle" />
          </Row>
        ))}
      </Section>
      <Section gap={8}>
        <Text style={t("bodySStrong", "textPrimary")}>You own these records</Text>
        <Text style={t("caption", "textTertiary")}>Nothing is added or read without a share you created. Revoke any share from Privacy at any time.</Text>
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  flex: { flex: 1, gap: 2 },
  gap11: { gap: 11 },
});
