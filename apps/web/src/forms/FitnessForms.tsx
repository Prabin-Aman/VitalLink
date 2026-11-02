// Log Food (Figma 197:73), Log Workout (197:127), New Goal (197:397) and Log exercise (215:1843) — Dialog 560.
import {
  checkExerciseLog,
  checkSet,
  setSummary,
  checkFood,
  checkGoal,
  checkWorkout,
  DIRECTIONS,
  EFFORT,
  foodEstimate,
  GOAL_AREAS,
  goalUnit,
  MEAL_SLOTS,
  timeIn,
  todayIn,
  WORKOUT_TYPES,
  type FoodHit,
  type GoalMetricOption,
} from "@vitallink/api";
import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { Choices, FormDialog, formStyles as s, ItemList, must, Note, Row, SelectField, TextField, useAccount, useZone } from "../components/Form";
import { Button } from "../components/ui";
import { AddFoodDialog } from "./MoreForms";
import { useApi } from "../data";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");

/** The meal slot for the time of day: breakfast before 11, lunch before 15, dinner from 17, else snack. */
export const slotAt = (hhmm: string) => {
  const h = Number(hhmm.slice(0, 2));
  return h < 11 ? "breakfast" : h < 15 ? "lunch" : h >= 17 && h < 22 ? "dinner" : "snack";
};

// ---------------------------------------------------------------- Log Food

/** `food` comes preset after Scan Barcode found it. */
export function LogFoodDialog({ onClose, food }: { onClose: () => void; food?: FoodHit }) {
  const tz = useZone();
  return tz ? <LogFood tz={tz} onClose={onClose} start={food ?? null} /> : null;
}

