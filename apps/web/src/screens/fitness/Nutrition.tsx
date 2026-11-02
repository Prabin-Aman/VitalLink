// Nutrition — Desktop 1440 (Figma 131:100); Week, Meals, Recipes and Hydration are in NutritionTabs.tsx (Figma 195:2).
import { loadNutrition } from "@vitallink/api";
import { useState } from "react";
import { tokenVar } from "../../components/Icon";
import { Action, BarRow, IconTile, KeyValues, PageHeader, Panel, PillTabs, Split, StatRow, Txt } from "../../components/kit";
import { useScreen } from "../../data";
import { ScreenState } from "../ScreenState";
import s from "../Lists.module.css";
import n from "./Nutrition.module.css";
import { LogFoodDialog } from "../../forms/FitnessForms";
import { NutritionTargetsDialog, ScanBarcodeDialog } from "../../forms/MoreForms";
import type { FoodHit } from "@vitallink/api";
import { HydrationTab, MealsTab, RecipesTab, WeekTab } from "./NutritionTabs";

const TABS = ["Today", "Week", "Meals", "Recipes", "Hydration"];

export function Nutrition() {
  const [form, setForm] = useState(false);
  const [targets, setTargets] = useState(false);
  const [scan, setScan] = useState(false);
  const [scanned, setScanned] = useState<FoodHit | null>(null);
  const state = useScreen(loadNutrition);
  const [tab, setTab] = useState(TABS[0]);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <>
      <PageHeader
        title="Nutrition"
        subtitle={v.subtitle}
        actions={
          <>
            <Action onClick={() => setTargets(true)}>Targets</Action>
            {targets && <NutritionTargetsDialog onClose={() => setTargets(false)} />}
            <Action onClick={() => setScan(true)}>Scan Barcode</Action>
            {scan && <ScanBarcodeDialog onClose={() => setScan(false)} onFound={setScanned} />}
            <Action primary onClick={() => setForm(true)}>Log Food</Action>
            {form && <LogFoodDialog onClose={() => setForm(false)} />}
            {scanned && <LogFoodDialog food={scanned} onClose={() => setScanned(null)} />}
          </>
        }
      />
      <PillTabs label="Nutrition sections" tabs={TABS} value={tab} onChange={setTab} />
      {tab === "Week" ? (
        <WeekTab />
      ) : tab === "Meals" ? (
        <MealsTab />
      ) : tab === "Hydration" ? (
        <HydrationTab />
      ) : tab === "Recipes" ? (
        <RecipesTab />
      ) : (
        <>
          <StatRow stats={v.stats} />
          <Split
            rail={
              <>
                <Panel title="Energy balance" level="h5" gap={12}>
                  <KeyValues rows={v.energy} />
                  <Txt s="caption" c="textTertiary" as="p">{v.energyNote}</Txt>
                </Panel>
                <Panel title="Hydration" level="h5" gap={12}>
                  <div className={n.glasses} role="img" aria-label={v.hydration.caption}>
                    {v.hydration.glasses.map((full, i) => (
                      <span key={i} style={{ background: tokenVar(full ? "dataBloodOxygen" : "bgSubtle") }} />
                    ))}
                  </div>
                  <Txt s="caption" c="textTertiary">{v.hydration.caption}</Txt>
                </Panel>
              </>
            }
          >
            <Panel title="Macronutrient split" gap={16}>
              {v.splitEmpty && <Txt s="bodyM" c="textTertiary" as="p">{v.splitEmpty}</Txt>}
              <div className={n.split} hidden={!v.split.length} role="img" aria-label={v.split.map((x) => x.label).join(", ")}>
                {v.split.map((x) => (
                  <span key={x.label} className="vl-label-l" style={{ flexGrow: x.fraction, background: tokenVar(x.color) }}>
                    {x.label}
                  </span>
                ))}
              </div>
              {v.macros.map((m) => (
                <BarRow key={m.key} row={m} />
              ))}
            </Panel>

            <Panel title="Today's meals">
              <ul className={s.list}>
                {v.meals.map((m) => (
                  <li key={m.id} className={s.row}>
                    <IconTile icon="nutrition-20" color="dataNutrition" />
                    <div className={s.text}>
                      <Txt s="bodyMStrong">{m.title}</Txt>
                      <Txt s="caption" c="textTertiary">{m.items}</Txt>
                    </div>
                    <Txt s="numeric" className={s.c90}>{m.kcal}</Txt>
                    <Txt s="table" c="textSecondary" className={s.c150}>{m.macros}</Txt>
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
