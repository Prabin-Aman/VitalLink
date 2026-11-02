// My Health · Digital Wellbeing — Mobile 390 (Figma 87:374).
import { loadDigital } from "@vitallink/api";
import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { StackBars } from "../../components/Charts";
import { HealthChips } from "../../components/HealthChips";
import { Legend, Screen, Section, StatGrid } from "../../components/kit";
import { ScreenState } from "../../components/ui";
import { useScreen } from "../../data";
import { AddReadingSheet } from "../../sheets/HealthSheets";
import { c, t } from "../../theme";

export function DigitalScreen() {
  const state = useScreen(loadDigital);
  const [adding, setAdding] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <Screen title="Digital Wellbeing" subtitle="Screen time and pickups · iPhone" add={() => setAdding(true)} chips={<HealthChips active="Digital" />}>
      {adding && <AddReadingSheet onClose={() => setAdding(false)} />}
      <Section title="Screen time" gap={14} action={<Text style={t("labelM", "textLink")}>Week</Text>}>
        <View style={st.baseline}>
          <Text style={t("metricL", "textPrimary")}>{v.average}</Text>
          <Text style={t("caption", "rangeNormal")}>{v.shortChange}</Text>
        </View>
        {/* 15.5px per hour (plot 87:374). */}
        <StackBars label="Screen time by day" scale={15.5} days={v.days.map((d) => ({ label: d.label[0], segments: d.segments }))} />
        <Legend items={[{ label: "Daytime", color: "dataNutrition" }, { label: "After 10 PM", color: "rangeBorderline" }]} />
      </Section>

      <StatGrid stats={v.mobileStats} />

      <Section title="Screen time vs deep sleep">
        <Text style={t("bodyS", "textSecondary")}>{v.shortDeepSleep}</Text>
        <View style={st.compare}>
          <View style={[st.tile, { backgroundColor: c.rangeHighBg }]}>
            <Text style={t("caption", "textSecondary")}>Heavy late use</Text>
            <Text style={t("metricM", "rangeHigh")}>{v.deepSleep.heavy}</Text>
          </View>
          <View style={[st.tile, { backgroundColor: c.rangeNormalBg }]}>
            <Text style={t("caption", "textSecondary")}>Light late use</Text>
            <Text style={t("metricM", "rangeNormal")}>{v.deepSleep.light}</Text>
          </View>
        </View>
        <Text style={t("caption", "textTertiary")}>An association in your own data, not a clinical finding.</Text>
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  baseline: { flexDirection: "row", alignItems: "baseline", gap: 6 },
  compare: { flexDirection: "row", gap: 10 },
  tile: { flex: 1, padding: 10, gap: 3, borderRadius: 8 },
});