function LogFood({ tz, onClose, start }: { tz: string; onClose: () => void; start: FoodHit | null }) {
  const api = useApi();
  const time = timeIn(tz);
  const [f, setF] = useState({ slot: slotAt(time), grams: start ? String(Math.round(start.serving_g)) : "", date: todayIn(tz), time });
  const [query, setQuery] = useState(start ? (start.brand ? `${start.name} · ${start.brand}` : start.name) : "");
  const [hits, setHits] = useState<FoodHit[] | null>(null);
  const [food, setFood] = useState<FoodHit | null>(start);
  const [addingFood, setAddingFood] = useState(false);
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  // Search as the user types (2+ letters), a moment after they stop.
  useEffect(() => {
    if (food || query.trim().length < 2) {
      setHits(null);
      return;
    }
    let live = true;
    const t = setTimeout(() => api.searchFoods(query.trim()).then((r) => live && setHits(r), () => live && setHits([])), 250);
    return () => {
      live = false;
      clearTimeout(t);
    };
  }, [api, query, food]);

  const pick = (h: FoodHit) => {
    setFood(h);
    setQuery(h.brand ? `${h.name} · ${h.brand}` : h.name);
    setF((x) => ({ ...x, grams: x.grams || String(Math.round(h.serving_g)) }));
  };
  const estimate = food ? foodEstimate(food, f.grams) : null;

  return (
    <FormDialog
      overline="Nutrition › Log Food"
      title="Log Food"
      subtitle="Add what you ate to today's totals."
      action="Log Food"
      onClose={onClose}
      save={async () => {
        await api.logMeal(must(checkFood({ ...f, foodId: food?.id ?? null }, tz)));
      }}
    >
      {/* Outside Log Food's own form (a portal): a form inside a form would submit both. */}
      {addingFood && createPortal(<AddFoodDialog name={query.trim()} onClose={() => setAddingFood(false)} onAdded={(h) => pick(h)} />, document.body)}
      <Choices label="Meal" options={MEAL_SLOTS} selected={[f.slot]} onChange={([v]) => set("slot")(v)} />
      <div>
        <TextField
          label="Food"
          icon="search-16"
          value={query}
          placeholder="Search foods"
          autoFocus
          autoComplete="off"
          onChange={(v) => {
            setQuery(v);
            setFood(null);
          }}
          helper={api.isMock ? "Food search needs your account; the demo has no food list." : "From your foods and the VitalLink food list."}
        />
        {hits && (
          <div className={s.results} role="listbox" aria-label="Foods">
            {hits.length === 0 ? (
              <span className={cx("vl-body-s", s.empty)}>
                No foods match “{query.trim()}”.{" "}
                <Button variant="tertiary" size="sm" onClick={() => setAddingFood(true)}>
                  Add it as a new food
                </Button>
              </span>
            ) : (
              hits.slice(0, 8).map((h) => (
                <button key={h.id} type="button" role="option" aria-selected={false} className={s.result} onClick={() => pick(h)}>
                  <span className="vl-body-m">{h.brand ? `${h.name} · ${h.brand}` : h.name}</span>
                  <span className={cx("vl-caption", s.helper)}>{Math.round(h.kcal)} kcal / 100 g</span>
                </button>
              ))
            )}
          </div>
        )}
      </div>
      <Row>
        <TextField label="Amount" value={f.grams} onChange={set("grams")} inputMode="decimal" helper="grams" />
        <TextField label="Time" type="time" icon="clock-16" value={f.time} onChange={set("time")} />
      </Row>
      {estimate && <Note>{estimate}</Note>}
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Log Workout

/** `preset` comes from Start Session on the next planned session. */
export function LogWorkoutDialog({ onClose, preset }: { onClose: () => void; preset?: { title: string; type: string } }) {
  const tz = useZone();
  return tz ? <LogWorkout tz={tz} onClose={onClose} preset={preset} /> : null;
}

const TITLES: Record<string, string> = { walk: "Walk", run: "Run", cycle: "Ride", strength: "Strength session", swim: "Swim", hiit: "HIIT", yoga: "Yoga", other: "Workout" };

function LogWorkout({ tz, onClose, preset }: { tz: string; onClose: () => void; preset?: { title: string; type: string } }) {
  const api = useApi();
  const [f, setF] = useState({ type: preset?.type ?? "run", title: preset?.title ?? "", date: todayIn(tz), time: timeIn(tz), minutes: "", km: "", effort: "", avgHr: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const hasDistance = ["walk", "run", "cycle", "swim"].includes(f.type);

  return (
    <FormDialog
      overline="Workouts › Log Workout"
      title="Log Workout"
      subtitle="Record a session your watch didn't track."
      action="Save Workout"
      onClose={onClose}
      save={async () => {
        await api.logWorkout(must(checkWorkout({ ...f, title: f.title || TITLES[f.type], km: hasDistance ? f.km : "" }, tz)));
      }}
    >
      <Choices label="Type" options={WORKOUT_TYPES} selected={[f.type]} onChange={([v]) => set("type")(v)} />
      <TextField label="Title" value={f.title} placeholder={TITLES[f.type]} onChange={set("title")} />
      <Row>
        <TextField label="Date" type="date" icon="appointments-16" value={f.date} max={todayIn(tz)} onChange={set("date")} />
        <TextField label="Start" type="time" icon="clock-16" value={f.time} onChange={set("time")} />
      </Row>
      <Row>
        <TextField label="Duration" value={f.minutes} onChange={set("minutes")} inputMode="numeric" placeholder="42" helper="minutes" autoFocus />
        {hasDistance ? (
          <TextField label="Distance" value={f.km} onChange={set("km")} inputMode="decimal" placeholder="Optional" helper="km" />
        ) : (
          <TextField label="Avg heart rate" value={f.avgHr} onChange={set("avgHr")} inputMode="numeric" placeholder="Optional" helper="bpm" />
        )}
      </Row>
      <Row>
        <SelectField label="Effort" value={f.effort} onChange={set("effort")} options={EFFORT} />
        {hasDistance ? (
          <TextField label="Avg heart rate" value={f.avgHr} onChange={set("avgHr")} inputMode="numeric" placeholder="Optional" helper="bpm" />
        ) : (
          <span />
        )}
      </Row>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- New Goal

export function NewGoalDialog({ onClose }: { onClose: () => void }) {
  const account = useAccount();
  const api = useApi();
  const [metrics, setMetrics] = useState<GoalMetricOption[] | null>(null);
  useEffect(() => {
    api.goalMetrics().then(setMetrics, () => setMetrics([]));
  }, [api]);
  return account && metrics ? <NewGoal tz={account.tz} units={account.units} metrics={metrics} onClose={onClose} /> : null;
}

function NewGoal({ tz, units, metrics, onClose }: { tz: string; units: "metric" | "imperial"; metrics: GoalMetricOption[]; onClose: () => void }) {
  const api = useApi();
  const areas = GOAL_AREAS.filter((a) => metrics.some((m) => m.area === a.value));
  const [area, setArea] = useState<string>(areas[0]?.value ?? "activity");
  const inArea = metrics.filter((m) => m.area === area);
  const [f, setF] = useState({ metric: inArea[0]?.code ?? "", direction: "at_least", target: "", starts: todayIn(tz), ends: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const metric = metrics.find((m) => m.code === f.metric);
  const unit = metric ? goalUnit(metric, units) : null;

  return (
    <FormDialog
      overline="Goals › New Goal"
      title="New Goal"
      subtitle="Pick something to track; progress updates from your data."
      action="Create Goal"
      onClose={onClose}
      save={async () => {
        await api.createGoal(must(checkGoal(f, metrics, units)));
      }}
    >
      <Choices
        label="Area"
        options={areas}
        selected={[area]}
        onChange={([v]) => {
          setArea(v);
          set("metric")(metrics.find((m) => m.area === v)?.code ?? "");
        }}
      />
      <SelectField label="Goal" value={f.metric} onChange={set("metric")} options={inArea.map((m) => ({ value: m.code, label: m.label }))} />
      <Row>
        <SelectField label="Direction" value={f.direction} onChange={set("direction")} options={DIRECTIONS} />
        <TextField label="Target" value={f.target} onChange={set("target")} inputMode="decimal" helper={unit ? [unit.unit, unit.per].filter(Boolean).join(" ") : undefined} autoFocus />
      </Row>
      <Row>
        <TextField label="Starts" type="date" icon="appointments-16" value={f.starts} onChange={set("starts")} />
        <TextField label="Ends" type="date" icon="appointments-16" value={f.ends} min={f.starts} onChange={set("ends")} helper="Optional" />
      </Row>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Log exercise

type SetBody = { reps: number | null; weight_kg: number | null; duration_s: number | null };

/** From the Exercise Library: did you do it? The sets (reps, weight, or time for a hold) become a workout. */
export function LogExerciseDialog({ exercise, onClose }: { exercise: string; onClose: () => void }) {
  const tz = useZone();
  return tz ? <LogExercise tz={tz} exercise={exercise} onClose={onClose} /> : null;
}

function LogExercise({ tz, exercise, onClose }: { tz: string; exercise: string; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ date: todayIn(tz), time: timeIn(tz), minutes: "", effort: "" });
  const [sets, setSets] = useState<SetBody[]>([]);
  const [next, setNext] = useState({ reps: "", weight: "", seconds: "" });
  const [problem, setProblem] = useState<string | null>(null);
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  const addSet = () => {
    const c = checkSet(next);
    if (!c.ok) return setProblem(c.error);
    setProblem(null);
    setSets((all) => [...all, c.body]);
    // Keep the weight for the next set; most sets repeat it.
    setNext((x) => ({ ...x, reps: "", seconds: "" }));
  };

  return (
    <FormDialog
      overline="Workouts › Exercise Library"
      title={exercise}
      subtitle="Did you do it? Add your sets and VitalLink saves it to your workouts."
      action="Save Exercise"
      onClose={onClose}
      save={async () => {
        // A set typed but not added yet still counts.
        const typed = next.reps || next.seconds ? must(checkSet(next)) : null;
        await api.logWorkout(must(checkExerciseLog(exercise, f, typed ? [...sets, typed] : sets, tz)));
      }}
    >
      <Row>
        <TextField label="Date" type="date" icon="appointments-16" value={f.date} max={todayIn(tz)} onChange={set("date")} />
        <TextField label="Start" type="time" icon="clock-16" value={f.time} onChange={set("time")} />
      </Row>
      <ItemList
        label="Sets"
        empty="No sets yet. Add each set below."
        items={sets.map((x, i) => ({ key: String(i), title: `Set ${i + 1}`, caption: setSummary(x), onRemove: () => setSets((all) => all.filter((_, j) => j !== i)) }))}
        add={null}
      />
      <Row>
        <TextField label="Reps" value={next.reps} onChange={(reps) => setNext((x) => ({ ...x, reps }))} inputMode="numeric" placeholder="10" autoFocus />
        <TextField label="Weight" value={next.weight} onChange={(weight) => setNext((x) => ({ ...x, weight }))} inputMode="decimal" placeholder="Optional" helper="kg" />
        <TextField label="Time" value={next.seconds} onChange={(seconds) => setNext((x) => ({ ...x, seconds }))} inputMode="numeric" placeholder="Optional" helper="seconds, for holds like a plank" />
      </Row>
      <span>
        <Button variant="tertiary" size="sm" onClick={addSet}>
          + Add set
        </Button>
      </span>
      {problem && <Note>{problem}</Note>}
      <Row>
        <TextField label="Time taken" value={f.minutes} onChange={set("minutes")} inputMode="numeric" placeholder="25" helper="minutes" />
        <SelectField label="Effort" value={f.effort} onChange={set("effort")} options={EFFORT} />
      </Row>
    </FormDialog>
  );
}
