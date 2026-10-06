import { loadActivity, type WorkoutType } from "@vitallink/api";
import { useState } from "react";
import { StepsChart } from "../components/Charts";
import { Icon, tokenVar, type IconName } from "../components/Icon";
import { AccentDot, Card, Progress, Segmented } from "../components/ui";
import { useScreen } from "../data";
import { HealthPage } from "./health/HealthPage";
import { ScreenState } from "./ScreenState";
import s from "./Activity.module.css";

export const WORKOUT_ICON: Record<WorkoutType, IconName> = {
  walk: "walk-20",
  run: "run-20",
  cycle: "bike-20",
  strength: "dumbbell-20",
  swim: "health-20",
  hiit: "dumbbell-20",
  yoga: "health-20",
  other: "health-20",
};

export function Activity() {
  const state = useScreen(loadActivity);
  const [range, setRange] = useState("Week");
  if (state.status !== "ready") return <ScreenState state={state} />;
  const a = state.data;

  return (
    <HealthPage subtitle="Steps, distance, workouts and movement from Apple Watch and iPhone">
      <div className={s.grid}>
        <div className={s.left}>
          <Card className={s.steps}>
            <div className={s.stepsHead}>
              <div>
                <h2 className="vl-h3">Steps</h2>
                <p className={s.total}>
                  <span className="vl-metric-xl">{a.total}</span>
                  <span className="vl-body-m" style={{ color: tokenVar("textSecondary") }}>
                    this week · {a.dailyAverage} daily average
                  </span>
                </p>
              </div>
              <Segmented wide label="Steps range" options={["Day", "Week", "Month", "6M", "Year"]} value={range} onChange={setRange} />
            </div>
            <div className={s.plot}>
              <StepsChart bars={a.bars} goal={a.goal} goalLabel={a.goalLabel} />
            </div>
          </Card>

          <div className={s.stats}>
            {a.stats.map((st) => (
              <article key={st.key} className={s.stat}>
                <span className={s.statLabel}>
                  <AccentDot color={st.accent} />
                  {st.label}
                </span>
                <span className={s.statValue}>
                  <span className="vl-metric-l">{st.value}</span>
                  <span className="vl-metric-unit" style={{ color: tokenVar("textTertiary") }}>
                    {st.unit}
                  </span>
                </span>
                <span className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                  {st.caption}
                </span>
              </article>
            ))}
          </div>

          {a.workouts && (
            <Card className={s.workouts}>
              <h2 className="vl-h3">Workouts this week</h2>
              <table className={s.table}>
                <caption className="sr-only">Workouts this week</caption>
                <tbody>
                  {a.workouts.map((w) => (
                    <tr key={w.id}>
                      <th scope="row">
                        <span className={s.kind}>
                          <span className={s.kindIcon}>
                            <Icon name={WORKOUT_ICON[w.icon]} color={w.iconColor} />
                          </span>
                          <span>
                            <span className={s.wTitle}>{w.title}</span>
                            <span className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                              {w.when}
                            </span>
                          </span>
                        </span>
                      </th>
                      <td className={s.c1}>{w.duration}</td>
                      <td className={s.c2}>{w.distance}</td>
                      <td className={s.c3}>{w.kcal}</td>
                      <td className={s.c4}>{w.hr}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          )}
        </div>

        <aside className={s.rail}>
          {a.goals && (
            <Card className={s.railCard}>
              <h2 className="vl-h5">Goal progress</h2>
              {a.goals.map((g) => (
                <div key={g.id} className={s.goal}>
                  <div className={s.goalHead}>
                    <span className={s.goalLabel}>{g.label}</span>
                    <span className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                      {g.progress}
                    </span>
                  </div>
                  <Progress fraction={g.fraction} color={g.color} label={g.label} />
                </div>
              ))}
            </Card>
          )}
          <Card className={s.railCard}>
            <h2 className="vl-h5">Streaks</h2>
            <dl className={s.streaks}>
              {a.streaks.map((st) => (
                <div key={st.label}>
                  <dt>{st.label}</dt>
                  <dd>{st.value}</dd>
                </div>
              ))}
            </dl>
          </Card>
        </aside>
      </div>
    </HealthPage>
  );
}
