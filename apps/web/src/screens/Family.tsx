// Family Health — Desktop 1440 (Figma 71:401).
import { loadFamily } from "@vitallink/api";
import { Link } from "react-router-dom";
import { AccentDot, Avatar } from "../components/ui";
import { cx, Panel, StatusPill, TintCard, Txt } from "../components/kit";
import { useState } from "react";
import { useScreen } from "../data";
import { ManageAccessDialog, ShareDialog } from "../forms/ShareForms";
import { ScreenState } from "./ScreenState";
import k from "./Lists.module.css";
import s from "./Family.module.css";

export function Family() {
  const [dialog, setDialog] = useState<"manage" | "add" | null>(null);
  const state = useScreen(loadFamily);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const view = v.viewing;

  return (
    <>
      {view && (
        <section className={s.viewing} aria-label={view.title}>
          <Avatar initials={view.person.initials} tone={view.person.tone} size={40} fontSize={13} />
          <div className={s.grow}>
            <Txt s="h4" c="textBrand" as="h1">{view.title}</Txt>
            <Txt s="bodyS" c="textSecondary">{view.detail}</Txt>
          </div>
          <button type="button" className={cx("vl-label-l", s.manage)} onClick={() => setDialog("manage")}>Manage Access</button>
        </section>
      )}
      <div className={s.split}>
        <nav className={s.profiles} aria-label="Family profiles">
          <Txt s="overline" c="textTertiary" className={s.overline}>PROFILES</Txt>
          {v.members.map((m) => {
            const body = (
              <>
                <Avatar initials={m.initials} tone={m.tone} size={30} fontSize={11} />
                <span className={s.stack}>
                  <Txt s="bodySStrong" c={m.selected ? "textBrand" : "textPrimary"}>{m.name}</Txt>
                  <Txt s="caption" c="textTertiary">{m.caption}</Txt>
                </span>
              </>
            );
            // Your own profile opens your dashboard; others show only what they share.
            return m.caption === "You" ? (
              <Link key={m.id} to="/" className={s.profile}>{body}</Link>
            ) : (
              <div key={m.id} className={cx(s.profile, m.selected && s.selected)} aria-current={m.selected || undefined}>
                {body}
              </div>
            );
          })}
          <button type="button" className={cx("vl-label-m", s.add)} onClick={() => setDialog("add")}>+ Add Family Member</button>
          {dialog === "add" && <ShareDialog preset={{ kind: "family", scopes: ["vitals", "activity"], overline: "Family › Add Family Member", note: "You'll appear on their Family page. To see their data here, they share with you from their own account." }} onClose={() => setDialog(null)} />}
          {dialog === "manage" && <ManageAccessDialog kind="family" onClose={() => setDialog(null)} onShare={() => setDialog("add")} />}
        </nav>

        {view && (
          <div className={s.main}>
            <div className={k.tints}>
              {view.tints.map((x) => (
                <TintCard key={x.key} label={x.label} value={x.value} bg={x.bg} fg={x.fg} />
              ))}
            </div>
            <div className={s.detail}>
              <Panel title="Conditions & allergies" level="h5" gap={12} className={s.half}>
                <ul className={s.rows}>
                  {view.conditions.map((c) => (
                    <li key={c.label}>
                      <AccentDot color={c.color} />
                      <Txt s="bodyM">{c.label}</Txt>
                    </li>
                  ))}
                </ul>
              </Panel>
              <Panel title="Your access" level="h5" gap={12} className={s.half}>
                <ul className={s.rows}>
                  {view.access.map((a) => (
                    <li key={a.label}>
                      <Txt s="bodyM" c={a.granted ? "textPrimary" : "textTertiary"} className={s.grow}>{a.label}</Txt>
                      {a.granted ? (
                        <StatusPill label="Granted" color="rangeNormal" bg="rangeNormalBg" dot={false} />
                      ) : (
                        <StatusPill label="Not shared" color="textTertiary" dot={false} />
                      )}
                    </li>
                  ))}
                </ul>
                <Txt s="caption" c="textTertiary" as="p">{view.note}</Txt>
              </Panel>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
