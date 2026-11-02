import { loadActivity, type WorkoutType } from "@vitallink/api";
import { useState } from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { Header } from "../components/Header";
import { HealthChips } from "../components/HealthChips";
import { Icon, type IconName } from "../components/Icon";
import { StepsChart } from "../components/StepsChart";
import { AddPill } from "../components/kit";
import { Card, Dot, ScreenState } from "../components/ui";
import { useScreen } from "../data";
import { AddReadingSheet } from "../sheets/HealthSheets";
import { c, t } from "../theme";

export const WORKOUT_ICON_18: Record<WorkoutType, IconName> = {
  walk: "walk-18",
  run: "run-18",
  cycle: "bike-18",
  strength: "dumbbell-18",
  swim: "health-16",
  hiit: "dumbbell-18",
  yoga: "health-16",
  other: "health-16",
};
// Mobile shows four of the five weekly stats.
const MOBILE_STATS = ["distance", "flights", "energy", "goal-days"];

export function ActivityScreen() {
  const state = useScreen(loadActivity);
  const [adding, setAdding] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const a = state.data;

  return (
    <View style={st.screen}>
      <Header
        title="Activity"
        subtitle="Steps, workouts and movement · Apple Watch"
        trailing={<AddPill onPress={() => setAdding(true)} />}
      >
        <HealthChips active="Activity" />
      </Header>

      {adding && <AddReadingSheet onClose={() => setAdding(false)} />}
      <ScrollView contentContainerStyle={st.content}>
        <Card style={st.steps}>
          <View style={st.between}>
            <Text style={t("h5", "textPrimary")}>Steps this week</Text>
            <Text style={t("labelM", "textLink")}>Week</Text>
          </View>
          <View style={st.baseline}>
            <Text style={t("metricL", "textPrimary")}>{a.total}</Text>
            <Text style={t("caption", "textTertiary")}>{a.dailyAverage} daily avg</Text>
          </View>
          <View style={st.plot}>
            <StepsChart bars={a.bars} goal={a.goal} />
          </View>
        </Card>

        <View style={st.grid}>
          {a.stats
            .filter((s) => MOBILE_STATS.includes(s.key))
            .map((s) => (
              <Card key={s.key} style={st.stat}>
                <View style={st.row}>
                  <Dot color={s.accent} />
                  <Text style={t("labelM", "textSecondary")}>{s.label}</Text>
                </View>
                <View style={st.baseline}>
                  <Text style={t("metricM", "textPrimary")}>{s.value}</Text>
                  <Text style={t("metricUnit", "textTertiary")}>{s.unit}</Text>
                </View>
                <Text style={t("caption", "textTertiary")}>{s.caption}</Text>
              </Card>
            ))}
        </View>

        {a.workouts && (
          <Card style={st.workouts}>
            <Text style={[t("h5", "textPrimary"), st.cardTitle]}>Workouts this week</Text>
            {a.workouts.map((w) => (
              <View key={w.id} style={st.workout}>
                <View style={st.kind}>
                  <Icon name={WORKOUT_ICON_18[w.icon]} color={w.iconColor} />
                </View>
                <View style={st.flex}>
                  <Text style={t("bodySStrong", "textPrimary")}>{w.title}</Text>
                  <Text style={t("caption", "textTertiary")} numberOfLines={1}>
                    {w.summary}
                  </Text>
                </View>
              </View>
            ))}
          </Card>
        )}
      </ScrollView>
    </View>
  );
}

const st = StyleSheet.create({
  screen: { flex: 1, backgroundColor: c.bgCanvas },
  content: { padding: 16, gap: 16 },
  steps: { padding: 16, gap: 8 },
  between: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  baseline: { flexDirection: "row", alignItems: "baseline", gap: 6 },
  plot: { marginTop: 6 },
  grid: { flexDirection: "row", flexWrap: "wrap", gap: 12 },
  stat: { flexBasis: "40%", flexGrow: 1, padding: 16, gap: 6 },
  row: { flexDirection: "row", alignItems: "center", gap: 5 },
  workouts: { padding: 16, gap: 0 },
  workout: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 14, borderTopWidth: 1, borderTopColor: c.borderSubtle },
  kind: { width: 34, height: 34, borderRadius: 9, backgroundColor: c.bgSubtle, alignItems: "center", justifyContent: "center" },
  flex: { flex: 1 },
  cardTitle: { marginBottom: 12 },
});
