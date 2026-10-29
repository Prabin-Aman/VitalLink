// Emergency Medical ID — Desktop 1440 (Figma 73:308).
import { loadEmergency } from "@vitallink/api";
import qr from "../../../../packages/assets/emergency-qr.json";
import { Icon, tokenVar } from "../components/Icon";
import { PageHeader, Panel, RowButton, Txt } from "../components/kit";
import { Avatar } from "../components/ui";
import { useScreen } from "../data";
import { ScreenState } from "./ScreenState";
import s from "./Emergency.module.css";
import { useState } from "react";
import { EditEmergencyDialog } from "../forms/CareForms";

export function Emergency() {
  const [form, setForm] = useState(false);
  const state = useScreen(loadEmergency);
  if (state.status !== "ready") return <ScreenState state={state} />;
  const v = state.data;

  return (
    <>
      <PageHeader title="Emergency Medical ID" subtitle="Visible to first responders without unlocking your account. Keep it short and accurate." />
      <div className={s.split}>
        <section className={s.card} aria-label="Emergency medical ID card">
          <div className={s.top}>
            <span className={s.alert}>
              <Icon name="emergency-36" color="textOnBrand" />
            </span>
            <div className={s.who}>
              <Txt s="overline" c="textEnergy">EMERGENCY MEDICAL ID</Txt>
              <Txt s="h2" c="textInverse" as="h2">{v.name}</Txt>
              <Txt s="bodyS" c="textSecondary">{v.meta}</Txt>
            </div>
          </div>
          <hr className={s.rule} />
          <dl className={s.fields}>
            {v.fields.map((f) => (
              <div key={f.key}>
                <Txt s="labelS" c="textTertiary" as="dt">{f.label.toUpperCase()}</Txt>
                <Txt s="bodyMStrong" c={f.color} as="dd">{f.value}</Txt>
              </div>
            ))}
          </dl>
        </section>

        <div className={s.right}>
          <Panel title="Emergency contacts" level="h5" gap={12}>
            {v.contacts.map((c) => (
              <div key={c.name} className={s.contact}>
                <Avatar initials={c.initials} tone={c.tone} size={34} fontSize={13} />
                <div className={s.grow}>
                  <Txt s="bodySStrong">{c.title}</Txt>
                  <Txt s="caption" c="textTertiary">{c.caption}</Txt>
                </div>
                <a href={`tel:${c.phone.replace(/\D/g, "")}`} className={`vl-label-m ${s.call}`}>Call</a>
              </div>
            ))}
          </Panel>
          <Panel gap={12}>
            <div className={s.share}>
              <svg width={104} height={104} viewBox="0 0 104 104" role="img" aria-label="Emergency QR code" className={s.qr}>
                <rect width={104} height={104} rx={10} fill={tokenVar("bgSubtle")} />
                {qr.cells.map(([x, y]) => (
                  <rect key={`${x}-${y}`} x={12 + x * 10} y={12 + y * 10} width={9} height={9} fill={tokenVar("textPrimary")} />
                ))}
              </svg>
              <div className={s.shareText}>
                <Txt s="h5" as="h2">Emergency QR code</Txt>
                <Txt s="bodyS" c="textSecondary" as="p">Scanning shows this card only — no login, no access to the rest of your record.</Txt>
                <div className={s.btns}>
                  <RowButton onClick={() => window.print()}>Print Card</RowButton>
                  <RowButton onClick={() => window.print()}>Download PDF</RowButton>
                  <RowButton onClick={() => setForm(true)}>Edit Information</RowButton>
                  {form && <EditEmergencyDialog onClose={() => setForm(false)} />}
                </div>
              </div>
            </div>
          </Panel>
        </div>
      </div>
    </>
  );
}
