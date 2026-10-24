// Medications — Mobile 390 (Figma 150:2).
import { loadMedications } from "@vitallink/api";
import { StyleSheet, Text, View } from "react-native";
import { Pill, Row, Screen, Section, SmallButton, StatGrid } from "../components/kit";
import { ScreenState } from "../components/ui";
import { useApi, useReloadableScreen } from "../data";
import { t } from "../theme";

export function MedicationsScreen() {
  const api = useApi();
  const [state, reload] = useReloadableScreen(loadMedications);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const markTaken = (id: string, at: string) => {
    api.logDose(id, at, "taken").then(reload, () => undefined);
  };

  return (
    <Screen title="Medications" subtitle={v.shortSubtitle} back="/profile">
      <StatGrid stats={v.mobileStats} />
      <Section title="Today's doses" gap={8}>
        {v.rows.map((r) => (
          <Row key={r.id} pad={11} style={st.dose}>
            <View style={st.head}>
              <View style={st.flex}>
                <Text style={t("bodySStrong", "textPrimary")}>{r.name}</Text>
                <Text style={t("caption", "textTertiary")}>{r.summary}</Text>
              </View>
              <Pill chip={r.status} />
            </View>
            <SmallButton label={r.mobileAction} onPress={r.canMarkTaken ? () => markTaken(r.id, r.doseAt) : undefined} />
          </Row>
        ))}
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  dose: { flexDirection: "column", alignItems: "stretch", gap: 9 },
  head: { flexDirection: "row", alignItems: "center", gap: 10 },
  flex: { flex: 1, gap: 2 },
});
