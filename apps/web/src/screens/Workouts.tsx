// Workouts — Desktop 1440 (Figma 129:1142); the other tabs are in WorkoutTabs.tsx (Figma 195:2).
import { loadWorkouts } from "@vitallink/api";
import { useState } from "react";
import { StackedBars } from "../components/Charts";
import { Action, BarRow, HeadLink, IconTile, Legend, PageHeader, Panel, PillTabs, Split, StatRow, StatusPill, Txt } from "../components/kit";
import { Button, Segmented } from "../components/ui";
import { useScreen } from "../data";
import { WORKOUT_ICON } from "./Activity";
import { ScreenState } from "./ScreenState";
import s from "./Lists.module.css";
import { LogWorkoutDialog } from "../forms/FitnessForms";
import { ExerciseLibraryTab, HistoryTab, PlansTab, RecordsTab } from "./WorkoutTabs";

const TABS = ["Overview", "History", "Training Plans", "Exercise Library", "Personal Records"];

export function Workouts() {
  const [form, setForm] = useState(false);
  const state = useScreen(loadWorkouts);
  const [tab, setTab] = useState(TABS[0]);
  const [range, setRange] = useState("Week");
  if (state.status !== "ready") return <ScreenState state={state} />;
  const w = state.data;

  return (
    <>
      <PageHeader
        title="Workouts"
        subtitle={w.subtitle}
        actions={
          <>
            <Action onClick={() => setTab("Exercise Library")}>Browse Exercises</Action>
            <Action primary onClick={() => setForm(true)}>Log Workout</Action>
            {form && <LogWorkoutDialog onClose={() => setForm(false)} />}
          </>
        }
      />
      <PillTabs label="Workout sections" tabs={TABS} value={tab} onChange={setTab} />
      {tab === "History" ? (
        <HistoryTab />
      ) : tab === "Training Plans" ? (
        <PlansTab />
      ) : tab === "Exercise Library" ? (
        <ExerciseLibraryTab />
      ) : tab === "Personal Records" ? (
        <RecordsTab />
      ) : (
        <>
          <StatRow stats={w.stats} />
          <Split
            rail={
              <>
                <Panel title="Muscle group balance" level="h5" gap={12}>
                  <Txt s="caption" c="textTertiary">{w.muscles.caption}</Txt>
                  {w.muscles.groups.map((g) => (
                    <BarRow key={g.key} row={g} height={7} />
                  ))}
                  <Txt s="caption" c="textTertiary" as="p">{w.muscles.note}</Txt>
                </Panel>
                <Panel title="Recent personal records" level="h5" gap={10}>
                  {w.records.map((r) => (
                    <div key={r.name} className={s.pr}>
                      <div className={s.stack1}>
                        <Txt s="bodySStrong">{r.name}</Txt>
                        <Txt s="caption" c="textTertiary">{r.when}</Txt>
                      </div>
                      <Txt s="numeric" c="goalAchieved">{r.value}</Txt>
                    </div>
                  ))}
                </Panel>
                {w.next && (
                  <Panel title="Next planned session" level="h5" gap={10}>
                    <Txt s="bodyMStrong">{w.next.title}</Txt>
                    <Txt s="caption" c="textTertiary">{w.next.detail}</Txt>
                    <Button className={s.wide}>Start Session</Button>
                  </Panel>
                )}
              </>
            }
          >
            <Panel
              title="Training load this week"
              caption="Minutes per day, split by session type"
              gap={16}
              action={<Segmented label="Training load range" options={["Week", "Month", "Year"]} value={range} onChange={setRange} wide />}
            >
              <StackedBars
                label="Training minutes"
                scale={2.15}
                days={w.load.map((d) => ({ label: d.label, total: d.total, rest: d.rest, segments: d.segments.map((x) => ({ value: x.minutes, color: x.color })) }))}
              />
              <Legend items={w.legend} />
            </Panel>

            <Panel title="Recent sessions" action={<HeadLink onClick={() => setTab("History")}>View all history</HeadLink>}>
              <ul className={s.list}>
                {w.sessions.map((x) => (
                  <li key={x.id} className={s.row}>
                    <IconTile icon={WORKOUT_ICON[x.icon]} color={x.iconColor} />
                    <div className={s.text}>
                      <Txt s="bodyMStrong">{x.title}</Txt>
                      <Txt s="caption" c="textTertiary">{x.caption}</Txt>
                    </div>
                    <Txt s="numeric" className={s.c80}>{x.duration}</Txt>
                    <Txt s="table" c="textSecondary" className={s.c140}>{x.metric}</Txt>
                    <span className={s.c96}>
                      <StatusPill label={x.zone} color="textSecondary" dot={false} />
                    </span>
                  </li>
                ))}
              </ul>
            </Panel>
          </Split>
        </>
      )}
    </>
  );
}
