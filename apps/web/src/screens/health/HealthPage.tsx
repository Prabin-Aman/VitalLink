// Shared My Health header and tabs (Heading + Tabs frames, e.g. 77:512).
import { useState, type ReactNode } from "react";
import { Action, PageHeader, PillLinks } from "../../components/kit";
import { useApi } from "../../data";
import { exportMyData } from "../../download";
import { AddReadingDialog } from "../../forms/HealthForms";

export const HEALTH_TABS = [
  { label: "Overview", to: "/health/overview" },
  { label: "Vitals", to: "/health/vitals" },
  { label: "Activity", to: "/health/activity" },
  { label: "Sleep", to: "/health/sleep" },
  { label: "Heart", to: "/health/heart" },
  { label: "Respiratory", to: "/health/respiratory" },
  { label: "Body Composition", to: "/health/body" },
  { label: "Mental Wellbeing", to: "/health/mental" },
  { label: "Digital Wellbeing", to: "/health/digital" },
];

export function HealthPage({ subtitle, children }: { subtitle: ReactNode; children: ReactNode }) {
  const [form, setForm] = useState(false);
  const api = useApi();
  return (
    <>
      <PageHeader
        title="My Health"
        subtitle={subtitle}
        actions={
          <>
            <Action compact onClick={() => exportMyData(api)}>Export Data</Action>
            <Action compact primary onClick={() => setForm(true)}>
              Add Reading
            </Action>
            {form && <AddReadingDialog onClose={() => setForm(false)} />}
          </>
        }
      />
      <PillLinks label="Health categories" tabs={HEALTH_TABS} />
      {children}
    </>
  );
}
