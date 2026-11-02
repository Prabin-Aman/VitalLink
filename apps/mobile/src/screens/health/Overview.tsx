// My Health · Overview — Mobile 390 (Figma 86:56).
import { loadHealthOverview } from "@vitallink/api";
import { useRouter } from "expo-router";
import { useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { Rings } from "../../components/Charts";
import { HealthChips } from "../../components/HealthChips";
import { Screen, Section } from "../../components/kit";
import { Dot, MetricCard, ScreenState } from "../../components/ui";
import { useScreen } from "../../data";
import { AddReadingSheet } from "../../sheets/HealthSheets";
import { c, t } from "../../theme";

export function HealthOverviewScreen() {
  const state = useScreen(loadHealthOverview);
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  // Mobile lists the watch, the phone and anything needing attention.
  const sources = v.sources.filter((s, i) => i < 2 || s.color !== "rangeNormal");

  return (
    <Screen title="My Health" subtitle={v.shortSubtitle} add={() => setAdding(true)} chips={<HealthChips active="Overview" />}>
      {adding && <AddReadingSheet onClose={() => setAdding(false)} />}
      <View style={st.today} accessibilityLabel="Today's movement">
        <Rings rings={v.rings.map((r) => ({ fraction: r.fraction, color: r.color, label: r.label }))} />
        <View style={st.ringRows}>
          {v.rings.map((r) => (
            <View key={r.label} style={st.ringRow}>
              <Text style={[t("labelS", r.color), st.ringLabel]}>{r.label}</Text>
              <Text style={t("metricM", "textInverse")}>{r.value}</Text>
              <Text style={t("caption", "textTertiary")}>{r.goal}</Text>
            </View>
          ))}
        </View>
      </View>

      {v.mobileSections.map((sec) => (
        <View key={sec.title} style={st.section}>
          <View style={st.head}>
            <Text style={[t("h5", "textPrimary"), st.flex]} accessibilityRole="header">{sec.title}</Text>
            <Pressable accessibilityRole="link" onPress={() => router.replace(sec.to as never)} hitSlop={8}>
              <Text style={t("labelM", "textLink")}>All</Text>
            </Pressable>
          </View>
          <View style={st.grid}>
            {sec.cards.map((m) => (
              <View key={m.key} style={st.cell}>
                <MetricCard m={m} />
              </View>
            ))}
          </View>
        </View>
      ))}

      <Section title="Data sources">
        {sources.map((s) => (
          <View key={s.name} style={st.source}>
            <Dot color={s.color} />
            <View style={st.flex}>
              <Text style={t("bodySStrong", "textPrimary")}>{s.name}</Text>
              <Text style={t("caption", "textTertiary")} numberOfLines={1}>{s.data}</Text>
            </View>
          </View>
        ))}
      </Section>
    </Screen>
  );
}

const st = StyleSheet.create({
  today: { flexDirection: "row", alignItems: "center", gap: 18, padding: 18, borderRadius: 16, backgroundColor: c.bgInverse },
  ringRows: { flex: 1, gap: 8 },
  ringRow: { flexDirection: "row", alignItems: "baseline", gap: 6 },
  ringLabel: { width: 64 },
  section: { gap: 10 },
  head: { flexDirection: "row", alignItems: "center", gap: 8 },
  flex: { flex: 1 },
  grid: { flexDirection: "row", flexWrap: "wrap", gap: 12 },
  cell: { flexBasis: "40%", flexGrow: 1 },
  source: { flexDirection: "row", alignItems: "center", gap: 10 },
});
