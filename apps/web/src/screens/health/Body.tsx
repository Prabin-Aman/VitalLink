// My Health · Body Composition — Desktop 1440 (Figma 133:1929).
import { loadBody } from "@vitallink/api";
import { LineChart } from "../../components/Charts";
import { Legend, Panel, Split, StatRow, Track, Txt } from "../../components/kit";
import { useScreen } from "../../data";
import { ScreenState } from "../ScreenState";
import { HealthPage } from "./HealthPage";
import t from "../../components/Table.module.css";
import s from "./health.module.css";

export function Body() {
  const state = useScreen(loadBody);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <HealthPage subtitle="Weight, composition and measurements · Withings scale">
      <Split
        rail={
          <>
            <Panel title="How this is measured" level="h5" gap={12}>
              <Txt s="bodyS" c="textSecondary" as="p">
                Body fat and muscle mass come from bioelectrical impedance on your Withings scale. Impedance readings shift with hydration and time of day, so weigh at the same time each morning and trust the trend, not any single number.
              </Txt>
            </Panel>
            <Panel title="Body fat goal" level="h5" gap={12}>
              <div className={s.zoneHead}>
                <Txt s="bodySStrong">{v.goal.now}</Txt>
                <Txt s="caption" c="textTertiary">{v.goal.target}</Txt>
              </div>
              <Track fraction={v.goal.fraction} color="goalAtRisk" label="Body fat goal progress" />
              <Txt s="caption" c="textTertiary" as="p">{v.goal.caption}</Txt>
            </Panel>
          </>
        }
      >
        <StatRow stats={v.stats} />
        <Panel title="Weight and body fat over 8 weeks" caption={v.chartCaption} gap={16}>
          <LineChart label="Weight and body fat over 8 weeks" series={v.chart.series} />
          <Legend items={v.chart.legend} />
        </Panel>
        <div className={t.wrap}>
          <table className={t.table}>
            <colgroup>
              <col style={{ width: 230 }} />
              <col style={{ width: 120 }} />
              <col style={{ width: 140 }} />
              <col style={{ width: 120 }} />
              <col style={{ width: 160 }} />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Measurement</th>
                <th scope="col">Current</th>
                <th scope="col">8 weeks ago</th>
                <th scope="col">Change</th>
                <th scope="col">Last taken</th>
              </tr>
            </thead>
            <tbody>
              {v.measurements.map((m) => (
                <tr key={m.name}>
                  <th scope="row"><Txt s="bodyMStrong">{m.name}</Txt></th>
                  <td><Txt s="numeric">{m.current}</Txt></td>
                  <td><Txt s="numeric" c="textTertiary">{m.previous}</Txt></td>
                  <td><Txt s="numeric" c={m.changeColor}>{m.change}</Txt></td>
                  <td><Txt s="table" c="textSecondary">{m.taken}</Txt></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Split>
    </HealthPage>
  );
}
