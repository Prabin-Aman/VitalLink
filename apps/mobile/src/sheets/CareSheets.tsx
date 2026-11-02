// Add Medication, Book Appointment, Reschedule and Edit Emergency Info — Mobile sheets (Figma 198:317).
import {
  checkAppointment,
  checkContact,
  checkMedication,
  checkReschedule,
  CONDITION_KINDS,
  dateLabel,
  dateTimeIn,
  DOSE_FORMS,
  timeLabel,
  timeOptions,
  todayIn,
  VISIT_LENGTHS,
  VISIT_MODES,
  WEEKDAYS,
  type ConditionItem,
  type EmergencyProfile,
} from "@vitallink/api";
import { useEffect, useState } from "react";
import { useApi } from "../data";
import {
  ChipAction,
  Choices,
  DateField,
  Field,
  FormProblem,
  FormSheet,
  ItemList,
  LinkAction,
  must,
  Note,
  OptionSheet,
  PickField,
  Row,
  TimeField,
  useZone,
} from "../components/Sheet";

// ---------------------------------------------------------------- Add Medication

export function AddMedicationSheet({ onClose }: { onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ name: "", form: "tablet", strength: "", times: ["08:00"], days: WEEKDAYS.map((d) => d.value), prescriber: "", refills: "", instructions: "" });
  const [picking, setPicking] = useState(false);
  const set = (k: "name" | "form" | "strength" | "prescriber" | "refills" | "instructions") => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const addTime = (time: string) => setF((x) => (x.times.includes(time) || x.times.length >= 8 ? x : { ...x, times: [...x.times, time].sort() }));

  return (
    <FormSheet
      overline="Medications › Add"
      title="Add Medication"
      subtitle="VitalLink reminds you at each dose time."
      action="Add Medication"
      onClose={onClose}
      save={async () => {
        await api.addMedication(must(checkMedication(f)));
      }}
    >
      <Field label="Name" value={f.name} onChange={set("name")} placeholder="e.g. Metformin" />
      <Row>
        <PickField label="Form" value={f.form} onChange={set("form")} options={DOSE_FORMS} />
        <Field label="Strength" value={f.strength} onChange={set("strength")} placeholder="e.g. 500 mg" />
      </Row>
      <Choices
        label="Dose times"
        multiple
        options={f.times.map((v) => ({ value: v, label: timeLabel(v) }))}
        selected={f.times}
        onChange={(times) => setF((x) => ({ ...x, times }))}
        extra={<ChipAction label="+ Add time" onPress={() => setPicking(true)} />}
      />
      {picking && <OptionSheet title="Add a dose time" options={timeOptions(15)} value="20:00" onPick={addTime} onClose={() => setPicking(false)} />}
      <Choices label="Days" multiple round options={WEEKDAYS} selected={f.days} onChange={(days) => setF((x) => ({ ...x, days }))} />
      <Row>
        <Field label="Prescriber" value={f.prescriber} onChange={set("prescriber")} placeholder="Optional" />
        <Field label="Refills left" value={f.refills} onChange={set("refills")} keyboard="number-pad" placeholder="Optional" />
      </Row>
      <Field label="Instructions" value={f.instructions} onChange={set("instructions")} placeholder="e.g. Take with food" />
    </FormSheet>
  );
}

// ---------------------------------------------------------------- Book Appointment

export function BookAppointmentSheet({ onClose }: { onClose: () => void }) {
  const tz = useZone();
  return tz ? <BookAppointment tz={tz} onClose={onClose} /> : null;
}

function BookAppointment({ tz, onClose }: { tz: string; onClose: () => void }) {
  const api = useApi();
  const [f, setF] = useState({ clinician: "", specialty: "", mode: "in_person", date: todayIn(tz), time: "10:00", length: "30", location: "", notes: "" });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const remote = f.mode === "telehealth";

  return (
    <FormSheet
      overline="Appointments › Add"
      title="Book Appointment"
      subtitle="Add a visit you've arranged so VitalLink can remind you."
      action="Save Appointment"
      onClose={onClose}
      save={async () => {
        await api.addAppointment(must(checkAppointment(f, tz)));
      }}
    >
      <Field label="Clinician" value={f.clinician} onChange={set("clinician")} placeholder="e.g. Dr. Laura Bennett" />
      <Field label="Specialty" value={f.specialty} onChange={set("specialty")} placeholder="Optional" />
      <Choices label="Visit" options={VISIT_MODES} selected={[f.mode]} onChange={([v]) => set("mode")(v)} />
      <Row>
        <DateField label="Date" value={f.date} onChange={set("date")} tz={tz} />
        <TimeField label="Time" value={f.time} onChange={set("time")} />
      </Row>
      <PickField label="Length" value={f.length} onChange={set("length")} options={VISIT_LENGTHS} />
      <Field
        label={remote ? "Link or app" : "Location"}
        icon={remote ? undefined : "map-pin-16"}
        value={f.location}
        onChange={set("location")}
        placeholder={remote ? "Optional" : "Clinic name and city"}
      />
      <Note>VitalLink doesn't book with the clinic — this keeps the visit in your reminders.</Note>
    </FormSheet>
  );
}

