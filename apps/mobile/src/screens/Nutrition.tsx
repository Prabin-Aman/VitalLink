// Nutrition — Mobile 390 (Figma 134:216).
import { loadNutrition } from "@vitallink/api";
import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { Rings } from "../components/Charts";
import { BarRow, ChipRow, Row, Screen, Section } from "../components/kit";
import { ScreenState } from "../components/ui";
import { useScreen } from "../data";
import { LogFoodSheet } from "../sheets/FitnessSheets";
import { c, t } from "../theme";

const CHIPS = ["Today", "Week", "Meals", "Recipes", "Water"];

export function NutritionScreen() {
  const state = useScreen(loadNutrition);
  const [tab, setTab] = useState(CHIPS[0]);
  const [logging, setLogging] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <Screen title="Nutrition" subtitle={v.shortSubtitle} add={() => setLogging(true)} back="/train" chips={<ChipRow label="Nutrition sections" options={CHIPS} value={tab} onChange={setTab} />}>
      {logging && <LogFoodSheet onClose={() => setLogging(false)} />}
      {tab !== CHIPS[0] ? (
        <Text style={t("bodyM", "textTertiary")}>{tab} is in a later batch.</Text>
      ) : (
        <>
          <View style={st.calories} accessibilityLabel={`Calories today ${v.ring.value} ${v.ring.goal}, ${v.ring.remaining}`}>
            {/* Calories ring: 84px, 10px stroke. */}
            <Rings size={84} rings={[{ fraction: v.ring.fraction, color: "dataNutrition", label: "Calories" }]} />
            <View style={st.flex}>
              <Text style={t("overline", "textTertiary")}>CALORIES TODAY</Text>
              <View style={st.baseline}>
                <Text style={t("metricXl", "textInverse")}>{v.ring.value}</Text>
                <Text style={t("bodyM", "textTertiary")}>{v.ring.goal}</Text>
              </View>
              <Text style={t("bodyS", "goalAchieved")}>{v.ring.remaining}</Text>
            </View>
          </View>
          <Section title="Macros" gap={12}>
            {v.macros.map((m) => (
              <BarRow key={m.key} row={m.key === "carbs" ? { ...m, label: "Carbs" } : m} />
            ))}
          </Section>
          <Section title="Today's meals">
            {v.meals.map((m) => (
              <Row key={m.id} pad={9}>
                <View style={st.flex}>
                  <Text style={t("bodySStrong", "textPrimary")}>{m.title}</Text>
                  <Text style={t("caption", "textTertiary")} numberOfLines={1}>{m.shortItems}</Text>
                </View>
                <Text style={t("bodySStrong", "textPrimary")}>{m.kcal}</Text>
              </Row>
            ))}
          </Section>
        </>
      )}
    </Screen>
  );
}

const st = StyleSheet.create({
  calories: { flexDirection: "row", alignItems: "center", gap: 16, padding: 18, borderRadius: 16, backgroundColor: c.bgInverse },
  flex: { flex: 1, gap: 2 },
  baseline: { flexDirection: "row", alignItems: "baseline", gap: 5 },
});
