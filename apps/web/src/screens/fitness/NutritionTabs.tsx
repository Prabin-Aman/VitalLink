// Nutrition tabs (Figma 195:2 "Web · Proposed tabs and sections"): Week (204:1068), Meals (204:1545),
// Hydration (204:1973, today's entries 221:2205), Recipes (222:2104).
import { loadHydration, loadMealsDay, loadNutritionWeek, loadRecipes, type Recipe } from "@vitallink/api";
import { useCallback, useState } from "react";
import { GoalBars } from "../../components/Charts";
import { Choices } from "../../components/Form";
import { IconTile, KeyValues, Panel, Split, StatRow, Txt } from "../../components/kit";
import { Button } from "../../components/ui";
import { notifyChanged, useApi, useScreen } from "../../data";
import { LogRecipeDialog, RecipeDialog } from "../../forms/RecipeForms";
import { ScreenState } from "../ScreenState";
import s from "../Lists.module.css";

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
  const remove = (id: string) => {
    setBusy(true);
    api.removeWater(id).then(
      () => (api.isMock ? alert("This is demo data, so nothing was removed.") : notifyChanged()),
      (e: Error) => alert(e.message),
    ).finally(() => setBusy(false));
  };
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
        <Panel title="Today" gap={12}>
          <Txt s="bodyS" c="textSecondary" as="p">{v.entriesLine}</Txt>
          <ul className={s.list}>
            {v.entries.map((e) => (
              <li key={e.id} className={s.row}>
                <div className={s.text}>
                  <Txt s="bodyMStrong">{e.amount}</Txt>
                  <Txt s="caption" c="textTertiary">{e.when}</Txt>
                </div>
                <Button variant="tertiary" size="sm" disabled={busy} onClick={() => remove(e.id)} aria-label={`Remove ${e.amount} at ${e.when}`}>
                  Remove
                </Button>
              </li>
            ))}
          </ul>
        </Panel>
        <Panel title="This week">
          <GoalBars bars={v.bars} goalFraction={v.goalFraction} color="dataRespiratory" label="Water by day" />
        </Panel>
      </Split>
    </>
  );
}

export function RecipesTab() {
  const state = useScreen(loadRecipes);
  const [editing, setEditing] = useState<Recipe | "new" | null>(null);
  const [logging, setLogging] = useState<Recipe | null>(null);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  return (
    <Split
      rail={
        <Panel title="About recipes" level="h5" gap={10}>
          <Txt s="bodyS" c="textSecondary" as="p">
            Build a dish from your foods once, with how many servings it makes. VitalLink works out each serving's calories and macros.
          </Txt>
        </Panel>
      }
    >
      <Panel title="Your recipes">
        {v.empty && <Txt s="bodyM" c="textTertiary" as="p">{v.empty}</Txt>}
        <ul className={s.list}>
          {v.rows.map((r) => (
            <li key={r.id} className={s.row}>
              <IconTile icon="nutrition-20" color="dataNutrition" />
              <div className={s.text}>
                <Txt s="bodyMStrong">{r.title}</Txt>
                <Txt s="caption" c="textTertiary">{r.caption}</Txt>
              </div>
              <Txt s="table">{r.kcal}</Txt>
              <Button variant="tertiary" size="sm" onClick={() => setEditing(r.recipe)} aria-label={`Edit ${r.title}`}>
                Edit
              </Button>
              <Button variant="secondary" size="sm" onClick={() => setLogging(r.recipe)} aria-label={`Log ${r.title}`}>
                Log
              </Button>
            </li>
          ))}
        </ul>
        {v.rows.length > 0 && (
          <Txt s="bodyS" c="textSecondary" as="p">
            Per serving, worked out from your foods. Log adds servings as a meal; editing a recipe doesn't change meals already logged.
          </Txt>
        )}
        <span>
          <Button variant="secondary" size="sm" onClick={() => setEditing("new")}>
            + New Recipe
          </Button>
        </span>
      </Panel>
      {editing && <RecipeDialog recipe={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} />}
      {logging && <LogRecipeDialog recipe={logging} onClose={() => setLogging(null)} />}
    </Split>
  );
}
