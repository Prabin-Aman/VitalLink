import { loadDashboard } from "@vitallink/api";
import { useRouter } from "expo-router";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { Header } from "../components/Header";
import { Button, Card, MetricCard, ScoreRing, ScreenState } from "../components/ui";
import { useScreen } from "../data";
import { c, t } from "../theme";

// Mobile shows four vitals (Figma 16:2); weight lives on the Health tab.
const MOBILE_METRICS = ["heart_rate", "blood_pressure", "oxygen_saturation", "glucose"];

export function HomeScreen() {
  const state = useScreen(loadDashboard);
  const router = useRouter();
  if (state.status !== "ready") return <ScreenState state={state} />;
  const d = state.data;
  const metrics = d.metrics.filter((m) => MOBILE_METRICS.includes(m.key));

  return (
    <View style={st.screen}>
      <Header
        title={`${d.greeting}, ${d.firstName}`}
        titleStyle="h4"
        subtitle={d.dateLabel}
        trailing={
          <View style={st.avatar} accessibilityLabel={`Profile: ${d.fullName}`}>
            <Text style={[t("labelL", "textOnBrand"), { fontFamily: "Inter_600SemiBold" }]}>{d.initials}</Text>
          </View>
        }
      />
      <ScrollView contentContainerStyle={st.content}>
        {d.score !== null && (
          <View style={st.score} accessibilityLabel={`Health score ${d.score}, ${d.scoreLabel}`}>
            <ScoreRing score={d.score} />
            <View style={st.scoreText}>
              <Text style={t("overline", "textTertiary")}>HEALTH SCORE</Text>
              <View style={st.baseline}>
                <Text style={t("metricXl", "textInverse")}>{d.score}</Text>
                <Text style={t("bodyM", "textTertiary")}>/ 100</Text>
              </View>
              <Text style={t("bodyS", "rangeNormal")}>
                {d.scoreLabel} · {d.scoreDelta >= 0 ? "up" : "down"} {Math.abs(d.scoreDelta)} this week
              </Text>
            </View>
          </View>
        )}

        <View style={st.grid}>
          {metrics.map((m) => (
            <View key={m.key} style={st.cell}>
              <MetricCard m={m} />
            </View>
          ))}
        </View>

        <Button label="Start a Workout" icon="dumbbell-18" onPress={() => router.push("/train")} />

        {d.medications && (
          <Card style={st.meds}>
            <Text style={t("h5", "textPrimary")}>Today's Medications</Text>
            <View style={st.medList}>
              {d.medications.map((m) => (
                <View key={m.id} style={st.med}>
                  <View style={[st.medDot, { backgroundColor: c[m.taken ? "rangeNormal" : "bgMuted"] }]} />
                  <View>
                    <Text style={t("bodySStrong", m.taken ? "textTertiary" : "textPrimary")}>{m.title}</Text>
                    <Text style={t("caption", "textTertiary")}>{m.detail}</Text>
                  </View>
                </View>
              ))}
            </View>
          </Card>
        )}
      </ScrollView>
    </View>
  );
}

const st = StyleSheet.create({
  screen: { flex: 1, backgroundColor: c.bgCanvas },
  content: { padding: 16, gap: 16 },
  avatar: { width: 36, height: 36, borderRadius: 18, backgroundColor: c.bgBrand, alignItems: "center", justifyContent: "center" },
  score: { flexDirection: "row", alignItems: "center", gap: 16, padding: 18, borderRadius: 16, backgroundColor: c.bgInverse },
  scoreText: { gap: 2 },
  baseline: { flexDirection: "row", alignItems: "baseline", gap: 6 },
  grid: { flexDirection: "row", flexWrap: "wrap", gap: 12 },
  cell: { flexBasis: "40%", flexGrow: 1 },
  meds: { padding: 16, gap: 16 },
  medList: { gap: 11 },
  med: { flexDirection: "row", alignItems: "center", gap: 10 },
  medDot: { width: 18, height: 18, borderRadius: 9 },
});
