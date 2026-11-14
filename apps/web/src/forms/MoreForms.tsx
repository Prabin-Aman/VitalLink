// Dose history (Figma 218:1900), Record a refill (218:1934), Nutrition targets (218:1966), Add a food (218:2030)
// Add lab results (218:2098) and Scan a barcode (221:2145) — Dialog 560. Rules in packages/api/src/forms.ts; every save goes to the API.
import {
  checkBarcode,
  checkLabPanel,
  checkLabTest,
  checkNewFood,
  checkNutritionTargets,
  checkRefill,
  dateLabel,
  dateTimeIn,
  nutritionTargetsForm,
  timeLabel,
  todayIn,
  type FoodHit,
  type LabTest,
  type MedicationDetail,
  type NutritionTargets,
} from "@vitallink/api";
import { useEffect, useRef, useState } from "react";
import { FormDialog, Help, ItemList, must, Note, Row, TextField, useZone } from "../components/Form";
import { Button } from "../components/ui";
import { notifyChanged, useApi } from "../data";
import formCss from "../components/Form.module.css";

/** Loads what a dialog shows; a failure closes it with the message. */
function useLoad<T>(load: () => Promise<T>, onClose: () => void): T | null {
  const [v, setV] = useState<T | null>(null);
  useEffect(() => {
    let live = true;
    load().then(
      (x) => live && setV(x),
      (e: Error) => {
        if (!live) return;
        alert(e.message);
        onClose();
      },
    );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return v;
}

// ---------------------------------------------------------------- Dose history

export function DoseHistoryDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const api = useApi();
  const tz = useZone();
  const [version, setVersion] = useState(0);
  const m = useLoad(() => api.medication(id), onClose);
  const [doses, setDoses] = useState<MedicationDetail["recent_doses"]>(undefined);
  useEffect(() => {
    if (version) api.medication(id).then((x) => setDoses(x.recent_doses), () => undefined);
  }, [api, id, version]);
  if (!m || !tz) return null;
  const list = doses ?? m.recent_doses ?? [];
  const when = (iso: string) => {
    const d = dateTimeIn(iso, tz);
    return `${dateLabel(d.date, true)} · ${timeLabel(d.time)}`;
  };
  const undo = (at: string) => {
    if (!confirm("Undo this dose? It shows as due (or missed) again.")) return;
    if (api.isMock) return alert("This is demo data, so nothing changed.");
    api.undoDose(m.id, at).then(
      () => {
        setVersion((v) => v + 1);
        notifyChanged();
      },
      (e: Error) => alert(e.message),
    );
  };
  return (
    <FormDialog overline={`Medications › ${m.name}`} title="Dose history" subtitle="The last 14 days. Undo a dose you marked by mistake." onClose={onClose}>
      <ItemList
        label="Logged doses"
        empty="No doses logged in the last 14 days."
        add={null}
        items={list.map((d) => ({
          key: d.scheduled_for,
          title: when(d.scheduled_for),
          caption: d.status === "taken" ? `Taken at ${timeLabel(dateTimeIn(d.logged_at, tz).time)}` : "Skipped",
          onRemove: () => undo(d.scheduled_for),
          removeLabel: "Undo",
        }))}
      />
      <Note>Doses you didn't mark show as missed once their time has passed. Undo makes a dose due or missed again.</Note>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Record a refill

export function RefillDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const api = useApi();
  const tz = useZone();
  const m = useLoad(() => api.medication(id), onClose);
  return m && tz ? <Refill m={m} tz={tz} onClose={onClose} /> : null;
}

function Refill({ m, tz, onClose }: { m: MedicationDetail; tz: string; onClose: () => void }) {
  const api = useApi();
  const today = todayIn(tz);
  const [f, setF] = useState({ refills: String(m.refills_left), due: m.refill_due_on ?? "" });
  return (
    <FormDialog
      overline={`Medications › ${m.name}`}
      title="Record a refill"
      subtitle={`Refills left: ${m.refills_left} · VitalLink reminds you before the next one is due.`}
      action="Save Refill"
      onClose={onClose}
      save={async () => {
        await api.updateMedication(m.id, must(checkRefill(f, today)));
      }}
    >
      <Row>
        <TextField label="Refills left" value={f.refills} onChange={(refills) => setF((x) => ({ ...x, refills }))} inputMode="numeric" autoFocus />
        <TextField label="Next refill due" type="date" icon="appointments-16" value={f.due} min={today} onChange={(due) => setF((x) => ({ ...x, due }))} helper="Optional" />
      </Row>
      <Note>VitalLink doesn't contact your pharmacy. Ask them for the refill, then note it here.</Note>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Nutrition targets

export function NutritionTargetsDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const t = useLoad(() => api.nutritionTargets(), onClose);
  return t ? <NutritionTargetsForm_ t={t} onClose={onClose} /> : null;
}

function NutritionTargetsForm_({ t, onClose }: { t: NutritionTargets; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState(() => nutritionTargetsForm(t));
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormDialog
      overline="Nutrition › Targets"
      title="Nutrition targets"
      subtitle="Your daily calories, macros and water, and the weight change you're aiming for."
      action="Save Targets"
      onClose={onClose}
      save={async () => {
        await api.updateNutritionTargets(must(checkNutritionTargets(f)));
      }}
    >
      <Row>
        <TextField label="Calories" value={f.kcal} onChange={set("kcal")} inputMode="numeric" helper="kcal a day" autoFocus />
        <TextField label="Protein" value={f.protein} onChange={set("protein")} inputMode="numeric" helper="g a day" />
      </Row>
      <Row>
        <TextField label="Carbs" value={f.carbs} onChange={set("carbs")} inputMode="numeric" helper="g a day" />
        <TextField label="Fat" value={f.fat} onChange={set("fat")} inputMode="numeric" helper="g a day" />
      </Row>
      <Row>
        <TextField label="Fibre" value={f.fibre} onChange={set("fibre")} inputMode="numeric" helper="g a day" />
        <TextField label="Water" value={f.water} onChange={set("water")} inputMode="numeric" helper="ml a day" />
      </Row>
      <Row>
        <TextField label="Glass size" value={f.glass} onChange={set("glass")} inputMode="numeric" helper="ml, for the + Glass button" />
        <TextField label="Weekly weight change" value={f.weekly} onChange={set("weekly")} inputMode="decimal" helper="kg a week (negative to lose)" />
      </Row>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Add a food

/** From Log Food when search finds nothing; `onAdded` gets the new food so Log Food can pick it. */
export function AddFoodDialog({ name, onClose, onAdded }: { name: string; onClose: () => void; onAdded: (food: FoodHit) => void }) {
  const api = useApi();
  const [f, setF] = useState({ name, brand: "", kcal: "", serving: "", protein: "", carbs: "", fat: "", fibre: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormDialog
      overline="Nutrition › Log Food"
      title="Add a food"
      subtitle="Not in the list? Add it from its label; it's saved for next time."
      action="Add Food"
      onClose={onClose}
      save={async () => {
        const food = await api.createFood(must(checkNewFood(f)));
        if (food) onAdded(food);
      }}
    >
      <Row>
        <TextField label="Name" value={f.name} onChange={set("name")} placeholder="e.g. Greek yogurt, plain" autoFocus />
        <TextField label="Brand" value={f.brand} onChange={set("brand")} placeholder="Optional" />
      </Row>
      <Row>
        <TextField label="Calories" value={f.kcal} onChange={set("kcal")} inputMode="decimal" helper="kcal per 100 g" />
        <TextField label="Serving" value={f.serving} onChange={set("serving")} inputMode="decimal" placeholder="Optional" helper="g" />
      </Row>
      <Row>
        <TextField label="Protein" value={f.protein} onChange={set("protein")} inputMode="decimal" helper="g per 100 g" />
        <TextField label="Carbs" value={f.carbs} onChange={set("carbs")} inputMode="decimal" helper="g per 100 g" />
      </Row>
      <Row>
        <TextField label="Fat" value={f.fat} onChange={set("fat")} inputMode="decimal" helper="g per 100 g" />
        <TextField label="Fibre" value={f.fibre} onChange={set("fibre")} inputMode="decimal" placeholder="Optional" helper="g per 100 g" />
      </Row>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Add lab results

export function AddLabResultsDialog({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <AddLabResults tz={tz} onClose={onClose} /> : null;
}

function AddLabResults({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const today = todayIn(tz);
  const [p, setP] = useState({ name: "", short: "", date: today, laboratory: "" });
  const [tests, setTests] = useState<LabTest[]>([]);
  const [next, setNext] = useState({ name: "", value: "", unit: "", low: "", high: "" });
  const [problem, setProblem] = useState<string | null>(null);
  const setT = (k: keyof typeof next) => (v: string) => setNext((x) => ({ ...x, [k]: v }));

  const addTest = () => {
    const c = checkLabTest(next);
    if (!c.ok) return setProblem(c.error);
    setProblem(null);
    setTests((all) => [...all, c.body]);
    setNext({ name: "", value: "", unit: "", low: "", high: "" });
  };
  const fmt = (n: number, d: number) => n.toFixed(d);

  return (
    <FormDialog
      overline="Lab Results › Add"
      title="Add lab results"
      subtitle="Type in a report from your doctor or lab. Each test needs its value and normal range."
      action="Save Results"
      onClose={onClose}
      save={async () => {
        // A test typed but not added yet still counts.
        const typed = next.name || next.value ? must(checkLabTest(next)) : null;
        await api.addLabResults(must(checkLabPanel(p, typed ? [...tests, typed] : tests, tz, today)));
      }}
    >
      <Row>
        <TextField label="Panel" value={p.name} onChange={(name) => setP((x) => ({ ...x, name }))} placeholder="e.g. Complete Blood Count" autoFocus />
        <TextField label="Short name" value={p.short} onChange={(short) => setP((x) => ({ ...x, short }))} placeholder="e.g. CBC" />
      </Row>
      <Row>
        <TextField label="Collected" type="date" icon="appointments-16" value={p.date} max={today} onChange={(date) => setP((x) => ({ ...x, date }))} />
        <TextField label="Laboratory" value={p.laboratory} onChange={(laboratory) => setP((x) => ({ ...x, laboratory }))} placeholder="Optional" />
      </Row>
      <ItemList
        label="Results"
        empty="No tests yet. Add each one below."
        add={null}
        items={tests.map((t, i) => ({
          key: `${t.name}-${i}`,
          title: t.name,
          caption: `${fmt(t.value, t.decimals)} ${t.unit} · normal ${fmt(t.low, t.decimals)}–${fmt(t.high, t.decimals)}`.replace("  ", " "),
          onRemove: () => setTests((all) => all.filter((_, j) => j !== i)),
        }))}
      />
      <Row>
        <TextField label="Test" value={next.name} onChange={setT("name")} placeholder="e.g. Hemoglobin" />
        <TextField label="Value" value={next.value} onChange={setT("value")} inputMode="decimal" placeholder="14.2" />
        <TextField label="Unit" value={next.unit} onChange={setT("unit")} placeholder="g/dL" />
      </Row>
      <Row>
        <TextField label="Normal low" value={next.low} onChange={setT("low")} inputMode="decimal" placeholder="13" />
        <TextField label="Normal high" value={next.high} onChange={setT("high")} inputMode="decimal" placeholder="17" />
      </Row>
      <span>
        <Button variant="tertiary" size="sm" onClick={addTest}>
          + Add test
        </Button>
      </span>
      {problem && <Note>{problem}</Note>}
      <Help
        title="Reading a lab report"
        items={[
          { q: "Where is the normal range?", a: "Next to each result, often headed Reference range or Ref. interval, as two numbers such as 13.0 to 17.0. Type the lower one in Normal low and the higher one in Normal high." },
          { q: "The range has only one number", a: "For a result like \"under 200\", use 0 for Normal low and 200 for Normal high. For \"over 40\", use 40 for Normal low and a high number the result can't reach for Normal high." },
          { q: "What are Panel and Short name?", a: "The Panel is the report's title, such as Lipid Panel. The Short name is what its tab says, such as Lipids." },
          { q: "Can I keep the report itself?", a: "Yes. Upload the PDF or a photo in Medical Records with Upload Document." },
        ]}
      />
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Scan a barcode (Figma 221:2145)

/** Chrome on Android and macOS read barcodes from the camera; elsewhere the digits are typed in. */
type Detector = { detect: (v: HTMLVideoElement) => Promise<{ rawValue: string }[]> };
const BarcodeDetectorCtor = (globalThis as unknown as { BarcodeDetector?: new (o: { formats: string[] }) => Detector }).BarcodeDetector;

export function ScanBarcodeDialog({ onClose, onFound }: { onClose: () => void; onFound: (food: FoodHit) => void }) {
  const api = useApi();
  const [code, setCode] = useState("");
  const [camera, setCamera] = useState<"off" | "on" | "blocked">("off");
  const video = useRef<HTMLVideoElement>(null);

  // While the camera is on, look for a barcode a few times a second; the first one read fills the field.
  useEffect(() => {
    if (camera !== "on" || !BarcodeDetectorCtor) return;
    let stream: MediaStream | null = null;
    let timer = 0;
    let live = true;
    const detector = new BarcodeDetectorCtor({ formats: ["ean_13", "ean_8", "upc_a", "upc_e"] });
    navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } }).then(
      (s) => {
        if (!live) return s.getTracks().forEach((t) => t.stop());
        stream = s;
        video.current!.srcObject = s;
        const look = () =>
          detector.detect(video.current!).then((found) => {
            if (!live) return;
            if (found[0]) {
              setCode(found[0].rawValue);
              setCamera("off");
            } else timer = window.setTimeout(look, 300);
          }, () => live && (timer = window.setTimeout(look, 300)));
        look();
      },
      () => live && setCamera("blocked"),
    );
    return () => {
      live = false;
      clearTimeout(timer);
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [camera]);

  return (
    <FormDialog
      overline="Nutrition › Scan Barcode"
      title="Scan a barcode"
      subtitle={BarcodeDetectorCtor ? "Point your camera at the barcode, or type the digits under it." : "Type the digits under the barcode."}
      action="Look Up"
      onClose={onClose}
      save={async () => {
        const food = await api.foodByBarcode(must(checkBarcode(code)));
        if (food) onFound(food);
      }}
    >
      {camera === "on" && <video ref={video} className={formCss.camera} autoPlay muted playsInline aria-label="Camera preview" />}
      <TextField
        label="Barcode"
        inputMode="numeric"
        autoFocus
        value={code}
        onChange={setCode}
        placeholder="e.g. 5201054017593"
        helper={camera === "blocked" ? "The camera isn't available. Type the digits instead." : "8 to 14 digits, as printed under the bars."}
      />
      {BarcodeDetectorCtor && (
        <span>
          <Button variant="tertiary" size="sm" onClick={() => setCamera(camera === "on" ? "off" : "on")}>
            {camera === "on" ? "Stop camera" : "Use camera"}
          </Button>
        </span>
      )}
      <Note>Looked up in Open Food Facts (only the barcode is sent). A food found is saved to your foods and opens Log Food.</Note>
      <Help
        items={[
          { q: "There's no Use camera button", a: "Reading barcodes with the camera works in Chrome on Android and on a Mac. In other browsers, type the digits printed under the bars." },
          { q: "The camera doesn't read it", a: "Hold the barcode flat, in good light, a hand's width from the camera. If the browser asked for the camera and you said no, allow it in the site settings next to the address." },
          { q: "The product wasn't found", a: "Not every product is in Open Food Facts. Use Log Food, then Add it as a new food, and copy the numbers from the label. It's saved for next time." },
        ]}
      />
    </FormDialog>
  );
}
