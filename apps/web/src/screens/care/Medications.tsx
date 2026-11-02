// Medications — Desktop 1440 (Figma 62:685).
import { loadMedications } from "@vitallink/api";
import { tokenVar } from "../../components/Icon";
import { Action, EditTitle, PageHeader, RowButton, TintCard, Txt } from "../../components/kit";
import { Chip } from "../../components/ui";
import { useApi, useReloadableScreen } from "../../data";
import { ScreenState } from "../ScreenState";
import t from "../../components/Table.module.css";
import k from "../Lists.module.css";
import { useState } from "react";
import { AddMedicationDialog } from "../../forms/CareForms";
import { EditMedicationDialog } from "../../forms/EditForms";
import { DoseHistoryDialog, RefillDialog } from "../../forms/MoreForms";

// Column widths from the header row (196/100/140/165/145/90/130/110 inside 20px padding).
const COLS = [216, 100, 140, 165, 145, 90, 130, 130];

export function Medications() {
  const [form, setForm] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [rowDialog, setRowDialog] = useState<{ kind: "history" | "refill"; id: string } | null>(null);
  const api = useApi();
  const [state, reload] = useReloadableScreen(loadMedications);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const markTaken = (id: string, at: string) => {
    api.logDose(id, at, "taken").then(reload, (e: Error) => window.alert(`Couldn't save: ${e.message}`));
  };

  return (
    <>
      <PageHeader title="Medications" subtitle={v.subtitle} actions={<><Action primary onClick={() => setForm(true)}>Add Medication</Action>{form && <AddMedicationDialog onClose={() => setForm(false)} />}</>} />
      {editing && <EditMedicationDialog id={editing} onClose={() => setEditing(null)} />}
      {rowDialog?.kind === "history" && <DoseHistoryDialog id={rowDialog.id} onClose={() => setRowDialog(null)} />}
      {rowDialog?.kind === "refill" && <RefillDialog id={rowDialog.id} onClose={() => setRowDialog(null)} />}
      <div className={k.tints}>
        {v.tints.map((x) => (
          <TintCard key={x.key} label={x.label} value={x.value} bg={x.bg} fg={x.fg} />
        ))}
      </div>
      <div className={t.wrap}>
        <table className={t.table}>
          <colgroup>
            {COLS.map((w, i) => (
              <col key={i} style={{ width: w }} />
            ))}
          </colgroup>
          <thead>
            <tr>
              <th scope="col">Medication</th>
              <th scope="col">Dosage</th>
              <th scope="col">Frequency</th>
              <th scope="col">Next dose</th>
              <th scope="col">Prescriber</th>
              <th scope="col">Refills</th>
              <th scope="col">Status</th>
              <th scope="col" className={t.right}>
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {v.rows.map((r) => (
              <tr key={r.id} style={r.rowBg ? { background: tokenVar(r.rowBg) } : undefined}>
                <th scope="row">
                  <div className={t.name}>
                    <EditTitle label={`Edit ${r.name}`} onClick={() => setEditing(r.id)}>{r.name}</EditTitle>
                    <Txt s="caption" c="textTertiary">{r.detail}</Txt>
                  </div>
                </th>
                <td><Txt s="numeric">{r.dose}</Txt></td>
                <td><Txt s="table" c="textSecondary">{r.frequency}</Txt></td>
                <td><Txt s="table" c={r.nextColor}>{r.next}</Txt></td>
                <td><Txt s="table" c="textSecondary">{r.prescriber}</Txt></td>
                <td><Txt s="numeric" c={r.refillsColor}>{r.refills}</Txt></td>
                <td><Chip chip={r.status} dot /></td>
                <td className={t.right}>
                  <RowButton
                    onClick={
                      r.actionKind === "take"
                        ? r.canMarkTaken
                          ? () => markTaken(r.id, r.doseAt)
                          : undefined
                        : () => setRowDialog({ kind: r.actionKind as "history" | "refill", id: r.id })
                    }
                  >
                    {r.action}
                  </RowButton>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
