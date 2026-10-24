// Notifications — Desktop 1440 (Figma 72:505).
import { loadNotifications, NOTIFICATION_FILTERS } from "@vitallink/api";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { tokenVar } from "../components/Icon";
import { PageHeader, PillTabs, RowButton, Txt } from "../components/kit";
import { useApi, useScreen } from "../data";
import { ScreenState } from "./ScreenState";
import s from "./Notifications.module.css";

const TABS = ["All", "Health", "Train", "Medication", "Appointments", "Records", "Security"];

// Where each notification's action goes.
const ACTION_ROUTE: Record<string, string> = {
  fitness: "/goals",
  goals: "/goals",
  health: "/records/labs",
  medication: "/medications",
  appointments: "/appointments",
  records: "/privacy",
  security: "/settings",
};

export function Notifications() {
  const state = useScreen(loadNotifications);
  const [tab, setTab] = useState(TABS[0]);
  const navigate = useNavigate();
  const api = useApi();
  // Acting on a notification reads it; the next visit shows it read.
  const open = (id: string, unread: boolean, to: string) => {
    if (unread) api.markNotificationsRead([id]).catch(() => undefined);
    navigate(to);
  };
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  const kinds = NOTIFICATION_FILTERS[tab];
  const items = kinds ? v.items.filter((n) => kinds.includes(n.kind)) : v.items;

  return (
    <>
      <PageHeader title="Notifications" subtitle={v.subtitle} />
      <PillTabs label="Notification filter" tabs={TABS} value={tab} onChange={setTab} />
      <section className={s.list} aria-label="Notifications">
        {items.length === 0 ? (
          <Txt s="bodyM" c="textTertiary" as="p" className={s.empty}>Nothing here.</Txt>
        ) : (
          items.map((n) => (
            <article key={n.id} className={s.item} style={n.unread ? { background: tokenVar("bgCanvas") } : undefined}>
              <span className={s.dot} style={{ background: tokenVar(n.unread ? "bgBrand" : "bgMuted") }} aria-label={n.unread ? "Unread" : undefined} />
              <div className={s.text}>
                <div className={s.tagRow}>
                  <span className={`vl-label-s ${s.tag}`} style={{ background: tokenVar(n.tag.bg), color: tokenVar(n.tag.fg) }}>{n.tag.label}</span>
                  <Txt s="caption" c="textTertiary">{n.when}</Txt>
                </div>
                <Txt s="bodyMStrong" as="h2">{n.title}</Txt>
                <Txt s="bodyS" c="textSecondary" as="p">{n.body}</Txt>
              </div>
              <RowButton onClick={() => open(n.id, n.unread, ACTION_ROUTE[n.kind])}>{n.action}</RowButton>
            </article>
          ))
        )}
      </section>
    </>
  );
}
