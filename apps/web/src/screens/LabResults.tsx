// Lab Results — Desktop 1440 (Figma 61:53).
import { labCsv, loadLabResults } from "@vitallink/api";
import { useCallback, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { tokenVar } from "../components/Icon";
import { Action, PageHeader, PillTabs, TintCard, Txt } from "../components/kit";
import { Chip } from "../components/ui";
import { useScreen } from "../data";
import { ScreenState } from "./ScreenState";
import { ShareDialog } from "../forms/ShareForms";
import { saveFile } from "../download";
import t from "../components/Table.module.css";
import k from "./Lists.module.css";

const COLS = [426, 110, 90, 150, 130, 110, 100];

export function LabResults() {
  const [sharing, setSharing] = useState(false);
  const { panel } = useParams();
  const state = useScreen(useCallback((api) => loadLabResults(api, panel), [panel]));
  const nav = useNavigate();
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <>
      <PageHeader
        title="Lab Results"
        subtitle={v.subtitle}
        actions={
          <>
            <Action
              onClick={() => {
                const f = labCsv(v, v.panelName, v.collectedOn);
                // The byte-order mark makes Excel read the file as UTF-8 (ranges use "–").
                saveFile(new Blob(["\uFEFF", f.text], { type: "text/csv;charset=utf-8" }), f.filename);
              }}
            >
              Export
            </Action>
            <Action primary onClick={() => setSharing(true)}>Share with Doctor</Action>
            {sharing && <ShareDialog preset={{ kind: "doctor", scopes: ["records"], overline: "Lab Results › Share with Doctor" }} onClose={() => setSharing(false)} />}
          </>
        }
      />
      {/* Tabs are your own panels, newest first; each uses the panel layout of Figma 61:53. */}
      <PillTabs
        label="Lab panels"
        tabs={v.panels.map((x) => x.label)}
        value={v.panels.find((x) => x.id === v.panelId)?.label ?? ""}
        onChange={(label) => {
          const next = v.panels.find((x) => x.label === label);
          if (next) nav(`/records/labs/${next.id}`);
        }}
      />
      {(
        <>
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
                  <th scope="col">Test</th>
                  <th scope="col">Result</th>
                  <th scope="col">Unit</th>
                  <th scope="col">Reference range</th>
                  <th scope="col">Status</th>
                  <th scope="col">Previous</th>
                  <th scope="col">Trend</th>
                </tr>
              </thead>
              <tbody>
                {v.rows.map((r) => (
                  <tr key={r.name} style={r.rowBg ? { background: tokenVar(r.rowBg) } : undefined}>
                    <th scope="row"><Txt s="bodyMStrong">{r.name}</Txt></th>
                    <td><Txt s="numeric" c={r.valueColor}>{r.value}</Txt></td>
                    <td><Txt s="table" c="textTertiary">{r.unit}</Txt></td>
                    <td><Txt s="numeric" c="textTertiary">{r.range}</Txt></td>
                    <td><Chip chip={r.status} dot /></td>
                    <td><Txt s="numeric" c="textTertiary">{r.previous}</Txt></td>
                    <td>
                      <Txt s="bodyMStrong" title={r.trendLabel}>
                        <span aria-hidden>{r.trend}</span>
                        <span className="sr-only">{r.trendLabel}</span>
                      </Txt>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className={t.foot}>
              <Txt s="caption" c="textTertiary" as="p">
                Reference ranges vary by laboratory and by patient. Results are for your information and do not replace discussion with your clinician.
              </Txt>
            </div>
          </div>
        </>
      )}
    </>
  );
}
