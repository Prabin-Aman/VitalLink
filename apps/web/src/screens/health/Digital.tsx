// My Health · Digital Wellbeing — Desktop 1440 (Figma 81:834).
import { loadDigital } from "@vitallink/api";
import { useState } from "react";
import { StackedBars } from "../../components/Charts";
import { Legend, Panel, Split, StatRow, StatusPill, Track, Txt } from "../../components/kit";
import { tokenVar } from "../../components/Icon";
import { Segmented } from "../../components/ui";
import { useScreen } from "../../data";
import { ScreenState } from "../ScreenState";
import { HealthPage } from "./HealthPage";
import s from "./health.module.css";

export function Digital() {
  const state = useScreen(loadDigital);
  const [range, setRange] = useState("Week");
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <HealthPage subtitle="Screen time, pickups and notifications from iPhone, and how they track against your sleep">
      <Split
        rail={
          <>
            <Panel title="Screen time vs deep sleep" level="h5" gap={12}>
              <Txt s="bodyS" c="textSecondary" as="p">{v.deepSleep.text}</Txt>
              <div className={s.compare}>
                <div style={{ background: tokenVar("rangeHighBg") }}>
                  <Txt s="caption" c="textSecondary">Heavy late use</Txt>
                  <Txt s="metricM" c="rangeHigh">{v.deepSleep.heavy}</Txt>
                </div>
                <div style={{ background: tokenVar("rangeNormalBg") }}>
                  <Txt s="caption" c="textSecondary">Light late use</Txt>
                  <Txt s="metricM" c="rangeNormal">{v.deepSleep.light}</Txt>
                </div>
              </div>
              <Txt s="caption" c="textTertiary" as="p">An association in your own data, not a clinical finding.</Txt>
            </Panel>
            <Panel title="Downtime & limits" level="h5" gap={12}>
              {v.limits.map((l) => (
                <div key={l.name} className={s.limit}>
                  <span>
                    <Txt s="bodySStrong">{l.name}</Txt>
                    <Txt s="caption" c="textTertiary">{l.detail}</Txt>
                  </span>
                  {l.on ? <StatusPill label="On" color="rangeNormal" bg="rangeNormalBg" dot={false} /> : <StatusPill label="Off" color="textTertiary" dot={false} />}
                </div>
              ))}
            </Panel>
          </>
        }
      >
        <Panel gap={18}>
          <div className={s.zoneHead}>
            <div className={s.chartHead}>
              <Txt s="h3" as="h2">Screen time</Txt>
              <p className={s.headline}>
                <Txt s="metricXl">{v.average}</Txt>
                <Txt s="bodyM" c="rangeNormal">{v.change}</Txt>
              </p>
            </div>
            <Segmented label="Screen time range" options={["Day", "Week", "Month"]} value={range} onChange={setRange} wide />
          </div>
          {/* 22.7px per hour, after-10 PM use under daytime use (plot 81:834). */}
          <StackedBars label="Screen time" scale={22.7} gap={0} radius={0} days={v.days} />
          <Legend items={v.legend} />
        </Panel>
        <StatRow stats={v.stats} />
        <Panel title="Where the time goes" gap={14}>
          {v.categories.map((c) => (
            <div key={c.key} className={s.zone}>
              <div className={s.zoneHead}>
                <span className={s.chartHead} style={{ gap: 1 }}>
                  <Txt s="bodyMStrong">{c.label}</Txt>
                  <Txt s="caption" c="textTertiary">{c.apps}</Txt>
                </span>
                <Txt s="bodyMStrong">{c.time}</Txt>
                <Txt s="caption" c="textTertiary" className={s.share}>{c.share}</Txt>
              </div>
              <Track fraction={c.fraction} color={c.color} label={c.label} />
            </div>
          ))}
        </Panel>
      </Split>
    </HealthPage>
  );
}
