// Nutrition tabs (Figma 195:2 "Web · Proposed tabs and sections"): Week (204:1068), Meals (204:1545),
// Hydration (204:1973).
import { loadHydration, loadMealsDay, loadNutritionWeek } from "@vitallink/api";
import { useCallback, useState } from "react";
import { GoalBars } from "../components/Charts";
import { Choices } from "../components/Form";
import { IconTile, KeyValues, Panel, Split, StatRow, Txt } from "../components/kit";
import { Button } from "../components/ui";
import { notifyChanged, useApi, useScreen } from "../data";
import { ScreenState } from "./ScreenState";
import s from "./Lists.module.css";

export function WeekTab() {
  const state = useScreen(loadNutritionWeek);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  return (
    <>
      <StatRow stats={v.stats} />
      <Split
        rail={
          <Panel title="This week" level="h5" gap={12}>
            <KeyValues rows={v.totals} />
          </Panel>
        }
      >
        <Panel title="Calories this week" caption={v.caption}>
          <GoalBars bars={v.bars} goalFraction={v.goalFraction} color="goalOnTrack" label="Calories by day" />
        </Panel>
        <Panel title="Day by day">
          <ul className={s.list}>
            {v.days.map((d) => (
              <li key={d.key} className={s.row}>
                <IconTile icon="nutrition-20" color="dataNutrition" />
                <div className={s.text}>
                  <Txt s="bodyMStrong">{d.title}</Txt>
                  <Txt s="caption" c="textTertiary">{d.detail}</Txt>
                </div>
                <Txt s="numeric" className={s.c90}>{d.kcal}</Txt>
              </li>
            ))}
          </ul>
        </Panel>
      </Split>
    </>
  );
}

export function MealsTab() {
  const api = useApi();
  const [date, setDate] = useState<string | null>(null);
  const state = useScreen(useCallback((a) => loadMealsDay(a, date), [date]));
  const [busy, setBusy] = useState<string | null>(null);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const remove = (id: string, title: string) => {
    if (!confirm(`Remove ${title}? It comes out of that day's totals.`)) return;
    setBusy(id);
    api.deleteMeal(id).then(
      () => (api.isMock ? alert("This is demo data, so nothing was removed.") : notifyChanged()),
      (e: Error) => alert(e.message),
    ).finally(() => setBusy(null));
  };
  return (
    <>
      <Choices label="Day" hideLabel options={v.days} selected={[v.date]} onChange={([d]) => setDate(d)} />
      <Split
        rail={
          <Panel title="Logging tips" level="h5" gap={10}>
            <Txt s="bodyS" c="textSecondary" as="p">
              Log Food adds to the day it's eaten. Removing a meal takes it out of that day's totals and your Nutrition goals.
            </Txt>
          </Panel>
        }
      >
        <Panel title={v.title}>
          {v.empty && <Txt s="bodyM" c="textTertiary" as="p">{v.empty}</Txt>}
          <ul className={s.list}>
            {v.meals.map((m) => (
              <li key={m.id} className={s.row}>
                <IconTile icon="nutrition-20" color="dataNutrition" />
                <div className={s.text}>
                  <Txt s="bodyMStrong">{m.title}</Txt>
                  <Txt s="caption" c="textTertiary">{m.items}</Txt>
                </div>
                <Txt s="table" className={s.c90}>{m.kcal}</Txt>
                <Button variant="tertiary" size="sm" disabled={busy === m.id} onClick={() => remove(m.id, m.title)} aria-label={`Remove ${m.title}`}>
                  Remove
                </Button>
              </li>
            ))}
          </ul>
          {v.total && <Txt s="bodySStrong" c="textSecondary" as="p">{v.total}</Txt>}
        </Panel>
      </Split>
    </>
  );
}

export function HydrationTab() {
  const api = useApi();
  const state = useScreen(loadHydration);
  const [busy, setBusy] = useState(false);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const add = (ml: number) => {
    setBusy(true);
    api.addWater(ml).then(
      () => (api.isMock ? alert("This is demo data, so nothing was saved.") : notifyChanged()),
      (e: Error) => alert(e.message),
    ).finally(() => setBusy(false));
  };
  return (
    <>
      <StatRow stats={v.stats} />
      <Split
        rail={
          <Panel title="About your goal" level="h5" gap={10}>
            <Txt s="bodyS" c="textSecondary" as="p">{v.note}</Txt>
          </Panel>
        }
      >
        <Panel title="Add water">
          <div className={s.actions}>
            {v.glasses.map((g, i) => (
              <Button key={g.ml} variant={i === 0 ? "primary" : "secondary"} disabled={busy} onClick={() => add(g.ml)}>
                {g.label}
              </Button>
            ))}
          </div>
        </Panel>
        <Panel title="This week">
          <GoalBars bars={v.bars} goalFraction={v.goalFraction} color="dataRespiratory" label="Water by day" />
        </Panel>
      </Split>
    </>
  );
}
