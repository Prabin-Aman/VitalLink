// Edit and delete — Dialog 560 (Figma page 15): Edit Medication (214:2165), Edit Appointment (214:2210),
// Edit Goal (214:2245), Edit record details (214:2324) and Daily targets (214:2058). Every save is a PATCH
// to the API, which writes the database; mock mode checks the fields and says nothing was saved.
import {
  checkAppointment,
  checkGoalEdit,
  checkMedicationEdit,
  checkTargets,
  dateTimeIn,
  DOSE_FORMS,
  goalEditForm,
  goalUnit,
  medicationForm,
  RECORD_TYPES,
  targetsForm,
  timeLabel,
  todayIn,
  VISIT_LENGTHS,
  VISIT_MODES,
  WEEKDAYS,
  type Appointment,
  type GoalDetail,
  type GoalMetricOption,
  type HealthRecord,
  type MedicationDetail,
  type MedicationForm,
  type Targets,
} from "@vitallink/api";
import { useEffect, useState } from "react";
import { Choices, FormDialog, FormProblem, formStyles, must, Note, Row, SelectField, TextField, useZone } from "../components/Form";
import { Button } from "../components/ui";
import { notifyChanged, useApi } from "../data";

/** Loads what a dialog edits; shows nothing until it arrives (an error closes the dialog with a message). */
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

/** A destructive button left of Cancel: confirms, sends, then reloads every screen. */
export function DangerButton({ label, confirmText, run, onDone }: { label: string; confirmText: string; run: () => Promise<unknown>; onDone: () => void }) {
  const api = useApi();
  return (
    <Button
      variant="tertiary"
      className={formStyles.dangerText}
      onClick={() => {
        if (!confirm(confirmText)) return;
        if (api.isMock) return alert("This is demo data, so nothing changed.");
        run().then(
          () => {
            notifyChanged();
            onDone();
          },
          (e: Error) => alert(e.message),
        );
      }}
    >
      {label}
    </Button>
  );
}

// ---------------------------------------------------------------- Edit Medication

export function EditMedicationDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const api = useApi();
  const m = useLoad(() => api.medication(id), onClose);
  return m ? <EditMedication m={m} onClose={onClose} /> : null;
}

