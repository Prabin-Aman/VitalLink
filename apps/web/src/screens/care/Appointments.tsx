// Appointments — Desktop 1440 (Figma 70:53).
import { loadAppointments, type CalendarVM } from "@vitallink/api";
import { useState } from "react";
import { Action, cx, PageHeader, Panel, PillTabs, Split, Txt } from "../../components/kit";
import { Button, Chip } from "../../components/ui";
import { useApi, useReloadableScreen } from "../../data";
import { ScreenState } from "../ScreenState";
import s from "./Appointments.module.css";
import { BookAppointmentDialog, RescheduleDialog } from "../../forms/CareForms";
import { EditAppointmentDialog } from "../../forms/EditForms";

const TABS = ["Upcoming", "Past", "Cancelled"] as const;
const DOW = ["S", "M", "T", "W", "T", "F", "S"];

export function Appointments() {
  const [form, setForm] = useState<{ kind: "book" } | { kind: "edit"; id: string } | { kind: "reschedule"; id: string; title: string; startsAt: string } | null>(null);
  const api = useApi();
  const [state, reload] = useReloadableScreen(loadAppointments);
  const cancel = (id: string, title: string) => {
    if (!confirm(`Cancel ${title}? Let the clinic know too; VitalLink doesn't contact them.`)) return;
    api.cancelAppointment(id).then(reload, (e: Error) => alert(e.message));
  };
  const [tab, setTab] = useState<string>(TABS[0]);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const items = v.items.filter((a) => a.group === tab);

  return (
    <>
      <PageHeader title="Appointments" subtitle={v.subtitle} actions={<><Action primary onClick={() => setForm({ kind: "book" })}>Book Appointment</Action>{form?.kind === "book" && <BookAppointmentDialog onClose={() => setForm(null)} />}</>} />
      {form?.kind === "edit" && <EditAppointmentDialog id={form.id} onClose={() => setForm(null)} />}
      {form?.kind === "reschedule" && <RescheduleDialog id={form.id} title={form.title} startsAt={form.startsAt} onClose={() => setForm(null)} />}
      <PillTabs label="Appointment filter" tabs={[...TABS]} value={tab} onChange={setTab} />
      <Split
        rail={
          <>
            <Calendar cal={v.calendar} />
            <Panel title="Need to see someone new?" level="h5" gap={10}>
              <Txt s="bodyS" c="textSecondary" as="p">
                Search 480+ providers across the Dallas–Fort Worth network by specialty, language and availability.
              </Txt>
              <Button variant="secondary" size="block" className={s.search}>
                Search Doctors
              </Button>
            </Panel>
          </>
        }
      >
        <div className={s.list}>
          {items.length === 0 ? (
            <Txt s="bodyM" c="textTertiary" as="p">No {tab.toLowerCase()} appointments.</Txt>
          ) : (
            items.map((a) => (
              <article key={a.id} className={s.card} aria-label={a.title}>
                <div className={s.date}>
                  <Txt s="labelM" c="textBrand">{a.weekday}</Txt>
                  <Txt s="metricM" c="textBrand">{a.day}</Txt>
                  <Txt s="caption" c="textBrand">{a.month}</Txt>
                </div>
                <div className={s.info}>
                  <Txt s="h5" as="h2">{a.title}</Txt>
                  <Txt s="bodyS" c="textSecondary">{a.detail}</Txt>
                  <div>
                    <Chip chip={a.status} dot />
                  </div>
                </div>
                {a.group === "Upcoming" && (
                  <div className={s.actions}>
                    <button
                      type="button"
                      className={cx("vl-label-m", s.btn, s.primary)}
                      onClick={a.mapsUrl ? () => window.open(a.mapsUrl!, "_blank", "noopener") : undefined}
                    >
                      {a.primary}
                    </button>
                    <div className={s.more}>
                      <button type="button" className={cx("vl-label-m", s.btn)} onClick={() => setForm({ kind: "edit", id: a.id })}>
                        Edit
                      </button>
                      <button
                        type="button"
                        className={cx("vl-label-m", s.btn)}
                        onClick={a.canCancel ? () => setForm({ kind: "reschedule", id: a.id, title: a.title, startsAt: a.startsAt }) : undefined}
                      >
                        Reschedule
                      </button>
                      <button type="button" className={cx("vl-label-m", s.btn)} onClick={a.canCancel ? () => cancel(a.id, a.title) : undefined}>
                        Cancel
                      </button>
                    </div>
                  </div>
                )}
              </article>
            ))
          )}
        </div>
      </Split>
    </>
  );
}

function Calendar({ cal }: { cal: CalendarVM }) {
  return (
    <Panel title={cal.title} level="h5" gap={12}>
      <table className={s.cal}>
        <thead>
          <tr>
            {DOW.map((d, i) => (
              <th key={i} scope="col" className="vl-label-s">
                {d}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {cal.weeks.map((w, i) => (
            <tr key={i}>
              {w.map((d, j) => (
                <td key={j}>
                  {d !== null && (
                    <span
                      className={cx("vl-body-s", s.day, d === cal.today && s.today, d !== cal.today && cal.marked.includes(d) && s.marked)}
                      aria-current={d === cal.today ? "date" : undefined}
                    >
                      {d}
                    </span>
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}
