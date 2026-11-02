// Log Food, Log Workout and New Goal — Mobile sheets (Figma 198:317).
import {
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
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useApi } from "../data";
import { c, t } from "../theme";
import { Choices, DateField, Field, FormSheet, must, Note, PickField, Row, TimeField, useZone } from "../components/Sheet";

/** The meal slot for the time of day: breakfast before 11, lunch before 15, dinner from 17, else snack. */
const slotAt = (hhmm: string) => {
  const h = Number(hhmm.slice(0, 2));
  return h < 11 ? "breakfast" : h < 15 ? "lunch" : h >= 17 && h < 22 ? "dinner" : "snack";
};

// ---------------------------------------------------------------- Log Food

export function LogFoodSheet({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <LogFood tz={tz} onClose={onClose} /> : null;
}

function LogFood({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const time = timeIn(tz);
  const [f, setF] = useState({ slot: slotAt(time), grams: "", date: todayIn(tz), time });
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<FoodHit[] | null>(null);
  const [food, setFood] = useState<FoodHit | null>(null);
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  // Search as the user types (2+ letters), a moment after they stop.
  useEffect(() => {
    if (food || query.trim().length < 2) {
      setHits(null);
      return;
    }
    let live = true;
    const timer = setTimeout(() => api.searchFoods(query.trim()).then((r) => live && setHits(r), () => live && setHits([])), 250);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [api, query, food]);

  const pick = (h: FoodHit) => {
    setFood(h);
    setQuery(h.brand ? `${h.name} · ${h.brand}` : h.name);
    setF((x) => ({ ...x, grams: x.grams || String(Math.round(h.serving_g)) }));
  };
  const estimate = food ? foodEstimate(food, f.grams) : null;

  return (
    <FormSheet
      overline="Nutrition › Add"
      title="Log Food"
      subtitle="Add what you ate to today's totals."
      action="Log Food"
      onClose={onClose}
      save={async () => {
        await api.logMeal(must(checkFood({ ...f, foodId: food?.id ?? null }, tz)));
      }}
    >
      <Choices label="Meal" options={MEAL_SLOTS} selected={[f.slot]} onChange={([v]) => set("slot")(v)} />
      <Field
        label="Food"
        icon="search-16"
        value={query}
        placeholder="Search foods"
        onChange={(v) => {
          setQuery(v);
          setFood(null);
        }}
        helper={api.isMock ? "Food search needs your account; the demo has no food list." : undefined}
      />
      {hits && (
        <View style={st.results} accessibilityLabel="Foods">
          {hits.length === 0 ? (
            <Text style={[t("bodyS", "textTertiary"), st.result]}>No foods match “{query.trim()}”.</Text>
          ) : (
            hits.slice(0, 6).map((h, i) => (
              <Pressable key={h.id} accessibilityRole="button" onPress={() => pick(h)} style={({ pressed }) => [st.result, i > 0 && st.line, pressed && { backgroundColor: c.bgSubtle }]}>
                <Text style={[t("bodyM", "textPrimary"), st.flex]} numberOfLines={1}>
                  {h.brand ? `${h.name} · ${h.brand}` : h.name}
                </Text>
                <Text style={t("caption", "textTertiary")}>{Math.round(h.kcal)} kcal / 100 g</Text>
              </Pressable>
            ))
          )}
        </View>
      )}
      <Row>
        <Field label="Amount" value={f.grams} onChange={set("grams")} keyboard="decimal-pad" helper="grams" />
        <TimeField label="Time" value={f.time} onChange={set("time")} />
      </Row>
      {estimate && <Note>{estimate}</Note>}
    </FormSheet>
  );
}

// ---------------------------------------------------------------- Log Workout

const TITLES: Record<string, string> = { walk: "Walk", run: "Run", cycle: "Ride", strength: "Strength session", swim: "Swim", hiit: "HIIT", yoga: "Yoga", other: "Workout" };

export function LogWorkoutSheet({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <LogWorkout tz={tz} onClose={onClose} /> : null;
}

function LogWorkout({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ type: "run", title: "", date: todayIn(tz), time: timeIn(tz), minutes: "", km: "", effort: "", avgHr: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const hasDistance = ["walk", "run", "cycle", "swim"].includes(f.type);

  return (
    <FormSheet
      overline="Workouts › Add"
      title="Log Workout"
      subtitle="Record a session your watch didn't track."
      action="Save Workout"
      onClose={onClose}
      save={async () => {
        await api.logWorkout(must(checkWorkout({ ...f, title: f.title || TITLES[f.type], km: hasDistance ? f.km : "" }, tz)));
      }}
    >
      <Choices label="Type" options={WORKOUT_TYPES} selected={[f.type]} onChange={([v]) => set("type")(v)} />
      <Field label="Title" value={f.title} placeholder={TITLES[f.type]} onChange={set("title")} />
      <Row>
        <DateField label="Date" value={f.date} onChange={set("date")} tz={tz} past />
        <TimeField label="Start" value={f.time} onChange={set("time")} />
      </Row>
      <Row>
        <Field label="Duration" value={f.minutes} onChange={set("minutes")} keyboard="number-pad" placeholder="42" helper="minutes" />
        {hasDistance ? (
          <Field label="Distance" value={f.km} onChange={set("km")} keyboard="decimal-pad" placeholder="Optional" helper="km" />
        ) : (
          <Field label="Avg HR" value={f.avgHr} onChange={set("avgHr")} keyboard="number-pad" placeholder="bpm" />
        )}
      </Row>
      <Row>
        <PickField label="Effort" value={f.effort} onChange={set("effort")} options={EFFORT} />
        {hasDistance ? <Field label="Avg HR" value={f.avgHr} onChange={set("avgHr")} keyboard="number-pad" placeholder="bpm" /> : <View style={st.flex} />}
      </Row>
    </FormSheet>
  );
}

// ---------------------------------------------------------------- New Goal

export function NewGoalSheet({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  const api = useApi();
  const [metrics, setMetrics] = useState<GoalMetricOption[] | null>(null);
  useEffect(() => {
    api.goalMetrics().then(setMetrics, () => setMetrics([]));
  }, [api]);
  return tz && metrics ? <NewGoal tz={tz} metrics={metrics} onClose={onClose} /> : null;
}

function NewGoal({ tz, metrics, onClose }: { tz: string; metrics: GoalMetricOption[]; onClose: () => void }) {
  const api = useApi();
  const areas = GOAL_AREAS.filter((a) => metrics.some((m) => m.area === a.value));
  const [area, setArea] = useState<string>(areas[0]?.value ?? "activity");
  const inArea = metrics.filter((m) => m.area === area);
  const [f, setF] = useState({ metric: inArea[0]?.code ?? "", direction: "at_least", target: "", starts: todayIn(tz), ends: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const metric = metrics.find((m) => m.code === f.metric);
  const unit = metric ? goalUnit(metric) : null;

  return (
    <FormSheet
      overline="Goals › Add"
      title="New Goal"
      subtitle="Progress updates from your data."
      action="Create Goal"
      onClose={onClose}
      save={async () => {
        await api.createGoal(must(checkGoal(f, metrics)));
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
      <PickField label="Goal" value={f.metric} onChange={set("metric")} options={inArea.map((m) => ({ value: m.code, label: m.label }))} />
      <Row>
        <PickField label="Direction" value={f.direction} onChange={set("direction")} options={DIRECTIONS} />
        <Field label="Target" value={f.target} onChange={set("target")} keyboard="decimal-pad" helper={unit ? [unit.unit, unit.per].filter(Boolean).join(" ") : undefined} />
      </Row>
      <Row>
        <DateField label="Starts" value={f.starts} onChange={set("starts")} tz={tz} />
        <DateField label="Ends" value={f.ends} onChange={set("ends")} tz={tz} from={f.starts} optional />
      </Row>
    </FormSheet>
  );
}

const st = StyleSheet.create({
  results: { marginTop: -8, borderWidth: 1, borderColor: c.borderSubtle, borderRadius: 8, backgroundColor: c.bgSurface },
  result: { flexDirection: "row", alignItems: "center", gap: 12, paddingVertical: 10, paddingHorizontal: 14 },
  line: { borderTopWidth: 1, borderTopColor: c.borderSubtle },
  flex: { flex: 1 },
});
