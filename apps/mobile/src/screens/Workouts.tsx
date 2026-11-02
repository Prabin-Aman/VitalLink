// Workouts — Mobile 390 (Figma 134:2).
import { loadWorkouts } from "@vitallink/api";
import { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { ChipRow, Row, Screen, Section, StatGrid, Tile } from "../components/kit";
import { Button, ScreenState } from "../components/ui";
import { useScreen } from "../data";
import { LogWorkoutSheet } from "../sheets/FitnessSheets";
import { t } from "../theme";
import { WORKOUT_ICON_18 } from "./Activity";

const CHIPS = ["Overview", "History", "Plans", "Exercises", "Records"];

export function WorkoutsScreen() {
  const state = useScreen(loadWorkouts);
  const [tab, setTab] = useState(CHIPS[0]);
  const [logging, setLogging] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <Screen title="Workouts" subtitle={v.shortSubtitle} add={() => setLogging(true)} chips={<ChipRow label="Workout sections" options={CHIPS} value={tab} onChange={setTab} />}>
      {logging && <LogWorkoutSheet onClose={() => setLogging(false)} />}
      {tab !== CHIPS[0] ? (
        <Text style={t("bodyM", "textTertiary")}>{tab} is in a later batch.</Text>
      ) : (
        <>
          <Button label="Start a Workout" icon="dumbbell-18" onPress={() => setLogging(true)} />
          <StatGrid stats={v.mobileStats} />
          <Section title="Recent sessions">
            {v.sessions.slice(0, 4).map((x) => (
              <Row key={x.id}>
                <Tile icon={WORKOUT_ICON_18[x.icon]} color={x.iconColor} />
                <View style={st.flex}>
                  <Text style={t("bodySStrong", "textPrimary")}>{x.title}</Text>
                  <Text style={t("caption", "textTertiary")} numberOfLines={1}>{x.summary}</Text>
                </View>
              </Row>
            ))}
          </Section>
        </>
      )}
    </Screen>
  );
}

const st = StyleSheet.create({ flex: { flex: 1, gap: 2 } });
