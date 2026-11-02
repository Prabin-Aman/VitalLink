// New recipe / Edit recipe (Figma 222:2330) and Log a recipe (222:2417) — Dialog 560. Rules in forms.ts.
import {
  checkIngredient,
  checkRecipe,
  checkRecipeLog,
  MEAL_SLOTS,
  recipeEstimate,
  thousands,
  timeIn,
  todayIn,
  type FoodHit,
  type Recipe,
  type RecipeIngredient,
} from "@vitallink/api";
import { useEffect, useState } from "react";
import { Choices, FormDialog, FormProblem, formStyles as s, ItemList, must, Note, Row, TextAreaField, TextField, useZone } from "../components/Form";
import { Button } from "../components/ui";
import { useApi } from "../data";
import { DangerButton } from "./EditForms";
import { slotAt } from "./FitnessForms";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");

/** Search as the user types (2+ letters), a moment after they stop; null while there's nothing to show. */
function useFoodSearch(query: string, picked: boolean): FoodHit[] | null {
  const api = useApi();
  const [hits, setHits] = useState<FoodHit[] | null>(null);
  useEffect(() => {
    if (picked || query.trim().length < 2) {
      setHits(null);
      return;
    }
    let live = true;
    const t = setTimeout(() => api.searchFoods(query.trim()).then((r) => live && setHits(r), () => live && setHits([])), 250);
    return () => {
      live = false;
      clearTimeout(t);
    };
  }, [api, query, picked]);
  return hits;
}

const foodLabel = (f: FoodHit) => (f.brand ? `${f.name} · ${f.brand}` : f.name);

/** `recipe` edits it (with Delete Recipe); without, a new recipe. */
export function RecipeDialog({ recipe, onClose }: { recipe?: Recipe; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ name: recipe?.name ?? "", servings: String(recipe?.servings ?? 2), notes: recipe?.notes ?? "" });
  const [items, setItems] = useState<RecipeIngredient[]>(recipe?.items.map((i) => ({ food: i.food, grams: i.quantity_g })) ?? []);
  const [query, setQuery] = useState("");
  const [food, setFood] = useState<FoodHit | null>(null);
  const [grams, setGrams] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const hits = useFoodSearch(query, food !== null);
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  const pick = (h: FoodHit) => {
    setFood(h);
    setQuery(foodLabel(h));
    setGrams((g) => g || String(Math.round(h.serving_g)));
  };
  const addIngredient = () => {
    const c = checkIngredient(food, grams);
    if (!c.ok) return setProblem(c.error);
    setItems((all) => [...all, c.body]);
    setFood(null);
    setQuery("");
    setGrams("");
    setProblem(null);
  };
  const estimate = recipeEstimate(items, f.servings);

  return (
    <FormDialog
      overline="Nutrition › Recipes"
      title={recipe ? "Edit recipe" : "New recipe"}
      subtitle="Add each food once with its grams for the whole dish."
      action="Save Recipe"
      onClose={onClose}
      extra={recipe && <DangerButton label="Delete Recipe" confirmText={`Delete ${recipe.name}? Meals you logged from it stay.`} run={() => api.deleteRecipe(recipe.id)} onDone={onClose} />}
      save={async () => {
        if (food) throw new FormProblem("Press + Add to recipe for the food you picked, or clear it.");
        const body = must(checkRecipe({ ...f, items }));
        if (recipe) await api.updateRecipe(recipe.id, body);
        else await api.createRecipe(body);
      }}
    >
      <Row>
        <TextField label="Name" value={f.name} onChange={set("name")} placeholder="e.g. Overnight oats" autoFocus={!recipe} />
        <TextField label="Servings" value={f.servings} onChange={set("servings")} inputMode="numeric" helper="How many it makes" />
      </Row>
      <ItemList
        label="Ingredients"
        empty="No foods yet. Search below and add them with their grams."
        items={items.map((i, n) => ({
          key: `${i.food.id}-${n}`,
          title: foodLabel(i.food),
          caption: `${thousands(i.grams)} g · ${thousands(Math.round((i.food.kcal * i.grams) / 100))} kcal`,
          onRemove: () => setItems((all) => all.filter((_, j) => j !== n)),
        }))}
        add={null}
      />
      <div>
        <Row>
          <TextField
            label="Food"
            icon="search-16"
            value={query}
            placeholder="Search foods"
            autoComplete="off"
            onChange={(v) => {
              setQuery(v);
              setFood(null);
            }}
            helper={api.isMock ? "Food search needs your account; the demo has no food list." : undefined}
          />
          <TextField label="Grams" value={grams} onChange={setGrams} inputMode="decimal" placeholder="e.g. 100" />
        </Row>
        {hits && (
          <div className={s.results} role="listbox" aria-label="Foods">
            {hits.length === 0 ? (
              <span className={cx("vl-body-s", s.empty)}>No foods match “{query.trim()}”. Add it first with Log Food › Add it as a new food.</span>
            ) : (
              hits.slice(0, 8).map((h) => (
                <button key={h.id} type="button" role="option" aria-selected={false} className={s.result} onClick={() => pick(h)}>
                  <span className="vl-body-m">{foodLabel(h)}</span>
                  <span className={cx("vl-caption", s.helper)}>{Math.round(h.kcal)} kcal / 100 g</span>
                </button>
              ))
            )}
          </div>
        )}
      </div>
      <span>
        <Button variant="tertiary" size="sm" onClick={addIngredient}>
          + Add to recipe
        </Button>
        {problem && <span className={cx("vl-caption", s.helper)}> {problem}</span>}
      </span>
      <TextAreaField label="Notes" value={f.notes} onChange={set("notes")} max={500} placeholder="Optional" helper="Optional, up to 500 characters." />
      {estimate && <Note>{estimate}</Note>}
    </FormDialog>
  );
}

export function LogRecipeDialog({ recipe, onClose }: { recipe: Recipe; onClose: () => void }) {
  const tz = useZone();
  return tz ? <LogRecipe tz={tz} recipe={recipe} onClose={onClose} /> : null;
}

function LogRecipe({ tz, recipe, onClose }: { tz: string; recipe: Recipe; onClose: () => void }) {
  const api = useApi();
  const time = timeIn(tz);
  const [f, setF] = useState({ slot: slotAt(time), servings: "1", date: todayIn(tz), time });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const c = checkRecipeLog(f, tz);
  const p = recipe.per_serving;
  const k = c.ok ? c.body.servings : null;
  return (
    <FormDialog
      overline={`Nutrition › Recipes › ${recipe.name}`}
      title="Log a recipe"
      subtitle={`Adds the servings you ate as a meal. One serving is ${thousands(Math.round(p.grams))} g.`}
      action="Log Meal"
      onClose={onClose}
      save={async () => {
        await api.logRecipe(recipe.id, must(checkRecipeLog(f, tz)));
      }}
    >
      <Choices label="Meal" options={MEAL_SLOTS} selected={[f.slot]} onChange={([v]) => set("slot")(v)} />
      <Row>
        <TextField label="Servings" value={f.servings} onChange={set("servings")} inputMode="decimal" helper="e.g. 1 or 1.5" autoFocus />
        <TextField label="Time" type="time" icon="clock-16" value={f.time} onChange={set("time")} />
      </Row>
      {k !== null && (
        <Note>
          ≈ {thousands(Math.round(p.kcal * k))} kcal · {Math.round(p.protein_g * k)} g protein · {Math.round(p.carbs_g * k)} g carbs · {Math.round(p.fat_g * k)} g fat
        </Note>
      )}
    </FormDialog>
  );
}
