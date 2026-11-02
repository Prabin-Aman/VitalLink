// Sample records from Medical Records 143:1538, Lab Results 61:53 and Document Viewer 143:1955.
import type { LabPanel, RecordsOverview } from "../types";

export const recordsOverview: RecordsOverview = {
  counts: { lab: 14, prescription: 9, imaging: 4, visit: 7, vaccination: 6, allergy: 2, procedure: 3, upload: 3 },
  recent: [
    { id: "r1", title: "Complete Blood Count", type: "lab", source: "Trinity Valley Lab", note: null, date: "2026-09-11T08:30:00-05:00", shared_with: "Dr. Bennett", panel_id: "cbc" },
    { id: "r2", title: "Lipid Profile", type: "lab", source: "Trinity Valley Lab", note: null, date: "2026-09-11T08:30:00-05:00", shared_with: null, panel_id: "lipid" },
    { id: "r3", title: "Metformin 500 mg", type: "prescription", source: "Dr. Laura Bennett", note: "active prescription", date: "2026-09-02T10:00:00-05:00", shared_with: null, panel_id: null },
    { id: "r4", title: "Chest X-ray", type: "imaging", source: "Oak Cliff Imaging", note: null, date: "2026-08-18T10:00:00-05:00", shared_with: "Dr. Bennett", panel_id: null },
    { id: "r5", title: "Annual physical summary", type: "visit", source: "Dr. Marcus Hale", note: "visit note", date: "2026-08-04T10:00:00-05:00", shared_with: null, panel_id: null },
    { id: "r6", title: "Influenza vaccination", type: "vaccination", source: "City Pharmacy", note: "record card", date: "2025-10-12T10:00:00-05:00", shared_with: null, panel_id: null },
  ],
  shares: [
    { grantee: "Dr. Laura Bennett", records: 2, scope: null, expires_at: "2026-09-25T00:00:00-05:00" },
    { grantee: "Susan Carter", records: null, scope: "Vitals and medications", expires_at: null },
  ],
};

export const labPanels: LabPanel[] = [
  {
    id: "cbc",
    name: "Complete Blood Count",
    short_name: "CBC",
    facility: "Trinity Valley Medical Center",
    laboratory: "Trinity Valley Laboratory",
    ordered_by: "Dr. Laura Bennett",
    collected_at: "2026-09-11T08:30:00-05:00",
    reported_at: "2026-09-12T07:00:00-05:00",
    added_at: "2026-09-12T09:00:00-05:00",
    file: { name: "CBC-11Sep2026.pdf", size_kb: 214 },
    results: [
      { name: "Hemoglobin", value: 14.2, unit: "g/dL", low: 13, high: 17, previous: 14.0, decimals: 1, interpretation: "normal" },
      { name: "White Blood Cells", value: 6.8, unit: "10³/µL", low: 4.5, high: 11, previous: 7.1, decimals: 1, interpretation: "normal" },
      { name: "Platelets", value: 243, unit: "10³/µL", low: 150, high: 400, previous: 238, decimals: 0, interpretation: "normal" },
      { name: "Hematocrit", value: 42.1, unit: "%", low: 38, high: 50, previous: 41.6, decimals: 1, interpretation: "normal" },
      { name: "Red Blood Cells", value: 4.9, unit: "10⁶/µL", low: 4.5, high: 5.9, previous: 4.9, decimals: 1, interpretation: "normal" },
      { name: "MCV", value: 86, unit: "fL", low: 80, high: 100, previous: 85, decimals: 0, interpretation: "normal" },
      { name: "Ferritin", value: 28, unit: "ng/mL", low: 30, high: 400, previous: 34, decimals: 0, interpretation: "borderline" },
      { name: "Neutrophils", value: 78, unit: "%", low: 40, high: 75, previous: 71, decimals: 0, interpretation: "abnormal" },
    ],
    report_results: ["Hemoglobin", "White Blood Cells", "Platelets", "Ferritin", "Neutrophils"],
    note: "Ferritin low. Dr. Bennett wants a recheck in 8 weeks. Started ferrous sulfate 12 Sep.",
  },
];
