// Add Medication (Figma 197:206), Book Appointment (197:278), Reschedule (197:359) and
// Edit Emergency Info (197:520) — Dialog 560.
import {
  checkAppointment,
  checkContact,
  checkMedication,
  checkReschedule,
  CONDITION_KINDS,
  dateTimeIn,
  dayMonth,
  DOSE_FORMS,
  timeLabel,
  timeOfDay,
  todayIn,
  VISIT_LENGTHS,
  VISIT_MODES,
  WEEKDAYS,
  weekday,
  type ConditionItem,
  type EmergencyProfile,
} from "@vitallink/api";
import { useEffect, useState } from "react";
import { Choices, FormDialog, FormProblem, ItemList, must, Note, Row, SelectField, TextField, useZone } from "../components/Form";
import { Button } from "../components/ui";
import { useApi } from "../data";

// ---------------------------------------------------------------- Add Medication

export function AddMedicationDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ name: "", form: "tablet", strength: "", times: ["08:00"], days: WEEKDAYS.map((d) => d.value), prescriber: "", refills: "", instructions: "" });
  const [newTime, setNewTime] = useState("20:00");
  const set = (k: "name" | "form" | "strength" | "prescriber" | "refills" | "instructions") => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const addTime = () => setF((x) => (x.times.includes(newTime) || x.times.length >= 8 ? x : { ...x, times: [...x.times, newTime].sort() }));

  return (
    <FormDialog
      overline="Medications › Add Medication"
      title="Add Medication"
      subtitle="VitalLink reminds you at each dose time."
      action="Add Medication"
      onClose={onClose}
      save={async () => {
        await api.addMedication(must(checkMedication(f)));
      }}
    >
      <TextField label="Name" value={f.name} onChange={set("name")} placeholder="e.g. Metformin" autoFocus />
      <Row>
        <SelectField label="Form" value={f.form} onChange={set("form")} options={DOSE_FORMS} />
        <TextField label="Strength" value={f.strength} onChange={set("strength")} placeholder="e.g. 500 mg" />
      </Row>
      <Choices
        label="Dose times"
        multiple
        options={f.times.map((t) => ({ value: t, label: timeLabel(t) }))}
        selected={f.times}
        onChange={(times) => setF((x) => ({ ...x, times }))}
      />
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
        <TextField label="Refills left" value={f.refills} onChange={set("refills")} inputMode="numeric" placeholder="Optional" />
      </Row>
      <TextField label="Instructions" value={f.instructions} onChange={set("instructions")} placeholder="e.g. Take with food" />
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Book Appointment

export function BookAppointmentDialog({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <BookAppointment tz={tz} onClose={onClose} /> : null;
}

function BookAppointment({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ clinician: "", specialty: "", mode: "in_person", date: todayIn(tz), time: "10:00", length: "30", location: "", notes: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  return (
    <FormDialog
      overline="Appointments › Book Appointment"
      title="Book Appointment"
      subtitle="Add a visit you've arranged so VitalLink can remind you."
      action="Save Appointment"
      onClose={onClose}
      save={async () => {
        await api.addAppointment(must(checkAppointment(f, tz)));
      }}
    >
      <Row>
        <TextField label="Clinician" value={f.clinician} onChange={set("clinician")} placeholder="e.g. Dr. Laura Bennett" autoFocus />
        <TextField label="Specialty" value={f.specialty} onChange={set("specialty")} placeholder="Optional" />
      </Row>
      <Choices label="Visit" options={VISIT_MODES} selected={[f.mode]} onChange={([v]) => set("mode")(v)} />
      <Row>
        <TextField label="Date" type="date" icon="appointments-16" value={f.date} min={todayIn(tz)} onChange={set("date")} />
        <TextField label="Time" type="time" icon="clock-16" value={f.time} onChange={set("time")} />
        <SelectField label="Length" value={f.length} onChange={set("length")} options={VISIT_LENGTHS} />
      </Row>
      <TextField
        label={f.mode === "telehealth" ? "Link or app" : "Location"}
        icon={f.mode === "telehealth" ? undefined : "map-pin-16"}
        value={f.location}
        onChange={set("location")}
        placeholder={f.mode === "telehealth" ? "Optional" : "Clinic name and city"}
      />
      <TextField label="Notes" value={f.notes} onChange={set("notes")} placeholder="Optional" />
      <Note>VitalLink doesn't book with the clinic — this keeps the visit in your calendar and reminders.</Note>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Reschedule

export function RescheduleDialog({ id, title, startsAt, onClose }: { id: string; title: string; startsAt: string; onClose: () => void }) {
  const tz = useZone();
  return tz ? <Reschedule tz={tz} id={id} title={title} startsAt={startsAt} onClose={onClose} /> : null;
}

function Reschedule({ tz, id, title, startsAt, onClose }: { tz: string; id: string; title: string; startsAt: string; onClose: () => void }) {
  const api = useApi();
  const now = dateTimeIn(startsAt, tz);
  const [f, setF] = useState(now);
  const when = `${weekday(startsAt, tz)} ${dayMonth(startsAt, tz).replace(/^0/, "")}, ${timeOfDay(startsAt, tz)}`;

  return (
    <FormDialog
      overline="Appointments › Reschedule"
      title="Reschedule"
      subtitle={`${title} · now ${when}`}
      action="Save New Time"
      onClose={onClose}
      save={async () => {
        if (f.date === now.date && f.time === now.time) throw new FormProblem("Pick a new date or time.");
        await api.rescheduleAppointment(id, must(checkReschedule(f.date, f.time, tz)));
      }}
    >
      <Row>
        <TextField label="New date" type="date" icon="appointments-16" value={f.date} min={todayIn(tz)} onChange={(date) => setF((x) => ({ ...x, date }))} />
        <TextField label="New time" type="time" icon="clock-16" value={f.time} onChange={(time) => setF((x) => ({ ...x, time }))} />
      </Row>
      <Note>Let the clinic know too — VitalLink doesn't contact them.</Note>
    </FormDialog>
  );
}

// ---------------------------------------------------------------- Edit Emergency Info

type Contact = EmergencyProfile["contacts"][number];

export function EditEmergencyDialog({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const [start, setStart] = useState<{ conditions: ConditionItem[]; contacts: Contact[] } | null>(null);
  useEffect(() => {
    Promise.all([api.conditions(), api.emergencyProfile()]).then(([conditions, p]) => setStart({ conditions, contacts: p.contacts }), () => setStart({ conditions: [], contacts: [] }));
  }, [api]);
  return start ? <EditEmergency start={start} onClose={onClose} /> : null;
}

function EditEmergency({ start, onClose }: { start: { conditions: ConditionItem[]; contacts: Contact[] }; onClose: () => void }) {
  const api = useApi();
  const [conditions, setConditions] = useState(start.conditions);
  const [contacts, setContacts] = useState(start.contacts);
  const [adding, setAdding] = useState<{ label: string; kind: "chronic" | "allergy" } | null>(null);
  const [newConditions, setNewConditions] = useState<{ label: string; kind: "chronic" | "allergy" }[]>([]);
  const [contact, setContact] = useState({ name: "", relation: "", phone: "" });

  const keepAdding = () => {
    if (!adding) return setAdding({ label: "", kind: "chronic" });
    if (!adding.label.trim()) return;
    setNewConditions((x) => [...x, { label: adding.label.trim(), kind: adding.kind }]);
    setAdding({ label: "", kind: adding.kind });
  };

  return (
    <FormDialog
      overline="Emergency Medical ID › Edit"
      title="Edit Emergency Info"
      subtitle="Shown on your Medical ID. Medications come from your Medications list."
      action="Save Changes"
      onClose={onClose}
      save={async () => {
        const typed = adding?.label.trim() ? [{ label: adding.label.trim(), kind: adding.kind }] : [];
        const c = checkContact(contact);
        const addContact = c === null ? null : must(c);
        if (addContact && contacts.length >= 5) throw new FormProblem("You can list up to 5 emergency contacts. Remove one first.");
        for (const x of start.conditions) if (x.id && !conditions.some((y) => y.id === x.id)) await api.removeCondition(x.id);
        for (const x of start.contacts) if (x.id && !contacts.some((y) => y.id === x.id)) await api.removeContact(x.id);
        for (const x of [...newConditions, ...typed]) await api.addCondition(x.label, x.kind);
        if (addContact) await api.addContact({ ...addContact, primary: contacts.length === 0 });
      }}
    >
      <ItemList
        label="Conditions & allergies"
        empty="None listed."
        items={[
          ...conditions.map((x) => ({
            key: x.id,
            title: x.label,
            caption: [x.kind === "allergy" ? "Allergy" : "Chronic", x.note].filter(Boolean).join(" · "),
            onRemove: () => setConditions((all) => all.filter((y) => y.id !== x.id)),
          })),
          ...newConditions.map((x, i) => ({
            key: `new-${i}`,
            title: x.label,
            caption: `${x.kind === "allergy" ? "Allergy" : "Chronic"} · new`,
            onRemove: () => setNewConditions((all) => all.filter((_, j) => j !== i)),
          })),
        ]}
        add={
          <>
            {adding && (
              <Row>
                <TextField label="Condition or allergy" value={adding.label} onChange={(label) => setAdding({ ...adding, label })} placeholder="e.g. Asthma" autoFocus />
                <SelectField label="Kind" value={adding.kind} onChange={(kind) => setAdding({ ...adding, kind: kind as "chronic" | "allergy" })} options={CONDITION_KINDS} />
              </Row>
            )}
            <span>
              <Button variant="tertiary" size="sm" onClick={keepAdding}>
                {adding ? "+ Add another" : "+ Add condition or allergy"}
              </Button>
            </span>
          </>
        }
      />
      <ItemList
        label="Emergency contacts"
        empty="No contacts yet."
        items={contacts.map((x, i) => ({
          key: x.id ?? `c-${i}`,
          title: x.name,
          caption: [x.relation, x.phone, x.primary ? "Primary" : null].filter(Boolean).join(" · "),
          onRemove: () => setContacts((all) => all.filter((y) => y !== x)),
        }))}
        add={null}
      />
      <Row>
        <TextField label="Contact name" value={contact.name} onChange={(name) => setContact((x) => ({ ...x, name }))} placeholder="Name" />
        <TextField label="Relationship" value={contact.relation} onChange={(relation) => setContact((x) => ({ ...x, relation }))} placeholder="e.g. Sister" />
      </Row>
      <TextField label="Phone" type="tel" icon="phone-16" value={contact.phone} onChange={(phone) => setContact((x) => ({ ...x, phone }))} placeholder="(214) 555-0100" helper="Fill these in to add a contact when you save." />
    </FormDialog>
  );
}