function EditMedication({ m, onClose }: { m: MedicationDetail; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState<MedicationForm>(() => medicationForm(m));
  const [newTime, setNewTime] = useState("20:00");
  const set = (k: "name" | "form" | "strength" | "prescriber" | "refills" | "instructions") => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const addTime = () => setF((x) => (x.times.includes(newTime) || x.times.length >= 8 ? x : { ...x, times: [...x.times, newTime].sort() }));
  const stopped = m.status === "stopped";

  return (
    <FormDialog
      overline={`Medications › ${m.name}`}
      title="Edit Medication"
      subtitle="Changes apply from the next dose. Past doses stay in your history."
      action="Save Changes"
      onClose={onClose}
      save={async () => {
        await api.updateMedication(m.id, must(checkMedicationEdit(f)));
      }}
      extra={
        <>
          <DangerButton label="Delete" confirmText={`Delete ${m.name} and its dose history? To keep the history, stop it instead.`} run={() => api.deleteMedication(m.id)} onDone={onClose} />
          <Button
            variant="secondary"
            onClick={() => {
              if (api.isMock) return alert("This is demo data, so nothing changed.");
              api.updateMedication(m.id, { status: stopped ? "active" : "stopped" }).then(
                () => {
                  notifyChanged();
                  onClose();
                },
                (e: Error) => alert(e.message),
              );
            }}
          >
            {stopped ? "Restart Medication" : "Stop Medication"}
          </Button>
        </>
      }
    >
      <TextField label="Name" value={f.name} onChange={set("name")} />
      <Row>
        <SelectField label="Form" value={f.form} onChange={set("form")} options={DOSE_FORMS} />
        <TextField label="Strength" value={f.strength} onChange={set("strength")} placeholder="e.g. 500 mg" />
      </Row>
      <Choices label="Dose times" multiple options={f.times.map((t) => ({ value: t, label: timeLabel(t) }))} selected={f.times} onChange={(times) => setF((x) => ({ ...x, times }))} />
      <Row>
        <TextField label="Another dose time" type="time" icon="clock-16" value={newTime} onChange={setNewTime} helper="Tap a time above to remove it." />
        <span style={{ alignSelf: "center" }}>
          <Button variant="tertiary" onClick={addTime}>
            + Add time
          </Button>
        </span>
      </Row>
      <Choices label="Days" multiple options={WEEKDAYS} selected={f.days} onChange={(days) => setF((x) => ({ ...x, days }))} />
      <Row>
        <TextField label="Prescriber" value={f.prescriber} onChange={set("prescriber")} placeholder="Optional" />
        <TextField label="Refills left" value={f.refills} onChange={set("refills")} inputMode="numeric" />
      </Row>
      <TextField label="Instructions" value={f.instructions} onChange={set("instructions")} placeholder="e.g. Take with food" />
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Edit Appointment

export function EditAppointmentDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const api = useApi();
  const tz = useZone();
  const a = useLoad(
    () =>
      api.appointments().then((all) => {
        const a = all.find((x) => x.id === id);
        if (!a) throw new Error("That appointment no longer exists.");
        return a;
      }),
    onClose,
  );
  return a && tz ? <EditAppointment a={a} tz={tz} onClose={onClose} /> : null;
}

function EditAppointment({ a, tz, onClose }: { a: Appointment; tz: string; onClose: () => void }) {
  const api = useApi();
  const when = dateTimeIn(a.starts_at, tz);
  const [f, setF] = useState({
    clinician: a.clinician,
    specialty: a.specialty,
    mode: a.mode,
    date: when.date,
    time: when.time,
    length: String(a.duration_min ?? 30),
    location: a.location,
    notes: a.notes ?? "",
  });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const lengths = VISIT_LENGTHS.some((o) => o.value === f.length) ? VISIT_LENGTHS : [...VISIT_LENGTHS, { value: f.length, label: `${f.length} min` }];

  return (
    <FormDialog
      overline={`Appointments › ${a.clinician}`}
      title="Edit Appointment"
      subtitle="Change the visit's details. Reschedule moves it to a new time."
      action="Save Changes"
      onClose={onClose}
      save={async () => {
        const body = must(checkAppointment(f, tz)) as Record<string, unknown>;
        await api.updateAppointment(a.id, { ...body, notes: (body.notes as string | null) ?? "" });
      }}
      extra={<DangerButton label="Delete" confirmText={`Delete the visit with ${a.clinician}? To keep it in your history, cancel it instead.`} run={() => api.deleteAppointment(a.id)} onDone={onClose} />}
    >
      <Row>
        <TextField label="Clinician" value={f.clinician} onChange={set("clinician")} />
        <TextField label="Specialty" value={f.specialty} onChange={set("specialty")} placeholder="Optional" />
      </Row>
      <Choices label="Visit" options={VISIT_MODES} selected={[f.mode]} onChange={([v]) => set("mode")(v)} />
      <Row>
        <TextField label="Date" type="date" icon="appointments-16" value={f.date} onChange={set("date")} />
        <TextField label="Time" type="time" icon="clock-16" value={f.time} onChange={set("time")} />
        <SelectField label="Length" value={f.length} onChange={set("length")} options={lengths} />
      </Row>
      <TextField
        label={f.mode === "telehealth" ? "Link or app" : "Location"}
        icon={f.mode === "telehealth" ? undefined : "map-pin-16"}
        value={f.location}
        onChange={set("location")}
        placeholder={f.mode === "telehealth" ? "Optional" : "Clinic name and city"}
      />
      <TextField label="Notes" value={f.notes} onChange={set("notes")} placeholder="Optional" />
      <Note>VitalLink doesn't book with the clinic. This keeps the visit in your calendar and reminders.</Note>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Edit Goal

export function EditGoalDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const api = useApi();
  const v = useLoad(() => Promise.all([api.goal(id), api.goalMetrics(), api.me()]), onClose);
  return v ? <EditGoal g={v[0]} metrics={v[1]} units={v[2].units ?? "metric"} onClose={onClose} /> : null;
}

const STATUSES = [
  { value: "active", label: "Active" },
  { value: "paused", label: "Paused" },
];

function EditGoal({ g, metrics, units, onClose }: { g: GoalDetail; metrics: GoalMetricOption[]; units: "metric" | "imperial"; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState(() => goalEditForm(g, metrics, units));
  const metric = metrics.find((m) => m.code === g.metric);
  const unit = metric ? goalUnit(metric, units) : null;
  const open = g.lifecycle === "active" || g.lifecycle === "paused";

  return (
    <FormDialog
      overline={`Goals › ${g.short_title}`}
      title="Edit Goal"
      subtitle={g.title}
      action="Save Changes"
      onClose={onClose}
      save={async () => {
        await api.updateGoal(g.id, must(checkGoalEdit(f, g, metrics, units)));
      }}
      extra={<DangerButton label="Delete" confirmText={`Delete “${g.title}”? Its progress history goes too.`} run={() => api.deleteGoal(g.id)} onDone={onClose} />}
    >
      <Row>
        <TextField label="Target" value={f.target} onChange={(target) => setF((x) => ({ ...x, target }))} inputMode="decimal" helper={unit ? [unit.unit, unit.per].filter(Boolean).join(" ") : undefined} autoFocus />
        <TextField label="Ends" type="date" icon="appointments-16" value={f.ends} min={g.starts_on} onChange={(ends) => setF((x) => ({ ...x, ends }))} helper="Optional" />
      </Row>
      {open && <Choices label="Status" options={STATUSES} selected={[f.status]} onChange={([s]) => setF((x) => ({ ...x, status: s as "active" | "paused" }))} />}
      <Note>What a goal tracks can't change. To track something else, create a new goal. Paused goals don't count against your streak.</Note>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Daily targets

export function TargetsDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const t = useLoad(() => api.targets(), onClose);
  return t ? <TargetsForm_ t={t} onClose={onClose} /> : null;
}

function TargetsForm_({ t, onClose }: { t: Targets; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState(() => targetsForm(t));
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormDialog
      overline="Goals › Daily targets"
      title="Daily targets"
      subtitle="The goals your rings, dashboard and streaks count against."
      action="Save Targets"
      onClose={onClose}
      save={async () => {
        await api.updateTargets(must(checkTargets(f)));
      }}
    >
      <Row>
        <TextField label="Steps" value={f.steps} onChange={set("steps")} inputMode="numeric" helper="steps a day" autoFocus />
        <TextField label="Move" value={f.move} onChange={set("move")} inputMode="numeric" helper="kcal a day" />
      </Row>
      <Row>
        <TextField label="Exercise" value={f.exercise} onChange={set("exercise")} inputMode="numeric" helper="minutes a day" />
        <TextField label="Stand" value={f.stand} onChange={set("stand")} inputMode="numeric" helper="hours a day" />
      </Row>
      <Row>
        <TextField label="Sleep" value={f.sleep} onChange={set("sleep")} inputMode="decimal" helper="hours a night" />
        <span />
      </Row>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Edit record details

export function EditRecordDialog({ id, onClose, onDeleted }: { id: string; onClose: () => void; onDeleted?: () => void }) {
  const api = useApi();
  const tz = useZone();
  const r = useLoad(() => api.record(id), onClose);
  return r && tz ? <EditRecord r={r} tz={tz} onClose={onClose} onDeleted={onDeleted ?? onClose} /> : null;
}

function EditRecord({ r, tz, onClose, onDeleted }: { r: HealthRecord; tz: string; onClose: () => void; onDeleted: () => void }) {
  const api = useApi();
  const today = todayIn(tz);
  const [f, setF] = useState({ title: r.title, type: r.type as string, date: r.date.slice(0, 10), source: r.source });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  return (
    <FormDialog
      overline={`Medical Records › ${r.title}`}
      title="Edit details"
      subtitle="Fix the title, type, date or source of this record."
      action="Save Changes"
      onClose={onClose}
      save={async () => {
        if (!f.title.trim()) throw new FormProblem("Enter a title.");
        if (f.date > today) throw new FormProblem("The date can't be in the future.");
        await api.updateRecord(r.id, { title: f.title.trim(), type: f.type, date: f.date, source: f.source.trim() });
      }}
      extra={
        <DangerButton
          label="Delete Record"
          confirmText={`Delete “${r.title}”${r.file ? " and its file" : ""}? Anyone you shared it with loses it too.`}
          run={() => api.deleteRecord(r.id)}
          onDone={onDeleted}
        />
      }
    >
      <TextField label="Title" value={f.title} onChange={set("title")} />
      <Row>
        <SelectField label="Type" value={f.type} onChange={set("type")} options={RECORD_TYPES} />
        <TextField label="Date" type="date" icon="appointments-16" value={f.date} max={today} onChange={set("date")} />
      </Row>
      <TextField label="Source" value={f.source} onChange={set("source")} placeholder="Clinic or lab (optional)" />
    </FormDialog>
  );
}
