import { loadPrivacy, type AccessCardVM, type AccessRowVM } from "@vitallink/api";
import type { ColorToken } from "@vitallink/tokens";
import { tokenVar } from "../../components/Icon";
import { Button, Chip } from "../../components/ui";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useScreen } from "../../data";
import { AccessDetailsDialog, ManageAccessDialog, ShareDialog } from "../../forms/ShareForms";
import { ScreenState } from "../ScreenState";
import s from "./Privacy.module.css";

export const TONE: Record<AccessCardVM["tone"], { bg: ColorToken; value: ColorToken }> = {
  default: { bg: "bgSurface", value: "textPrimary" },
  brand: { bg: "bgBrandSubtle", value: "textBrand" },
  critical: { bg: "bgCriticalSubtle", value: "textEnergy" },
  warning: { bg: "rangeBorderlineBg", value: "rangeBorderline" },
};

// Where each card's Manage goes: the people cards open Manage access (Figma 206:1516); the rest link to their page.
const MANAGE: Record<string, { kind: "doctor" | "family" | null } | { to: string }> = {
  doctors: { kind: "doctor" },
  hospitals: { kind: null },
  family: { kind: "family" },
  apps: { kind: null },
  emergency: { to: "/emergency" },
  devices: { to: "/devices" },
};

export function Privacy() {
  const nav = useNavigate();
  const [dialog, setDialog] = useState<{ manage: "doctor" | "family" | null } | { row: AccessRowVM } | "share" | null>(null);
  const state = useScreen(loadPrivacy);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const p = state.data;

  return (
    <>
      <header className={s.heading}>
        <h1 className="vl-h1">Privacy Centre</h1>
        <p className="vl-body-l" style={{ color: tokenVar("textSecondary") }}>
          Who can see your health record, what they looked at, and how to revoke it.
        </p>
      </header>

      <div className={s.cards}>
        {p.cards.map((c) => (
          <section key={c.key} className={s.card} style={{ background: tokenVar(TONE[c.tone].bg) }} aria-label={c.label}>
            <span className="vl-label-m" style={{ color: tokenVar("textSecondary") }}>
              {c.label}
            </span>
            <span className="vl-metric-l" style={{ color: tokenVar(TONE[c.tone].value) }}>
              {c.value}
            </span>
            <span className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
              {c.caption}
            </span>
            <Button
              variant="secondary"
              size="sm"
              className={s.manage}
              onClick={() => {
                const m = MANAGE[c.key];
                if (!m) return;
                if ("to" in m) nav(m.to);
                else setDialog({ manage: m.kind });
              }}
            >
              Manage
            </Button>
          </section>
        ))}
      </div>

      {dialog === "share" && <ShareDialog onClose={() => setDialog(null)} />}
      {dialog && dialog !== "share" && "row" in dialog && <AccessDetailsDialog row={dialog.row} onClose={() => setDialog(null)} />}
      {dialog && dialog !== "share" && "manage" in dialog && <ManageAccessDialog kind={dialog.manage} onClose={() => setDialog(null)} onShare={() => setDialog("share")} />}

      <h2 className="vl-h3">Data access history</h2>
      <div className={s.tableWrap}>
        <table className={s.table}>
          <thead>
            <tr>
              <th scope="col">Entity</th>
              <th scope="col" className={s.cAccessed}>Accessed</th>
              <th scope="col" className={s.cPurpose}>Purpose</th>
              <th scope="col" className={s.cWhen}>Date &amp; time</th>
              <th scope="col" className={s.cStatus}>Status</th>
              <th scope="col" className={s.cAction}>
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {p.log.map((r, i) => (
              <tr key={i} className={r.status.fg === "rangeHigh" ? s.blocked : undefined}>
                <th scope="row">
                  <div className={s.entity}>
                    <span className={s.who}>{r.who}</span>
                    <span className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                      {r.detail}
                    </span>
                  </div>
                </th>
                <td>{r.accessed}</td>
                <td>{r.purpose}</td>
                <td>{r.when}</td>
                <td>
                  <Chip chip={r.status} dot />
                </td>
                <td className={s.cAction}>
                  <Button variant="secondary" size="sm" onClick={() => setDialog({ row: r })} aria-label={`Details: ${r.who}, ${r.when}`}>
                    Details
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
