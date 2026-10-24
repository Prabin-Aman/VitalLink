// Medications — Desktop 1440 (Figma 62:685).
import { loadMedications } from "@vitallink/api";
import { tokenVar } from "../components/Icon";
import { Action, PageHeader, RowButton, TintCard, Txt } from "../components/kit";
import { Chip } from "../components/ui";
import { useApi, useReloadableScreen } from "../data";
import { ScreenState } from "./ScreenState";
import t from "../components/Table.module.css";
import k from "./Lists.module.css";

// Column widths from the header row (196/100/140/165/145/90/130/110 inside 20px padding).
const COLS = [216, 100, 140, 165, 145, 90, 130, 130];

export function Medications() {
  const api = useApi();
  const [state, reload] = useReloadableScreen(loadMedications);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const markTaken = (id: string, at: string) => {
    api.logDose(id, at, "taken").then(reload, (e: Error) => window.alert(`Couldn't save: ${e.message}`));
  };

  return (
    <>
      <PageHeader title="Medications" subtitle={v.subtitle} actions={<Action primary>Add Medication</Action>} />
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
                    <Txt s="bodyMStrong">{r.name}</Txt>
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
                  <RowButton onClick={r.canMarkTaken ? () => markTaken(r.id, r.doseAt) : undefined}>{r.action}</RowButton>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
