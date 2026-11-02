// Goals — Mobile 390 (Figma 134:395).
import { loadGoals } from "@vitallink/api";
import { useState } from "react";
import { Text } from "react-native";
import { BarRow, ChipRow, Screen, Section, StatGrid } from "../components/kit";
import { ScreenState } from "../components/ui";
import { useScreen } from "../data";
import { NewGoalSheet } from "../sheets/FitnessSheets";
import { t } from "../theme";

const CHIPS = ["Active", "Achieved", "Paused", "All"];

export function GoalsScreen() {
  const state = useScreen(loadGoals);
  const [tab, setTab] = useState(CHIPS[0]);
  const [adding, setAdding] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  // Active lists goals still in progress; "Achieved" and "Paused" filter by status.
  const rows = tab === "All" ? v.goals : tab === "Active" ? v.goals.filter((g) => g.status !== "Achieved" && g.status !== "Paused") : v.goals.filter((g) => g.status === tab);

  return (
    <Screen title="Goals" subtitle={v.shortSubtitle} add={() => setAdding(true)} back="/train" chips={<ChipRow label="Goal filter" options={CHIPS} value={tab} onChange={setTab} />}>
      {adding && <NewGoalSheet onClose={() => setAdding(false)} />}
      <StatGrid stats={v.mobileStats} />
      <Section title={`${tab} goals`} gap={12}>
        {rows.length === 0 ? (
          <Text style={t("bodyS", "textTertiary")}>No {tab.toLowerCase()} goals.</Text>
        ) : (
          rows.map((g) => <BarRow key={g.id} row={{ key: g.id, label: g.shortTitle, value: g.caption, shortValue: g.shortCaption, fraction: g.fraction, color: g.color }} />)
        )}
      </Section>
    </Screen>
  );
}
