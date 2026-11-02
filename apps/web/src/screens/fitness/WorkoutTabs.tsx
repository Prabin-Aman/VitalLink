// Workouts tabs (Figma 195:2 "Web · Proposed tabs and sections"): History (203:622), Training Plans
// (203:1226), Exercise Library (203:1805), Personal Records (203:2373).
import {
  filterExercises,
  HISTORY_RANGES,
  HISTORY_TYPES,
  loadExerciseLibrary,
  loadPersonalRecords,
  loadTrainingPlans,
  loadWorkoutHistory,
} from "@vitallink/api";
import { useCallback, useState } from "react";
import { Choices, TextField } from "../../components/Form";
import { IconTile, Panel, Split, StatRow, StatusPill, Txt } from "../../components/kit";
import { Button } from "../../components/ui";
import { notifyChanged, useApi, useScreen } from "../../data";
import { WORKOUT_ICON } from "../health/Activity";
import { LogExerciseDialog } from "../../forms/FitnessForms";
import { ScreenState } from "../ScreenState";
import s from "../Lists.module.css";
import t from "./WorkoutTabs.module.css";

export function HistoryTab() {
  const [days, setDays] = useState("90");
  const [type, setType] = useState("all");
  const state = useScreen(useCallback((api) => loadWorkoutHistory(api, Number(days), type), [days, type]));
  const api = useApi();
  const [busy, setBusy] = useState<string | null>(null);
  const remove = (id: string, title: string) => {
    if (!confirm(`Delete ${title}? It comes out of your totals, streaks and personal records.`)) return;
    setBusy(id);
    api
      .deleteWorkout(id)
      .then(
        () => (api.isMock ? alert("This is demo data, so nothing was deleted.") : notifyChanged()),
        (e: Error) => alert(e.message),
      )
      .finally(() => setBusy(null));
  };
  return (
    <>
      <div className={t.filters}>
        <Choices label="Period" hideLabel options={HISTORY_RANGES} selected={[days]} onChange={([v]) => setDays(v)} />
        <span className={t.divider} aria-hidden />
        <Choices label="Workout type" hideLabel options={HISTORY_TYPES} selected={[type]} onChange={([v]) => setType(v)} />
      </div>
      {state.status !== "ready" ? (
        <ScreenState state={state} />
      ) : (
        <>
          <StatRow stats={state.data.stats} />
          <Panel title="All sessions">
            {state.data.empty && <Txt s="bodyM" c="textTertiary" as="p">{state.data.empty}</Txt>}
            {state.data.groups.map((g) => (
              <section key={g.label} aria-label={g.label}>
                <Txt s="overline" c="textTertiary" as="h4" className={t.group}>{g.label}</Txt>
                <ul className={s.list}>
                  {g.rows.map((x) => (
                    <li key={x.id} className={s.row}>
                      <IconTile icon={WORKOUT_ICON[x.icon]} color={x.iconColor} />
                      <div className={s.text}>
                        <Txt s="bodyMStrong">{x.title}</Txt>
                        <Txt s="caption" c="textTertiary">{x.caption}</Txt>
                      </div>
                      <Txt s="numeric" className={s.c80}>{x.duration}</Txt>
                      <Txt s="table" c="textSecondary" className={s.c140}>{x.metric}</Txt>
                      <Button variant="tertiary" size="sm" disabled={busy === x.id} onClick={() => remove(x.id, x.title)} aria-label={`Delete ${x.title}`}>
                        Delete
                      </Button>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </Panel>
        </>
      )}
    </>
  );
}

export function PlansTab() {
  const state = useScreen(loadTrainingPlans);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  return (
    <Split
      rail={
        <Panel title="How plans work" level="h5" gap={10}>
          <Txt s="bodyS" c="textSecondary" as="p">
            A plan sets which sessions you aim for each week. Logged workouts count towards it, and Goals tracks the weekly count.
          </Txt>
        </Panel>
      }
    >
      {v.empty && (
        <Panel title="Training plans">
          <Txt s="bodyM" c="textTertiary" as="p">{v.empty}</Txt>
        </Panel>
      )}
      {v.plans.map((p) => (
        <Panel key={p.id} title={p.title} caption={p.caption}>
          {p.groups.map((g) => (
            <section key={g.label} aria-label={g.label}>
              <Txt s="overline" c="textTertiary" as="h4" className={t.group}>{g.label}</Txt>
              <ul className={s.list}>
                {g.rows.map((x) => (
                  <li key={x.id} className={s.row}>
                    <IconTile icon={WORKOUT_ICON[x.icon]} color={x.iconColor} />
                    <div className={s.text}>
                      <Txt s="bodyMStrong">{x.title}</Txt>
                      <Txt s="caption" c="textTertiary">{x.caption}</Txt>
                    </div>
                    <Txt s="numeric" className={s.c80}>{x.minutes}</Txt>
                    <span className={s.c96}>
                      <StatusPill label={x.status} color={x.status === "Next" ? "textBrand" : "textSecondary"} dot={false} />
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </Panel>
      ))}
    </Split>
  );
}

export function ExerciseLibraryTab() {
  const state = useScreen(loadExerciseLibrary);
  const [logging, setLogging] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [muscle, setMuscle] = useState("all");
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const shown = filterExercises(v, query, muscle);
  return (
    <>
      <div className={t.searchRow}>
        <div className={t.search}>
          <TextField label="Search exercises" icon="search-16" value={query} onChange={setQuery} placeholder="e.g. squat, row, plank" />
        </div>
        <Choices label="Muscle group" hideLabel options={v.muscles} selected={[muscle]} onChange={([m]) => setMuscle(m)} />
      </div>
      {shown.length === 0 ? (
        <Txt s="bodyM" c="textTertiary" as="p">No exercises match.</Txt>
      ) : (
        <ul className={t.cards}>
          {shown.map((e) => (
            <li key={e.id}>
              <button type="button" className={t.card} onClick={() => setLogging(e.name)} aria-label={`Log ${e.name}`}>
                <IconTile icon="dumbbell-20" color="fitnessStrength" />
                <span className={t.cardText}>
                  <Txt s="bodyMStrong">{e.name}</Txt>
                  <Txt s="caption" c="textTertiary">{e.muscle}</Txt>
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <Txt s="caption" c="textTertiary" as="p">
        {shown.length === v.exercises.length ? v.count : `${shown.length} of ${v.count}`} · select one to log it
      </Txt>
      {logging && <LogExerciseDialog exercise={logging} onClose={() => setLogging(null)} />}
    </>
  );
}

export function RecordsTab() {
  const state = useScreen(loadPersonalRecords);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  return (
    <>
      <StatRow stats={v.stats} />
      <Panel title="All personal records" gap={0}>
        {v.rows.length === 0 && <Txt s="bodyM" c="textTertiary" as="p">Records appear once you log sets with a weight or a run of 5 km or more.</Txt>}
        <ul className={t.records}>
          {v.rows.map((r) => (
            <li key={r.name} className={t.record}>
              <span className={s.stack1}>
                <Txt s="bodySStrong">{r.name}</Txt>
                <Txt s="caption" c="textTertiary">{r.when}</Txt>
              </span>
              <Txt s="numeric" c="goalAchieved">{r.value}</Txt>
            </li>
          ))}
        </ul>
      </Panel>
    </>
  );
}
