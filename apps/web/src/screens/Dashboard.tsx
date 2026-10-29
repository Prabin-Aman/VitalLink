import { CHART_RANGES, loadBloodPressureRange, loadDashboard, loadStepsRange, thousands } from "@vitallink/api";
import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { BPChart, ScoreRing, WeekBars } from "../components/Charts";
import { Icon, tokenVar } from "../components/Icon";
import { AccentDot, Button, Card, Chip, MetricCard, Segmented } from "../components/ui";
import { useScreen } from "../data";
import { ScreenState } from "./ScreenState";
import s from "./Dashboard.module.css";


export function Dashboard() {
  const nav = useNavigate();
  const state = useScreen(loadDashboard);
  const [stepRange, setStepRange] = useState("W");
  const [bpRange, setBpRange] = useState("W");
  // W is the designed week (loaded with the dashboard); other ranges load when picked.
  const steps = useScreen(useCallback((api) => loadStepsRange(api, stepRange), [stepRange]));
  const bp = useScreen(useCallback((api) => (bpRange === "W" ? Promise.resolve(null) : loadBloodPressureRange(api, bpRange)), [bpRange]));
  if (state.status !== "ready") return <ScreenState state={state} />;
  const d = state.data;
  const stepsVM = steps.status === "ready" ? steps.data : null;
  const bpLines = bp.status === "ready" && bp.data ? bp.data : d.bloodPressure;

  return (
    <>
      <header className={s.heading}>
        <h1 className="vl-h1">
          {d.greeting}, {d.firstName}
        </h1>
        <p className="vl-body-l" style={{ color: tokenVar("textSecondary") }}>
          Here is your health overview for today.
        </p>
      </header>

      {d.score !== null && (
        <section className={s.score} aria-label="Health score">
          <ScoreRing score={d.score} />
          <div className={s.scoreText}>
            <span className="vl-overline" style={{ color: tokenVar("textTertiary") }}>
              HEALTH SCORE
            </span>
            <div className={s.scoreValue}>
              <span className="vl-metric-hero">{d.score}</span>
              <span className="vl-h4" style={{ color: tokenVar("textTertiary") }}>
                / 100
              </span>
            </div>
            <span className="vl-body-m" style={{ color: tokenVar("rangeNormal") }}>
              {d.scoreLabel} · {d.scoreDelta >= 0 ? "up" : "down"} {Math.abs(d.scoreDelta)} points this week
            </span>
          </div>
          <Button size="cta" className={s.scoreCta} onClick={() => nav("/health/overview")}>
            View Health Summary
          </Button>
        </section>
      )}

      <div className={s.grid}>
        <div className={s.left}>
          <div className={s.metrics}>
            {d.metrics.map((m) => (
              <MetricCard key={m.key} m={m} width={232} />
            ))}
          </div>

          <div className={s.charts}>
            <Card className={s.chart}>
              <h2 className="vl-h5">{stepsVM?.title ?? "Activity This Week"}</h2>
              <p className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                {stepsVM?.caption ?? `Steps · daily goal ${thousands(d.steps.goal)}`}
              </p>
              <div className={s.range}>
                <Segmented label="Steps range" options={CHART_RANGES} value={stepRange} onChange={setStepRange} />
              </div>
              <WeekBars bars={stepsVM?.bars ?? d.steps.bars} perPx={stepsVM?.perPx ?? 100} />
            </Card>

            {d.bloodPressure && (
              <Card className={s.chart}>
                <h2 className="vl-h5">Blood Pressure History</h2>
                <p className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                  Systolic / diastolic · mmHg
                </p>
                <div className={s.range}>
                  <Segmented label="Blood pressure range" options={CHART_RANGES} value={bpRange} onChange={setBpRange} />
                </div>
                <div className={s.bpPlot}>
                  {bpLines && bpLines.systolic.length > 0 ? (
                    <BPChart systolic={bpLines.systolic} diastolic={bpLines.diastolic} />
                  ) : (
                    <p className="vl-body-s" style={{ color: tokenVar("textTertiary") }}>No blood pressure readings in this range.</p>
                  )}
                </div>
                <div className={s.legend}>
                  <span><AccentDot color="dataBloodPressure" /> Systolic {d.bloodPressure.latestSys}</span>
                  <span><AccentDot color="dataHeartRate" /> Diastolic {d.bloodPressure.latestDia}</span>
                  <span><AccentDot color="rangeNormal" /> Normal range</span>
                </div>
              </Card>
            )}
          </div>
        </div>

        <aside className={s.rail}>
          {d.medications && (
            <Card className={s.railCard}>
              <h2 className="vl-h5">Today's Medications</h2>
              <ul className={s.meds}>
                {d.medications.map((m) => (
                  <li key={m.id}>
                    <span className={s.medDot} style={{ background: tokenVar(m.taken ? "rangeNormal" : "bgMuted") }} aria-hidden />
                    <div>
                      <p className={s.medTitle} style={{ color: tokenVar(m.taken ? "textTertiary" : "textPrimary") }}>
                        {m.title}
                      </p>
                      <p className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                        {m.detail}
                      </p>
                    </div>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          {d.appointment && (
            <Card className={s.railCard}>
              <h2 className="vl-h5">Upcoming Appointment</h2>
              <p className="vl-body-s-strong">{d.appointment.with}</p>
              <p className="vl-caption" style={{ color: tokenVar("textSecondary") }}>
                {d.appointment.when}
              </p>
            </Card>
          )}

          {d.streak && (
            <Card className={s.railCard}>
              <h2 className="vl-h5">Training Streak</h2>
              <div className={s.streak}>
                <span className={s.streakIcon}>
                  <Icon name="streak-20" color="textCritical" />
                </span>
                <div>
                  <p className={s.medTitle} style={{ color: tokenVar("goalAchieved") }}>
                    {d.streak.days} day streak
                  </p>
                  <p className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                    Longest this year · {d.streak.longest} days
                  </p>
                </div>
              </div>
            </Card>
          )}

          {d.lab && (
            <Card className={s.railCard}>
              <h2 className="vl-h5">Recent Lab Result</h2>
              <div className={s.lab}>
                <span className="vl-numeric">
                  {d.lab.name}&nbsp;&nbsp;{d.lab.value}
                </span>
                <Chip chip={d.lab.chip} />
              </div>
              <p className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                {d.lab.note}
              </p>
            </Card>
          )}
        </aside>
      </div>
    </>
  );
}