// ---------------------------------------------------------------- Reschedule

export function RescheduleSheet({ id, title, startsAt, onClose }: { id: string; title: string; startsAt: string; onClose: () => void }) {
  const tz = useZone();
  return tz ? <Reschedule tz={tz} id={id} title={title} startsAt={startsAt} onClose={onClose} /> : null;
}

function Reschedule({ tz, id, title, startsAt, onClose }: { tz: string; id: string; title: string; startsAt: string; onClose: () => void }) {
  const api = useApi();
  const now = dateTimeIn(startsAt, tz);
  const [f, setF] = useState(now);

  return (
    <FormSheet
      overline="Appointments › Reschedule"
      title="Reschedule"
      subtitle={`${title} · now ${dateLabel(now.date, true)}, ${timeLabel(now.time)}`}
      action="Save New Time"
      onClose={onClose}
      save={async () => {
        if (f.date === now.date && f.time === now.time) throw new FormProblem("Pick a new date or time.");
        await api.rescheduleAppointment(id, must(checkReschedule(f.date, f.time, tz)));
      }}
    >
      <Row>
        <DateField label="New date" value={f.date} onChange={(date) => setF((x) => ({ ...x, date }))} tz={tz} />
        <TimeField label="New time" value={f.time} onChange={(time) => setF((x) => ({ ...x, time }))} />
      </Row>
      <Note>Let the clinic know too — VitalLink doesn't contact them.</Note>
    </FormSheet>
  );
}

// ---------------------------------------------------------------- Edit Emergency Info

type Contact = EmergencyProfile["contacts"][number];
type NewCondition = { label: string; kind: "chronic" | "allergy" };

export function EditEmergencySheet({ onClose }: { onClose: () => void }) {
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
  const [adding, setAdding] = useState<NewCondition | null>(null);
  const [newConditions, setNewConditions] = useState<NewCondition[]>([]);
  const [contact, setContact] = useState({ name: "", relation: "", phone: "" });
  const kindLabel = (k: NewCondition["kind"]) => (k === "allergy" ? "Allergy" : "Chronic");

  const keepAdding = () => {
    if (!adding) return setAdding({ label: "", kind: "chronic" });
    if (!adding.label.trim()) return;
    setNewConditions((x) => [...x, { label: adding.label.trim(), kind: adding.kind }]);
    setAdding({ label: "", kind: adding.kind });
  };

  return (
    <FormSheet
      overline="Emergency Medical ID › Edit"
      title="Edit Emergency Info"
      subtitle="Medications come from your Medications list."
      action="Save Changes"
      onClose={onClose}
      save={async () => {
        const typed = adding?.label.trim() ? [{ label: adding.label.trim(), kind: adding.kind }] : [];
        const checked = checkContact(contact);
        const addContact = checked === null ? null : must(checked);
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
            caption: [kindLabel(x.kind as NewCondition["kind"]), x.note].filter(Boolean).join(" · "),
            onRemove: () => setConditions((all) => all.filter((y) => y.id !== x.id)),
          })),
          ...newConditions.map((x, i) => ({
            key: `new-${i}`,
            title: x.label,
            caption: `${kindLabel(x.kind)} · new`,
            onRemove: () => setNewConditions((all) => all.filter((_, j) => j !== i)),
          })),
        ]}
        add={
          <>
            {adding && (
              <Row>
                <Field label="Condition or allergy" value={adding.label} onChange={(label) => setAdding({ ...adding, label })} placeholder="e.g. Asthma" autoFocus />
                <PickField label="Kind" value={adding.kind} onChange={(kind) => setAdding({ ...adding, kind: kind as NewCondition["kind"] })} options={CONDITION_KINDS} />
              </Row>
            )}
            <LinkAction label={adding ? "+ Add another" : "+ Add condition or allergy"} onPress={keepAdding} />
          </>
        }
      />
      <ItemList
        label="Emergency contacts"
        empty="No contacts yet."
        items={contacts.map((x, i) => ({
          key: x.id ?? `c-${i}`,
          title: x.name,
          caption: [x.relation, x.primary ? "Primary" : null].filter(Boolean).join(" · "),
          onRemove: () => setContacts((all) => all.filter((y) => y !== x)),
        }))}
      />
      <Field label="Contact name" value={contact.name} onChange={(name) => setContact((x) => ({ ...x, name }))} placeholder="Name" />
      <Row>
        <Field label="Relationship" value={contact.relation} onChange={(relation) => setContact((x) => ({ ...x, relation }))} placeholder="e.g. Sister" />
        <Field label="Phone" icon="phone-16" keyboard="phone-pad" value={contact.phone} onChange={(phone) => setContact((x) => ({ ...x, phone }))} placeholder="Phone" />
      </Row>
      <Note>Fill in a name, relationship and phone to add a contact when you save.</Note>
    </FormSheet>
  );
}
