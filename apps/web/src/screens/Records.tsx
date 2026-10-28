// Medical Records — Desktop 1440 (Figma 143:1538).
import { loadRecords, type RecordType } from "@vitallink/api";
import { useState } from "react";
import { Link } from "react-router-dom";
import { Icon } from "../components/Icon";
import { Action, cx, PageHeader, Panel, Txt } from "../components/kit";
import { useScreen } from "../data";
import { ScreenState } from "./ScreenState";
import t from "../components/Table.module.css";
import s from "./Records.module.css";
import { UploadDocumentDialog } from "../forms/RecordForms";

export function Records() {
  const [form, setForm] = useState(false);
  const state = useScreen(loadRecords);
  const [type, setType] = useState<RecordType | null>(null);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const rows = type ? v.rows.filter((r) => r.type === type) : v.rows;

  return (
    <>
      <PageHeader
        title="Medical Records"
        subtitle={v.subtitle}
        actions={
          <>
            <Action>Import Records</Action>
            <Action primary onClick={() => setForm(true)}>Upload Document</Action>
            {form && <UploadDocumentDialog onClose={() => setForm(false)} />}
          </>
        }
      />
      <div className={s.split}>
        <nav className={s.types} aria-label="Record types">
          <Txt s="overline" c="textTertiary">RECORD TYPES</Txt>
          <TypeButton label="All records" count={v.total} on={type === null} onClick={() => setType(null)} />
          {v.types.map((x) =>
            // Lab results have their own screen.
            x.key === "lab" ? (
              <Link key={x.key} to="/records/labs" className={cx("vl-body-m", s.type)}>
                <span className={s.grow}>{x.label}</span>
                <Txt s="caption" c="textTertiary">{x.count}</Txt>
              </Link>
            ) : (
              <TypeButton key={x.key} label={x.label} count={x.count} on={type === x.key} onClick={() => setType(x.key)} />
            ),
          )}
        </nav>

        <div className={cx(t.wrap, s.table)}>
          <table className={t.table}>
            <colgroup>
              <col style={{ width: 266 }} />
              <col style={{ width: 120 }} />
              <col style={{ width: 100 }} />
              <col style={{ width: 102 }} />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Record</th>
                <th scope="col">Type</th>
                <th scope="col">Date</th>
                <th scope="col"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <th scope="row">
                    <div className={s.record}>
                      <span className={s.doc}><Icon name="file-text-16" /></span>
                      <span className={t.name}>
                        <Txt s="bodyMStrong">{r.title}</Txt>
                        <Txt s="caption" c={r.sourceColor}>{r.source}</Txt>
                      </span>
                    </div>
                  </th>
                  <td><Txt s="table" c="textSecondary">{r.typeLabel}</Txt></td>
                  <td><Txt s="numeric" c="textSecondary">{r.date}</Txt></td>
                  <td className={t.right}>
                    <Link to={r.href} className={cx("vl-label-m", s.view)}>View</Link>
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={4}><Txt s="bodyM" c="textTertiary">No recent records of this type.</Txt></td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        <aside className={s.rail}>
          <Panel title="You own these records" level="h5" gap={12}>
            <Txt s="bodyS" c="textSecondary" as="p">
              Records are imported by you or uploaded from a document. Nobody can add to or read your record without a share you created — and you can revoke any share instantly.
            </Txt>
          </Panel>
          <Panel title="Active shares" level="h5" gap={12}>
            {v.shares.map((x) => (
              <div key={x.name} className={s.share}>
                <span className={s.grow}>
                  <Txt s="bodySStrong" as="div">{x.name}</Txt>
                  <Txt s="caption" c="textTertiary" as="div">{x.caption}</Txt>
                </span>
                <button type="button" className={cx("vl-label-m", s.revoke)}>Revoke</button>
              </div>
            ))}
          </Panel>
        </aside>
      </div>
    </>
  );
}

function TypeButton({ label, count, on, onClick }: { label: string; count: number; on: boolean; onClick: () => void }) {
  return (
    <button type="button" className={cx(on ? "vl-body-m-strong" : "vl-body-m", s.type, on && s.typeOn)} aria-current={on || undefined} onClick={onClick}>
      <span className={s.grow}>{label}</span>
      <Txt s="caption" c="textTertiary">{count}</Txt>
    </button>
  );
}
