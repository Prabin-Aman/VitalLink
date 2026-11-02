// Goals — Desktop 1440 (Figma 131:644).
import { loadGoals } from "@vitallink/api";
import { useState } from "react";
import { Icon, tokenVar } from "../../components/Icon";
import { Action, EditTitle, PageHeader, Panel, PillTabs, Split, StatRow, StatusPill, Track, Txt } from "../../components/kit";
import { useScreen } from "../../data";
import { ScreenState } from "../ScreenState";
import s from "../Lists.module.css";
import g from "./Goals.module.css";
import { NewGoalDialog } from "../../forms/FitnessForms";
import { EditGoalDialog, TargetsDialog } from "../../forms/EditForms";

const TABS = ["Active", "Achieved", "Paused", "All"];

export function Goals() {
  const [form, setForm] = useState(false);
  const [editing, setEditing] = useState<string | "targets" | null>(null);
  const state = useScreen(loadGoals);
  const [tab, setTab] = useState(TABS[0]);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;
  // Tabs filter the list; the design shows the Active view, which includes goals achieved this period.
  const rows = tab === "All" ? v.goals : v.goals.filter((x) => (tab === "Active" ? x.status !== "Paused" : x.status === tab));

  return (
    <>
      <PageHeader
        title="Goals"
        subtitle={v.subtitle}
        actions={
          <>
            <Action onClick={() => setEditing("targets")}>Daily Targets</Action>
            <Action onClick={() => setTab("Achieved")}>Goal History</Action>
            <Action primary onClick={() => setForm(true)}>New Goal</Action>
            {form && <NewGoalDialog onClose={() => setForm(false)} />}
            {editing === "targets" && <TargetsDialog onClose={() => setEditing(null)} />}
            {editing && editing !== "targets" && <EditGoalDialog id={editing} onClose={() => setEditing(null)} />}
          </>
        }
      />
      <PillTabs label="Goal filter" tabs={TABS} value={tab} onChange={setTab} />
      <StatRow stats={v.stats} />
      <Split
        rail={
          <>
            <Panel title="Consistency" level="h5" gap={12}>
              <Txt s="caption" c="textTertiary">Last {v.consistency.weeks.length} weeks · goal days met</Txt>
              <div className={g.grid} role="img" aria-label={v.consistency.caption}>
                {v.consistency.weeks.map((w, i) => (
                  <div key={i}>
                    {w.map((met, d) => (
                      <span key={d} style={{ background: tokenVar(met ? "goalAchieved" : "bgSubtle") }} />
                    ))}
                  </div>
                ))}
              </div>
              <Txt s="caption" c="textTertiary" as="p">{v.consistency.caption}</Txt>
            </Panel>
            <Panel title="Recently achieved" level="h5" gap={10}>
              {v.recent.map((r, i) => (
                <div key={`${i}-${r.title}`} className={g.achieved}>
                  <span className={g.badge}>
                    <Icon name="trophy-16" color="goalAchieved" />
                  </span>
                  <div className={s.stack1}>
                    <Txt s="bodySStrong">{r.title}</Txt>
                    <Txt s="caption" c="textTertiary">{r.when}</Txt>
                  </div>
                </div>
              ))}
            </Panel>
          </>
        }
      >
        <Panel title={`${tab} goals`} gap={14}>
          {rows.length === 0 ? (
            <Txt s="bodyM" c="textTertiary" as="p">No {tab.toLowerCase()} goals.</Txt>
          ) : (
            <ul className={s.list}>
              {rows.map((x) => (
                <li key={x.id} className={g.goal}>
                  <div className={g.goalHead}>
                    <div className={g.goalText}>
                      <EditTitle label={`Edit ${x.title}`} onClick={() => setEditing(x.id)}>{x.title}</EditTitle>
                      <Txt s="caption" c="textTertiary">{x.caption}</Txt>
                    </div>
                    <StatusPill label={x.status} color={x.color} />
                  </div>
                  <Track fraction={x.fraction} color={x.color} label={x.title} />
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </Split>
    </>
  );
}
