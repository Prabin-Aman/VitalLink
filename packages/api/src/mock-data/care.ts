// Sample data for the care screens (web 62:685, 70:53, 72:206, 72:505, 71:401,
// 73:308, 73:635, 71:104, 74:760 · mobile 150:2 – 151:1098).
// "Now" is Sun 13 Sep 2026, 8:45 AM, America/Chicago.
import type {
  AccountProfile,
  AppNotification,
  Appointment,
  Conversation,
  Device,
  EmergencyProfile,
  FamilyMember,
  Library,
  MedicationsOverview,
  SyncStatus,
} from "../types";

export const medicationsOverview: MedicationsOverview = {
  doses_today: { taken: 2, total: 5 },
  prescriptions: [
    { id: "rx1", name: "Metformin", form: "Tablet", strength: "500 mg", frequency: "Twice daily", prescriber: "Dr. Laura Bennett", refills_left: 3, dose: { at: "2026-09-13T08:00:00-05:00", status: "taken" }, refill_due: false },
    { id: "rx2", name: "Atorvastatin", form: "Tablet", strength: "10 mg", frequency: "Once daily", prescriber: "Dr. Laura Bennett", refills_left: 2, dose: { at: "2026-09-13T14:00:00-05:00", status: "due" }, refill_due: false },
    { id: "rx3", name: "Vitamin D3", form: "Capsule", strength: "2000 IU", frequency: "Once daily", prescriber: "Dr. Marcus Hale", refills_left: 5, dose: { at: "2026-09-13T20:00:00-05:00", status: "due" }, refill_due: false },
    { id: "rx4", name: "Lisinopril", form: "Tablet", strength: "10 mg", frequency: "Once daily", prescriber: "Dr. Laura Bennett", refills_left: 1, dose: { at: "2026-09-12T21:00:00-05:00", status: "missed" }, refill_due: false },
    { id: "rx5", name: "Ferrous Sulfate", form: "Tablet", strength: "325 mg", frequency: "Once daily", prescriber: "Dr. Marcus Hale", refills_left: 0, dose: { at: "2026-09-14T08:00:00-05:00", status: "due" }, refill_due: true },
  ],
};

export const appointments: Appointment[] = [
  { id: "a1", clinician: "Dr. Laura Bennett", specialty: "Cardiology", starts_at: "2026-09-15T10:30:00-05:00", mode: "in_person", location: "Trinity Valley Medical Center, Dallas", status: "confirmed" },
  { id: "a2", clinician: "Dr. Marcus Hale", specialty: "Internal Medicine", starts_at: "2026-09-18T15:00:00-05:00", mode: "telehealth", location: "Telehealth video visit", status: "confirmed" },
  { id: "a3", clinician: "Dr. Priya Raman", specialty: "Endocrinology", starts_at: "2026-09-28T09:15:00-05:00", mode: "in_person", location: "Oak Cliff Community Hospital", status: "pending" },
  { id: "a0", clinician: "Dr. Marcus Hale", specialty: "Internal Medicine", starts_at: "2026-09-09T11:00:00-05:00", mode: "in_person", location: "Oak Cliff Community Hospital", status: "completed" },
];

export const conversations: Conversation[] = [
  {
    id: "c1",
    with: "Dr. Laura Bennett",
    role: "Cardiology · Trinity Valley",
    role_long: "Cardiology · Trinity Valley Medical Center",
    tone: "teal",
    unread: false,
    last_at: "2026-09-13T06:45:00-05:00",
    preview: "Your ferritin is slightly low — let’s recheck in 8 weeks.",
    messages: [
      { id: "m1", from_me: false, at: "2026-09-12T09:12:00-05:00", body: "Hi Alex — I have reviewed your CBC from 11 September. Most values look good." },
      { id: "m2", from_me: false, at: "2026-09-12T09:12:00-05:00", body: "Ferritin came back at 28 ng/mL, just under the reference range, and neutrophils are mildly elevated at 78%. Neither is alarming on its own." },
      { id: "m3", from_me: true, at: "2026-09-12T09:41:00-05:00", body: "Should I be doing anything differently in the meantime?" },
      { id: "m4", from_me: false, at: "2026-09-12T10:04:00-05:00", body: "Continue the ferrous sulfate and add iron-rich food where you can. I would like to recheck in 8 weeks — I have attached the lab order.", attachment: { name: "Lab order · CBC + Ferritin · PDF" } },
    ],
  },
  { id: "c2", with: "Coach Maya Reed", role: "Strength & conditioning", role_long: "Strength & conditioning coach", tone: "energy", unread: true, last_at: "2026-09-12T08:45:00-05:00", preview: "Nice work on the deadlift progression — let us add a deload week.", messages: [] },
  { id: "c3", with: "Dr. Marcus Hale", role: "Internal Medicine", role_long: "Internal Medicine", tone: "teal", unread: false, last_at: "2026-09-10T08:45:00-05:00", preview: "Refill request approved. Pharmacy notified.", messages: [] },
  { id: "c4", with: "VitalLink Support", role: "Account & privacy", role_long: "Account & privacy", tone: "neutral", unread: false, last_at: "2026-09-06T08:45:00-05:00", preview: "We have updated your data sharing preferences.", messages: [] },
];

export const notifications: AppNotification[] = [
  { id: "n1", kind: "fitness", at: "2026-09-13T08:27:00-05:00", title: "Weekly training goal at risk", body: "You are 2 workouts behind your weekly target with 2 days left.", action: "View goal", read: false },
  { id: "n2", kind: "health", at: "2026-09-13T06:45:00-05:00", title: "New lab result available", body: "Complete Blood Count from Trinity Valley Medical Center. 1 value outside range.", short_body: "Complete Blood Count. 1 value outside range.", action: "View result", read: false },
  { id: "n3", kind: "medication", at: "2026-09-12T21:45:00-05:00", title: "Lisinopril dose missed", body: "Scheduled for 9:00 PM yesterday and not marked taken.", action: "Mark taken", read: false },
  { id: "n4", kind: "appointments", at: "2026-09-12T08:45:00-05:00", title: "Appointment tomorrow", body: "Dr. Laura Bennett, Cardiology, 10:30 AM at Trinity Valley Medical Center.", action: "Directions", read: false },
  { id: "n5", kind: "goals", at: "2026-09-12T08:00:00-05:00", title: "Step goal streak: 14 days", body: "Your longest streak this year.", action: "View goal", read: true },
  { id: "n6", kind: "records", at: "2026-09-09T08:45:00-05:00", title: "Medical record accessed", body: "Dr. Marcus Hale viewed your medication list as part of your 9 Sep visit.", action: "View log", read: true },
  { id: "n7", kind: "security", at: "2026-09-06T08:45:00-05:00", title: "New sign-in from Dallas, TX", body: "Chrome on macOS. If this was not you, secure your account now.", short_body: "Chrome on macOS. Secure your account if this was not you.", action: "Review", read: true },
];

export const family: FamilyMember[] = [
  { id: "f0", name: "Alex Carter", relation: "You", age: 32, tone: "brand", access: null, summary: null },
  {
    id: "f1",
    name: "Susan Carter",
    relation: "Mother",
    age: 68,
    tone: "purple",
    access: {
      level: "full",
      granted_at: "2026-02-12T10:00:00-06:00",
      scopes: [
        { label: "Vitals and health readings", short_label: "Vitals and readings", granted: true },
        { label: "Medications and refills", short_label: "Medications", granted: true },
        { label: "Appointments", short_label: "Appointments", granted: true },
        { label: "Lab results and imaging", short_label: "Lab results", granted: false },
        { label: "Messages with clinicians", short_label: "Messages", granted: false },
      ],
    },
    summary: {
      resting_hr: 61,
      conditions: [
        { label: "Type 2 diabetes", severity: "chronic" },
        { label: "Hypertension", severity: "chronic" },
        { label: "Penicillin allergy", severity: "allergy" },
      ],
      medications: 4,
      next_appointment: "2026-09-22T10:00:00-05:00",
    },
  },
  { id: "f2", name: "Ray Carter", relation: "Father", age: 70, tone: "purple", access: null, summary: null },
  { id: "f3", name: "Emma Carter", relation: "Daughter", age: 9, tone: "purple", access: null, summary: null },
];

export const emergency: EmergencyProfile = {
  name: "Alex Carter",
  age: 32,
  sex: "Male",
  city: "Dallas, TX",
  allergies: ["Penicillin (severe)", "Shellfish"],
  conditions: ["Type 2 diabetes", "Hypertension"],
  medications: ["Metformin 500 mg", "Lisinopril 10 mg"],
  organ_donor: "Yes — registered in Texas",
  preferred_hospital: "Trinity Valley Medical Center, Dallas",
  notes: "Wears a continuous glucose monitor on left arm.",
  contacts: [
    { name: "Susan Carter", relation: "Mother", phone: "(214) 555-0193", primary: true, tone: "purple" },
    { name: "Ray Carter", relation: "Father", phone: "(214) 555-0177", primary: false, tone: "purple" },
    { name: "Dr. Laura Bennett", relation: "Cardiologist", phone: "(469) 555-0110", primary: false, tone: "teal" },
  ],
};

export const devices: Device[] = [
  { id: "d1", name: "Apple Watch S9", kind: "wearable", icon: "devices", last_sync_at: "2026-09-13T08:33:00-05:00", status: "connected", data: ["Heart rate", "Activity", "Sleep", "Blood oxygen"], short_data: ["Heart rate", "Activity", "Sleep"] },
  { id: "d2", name: "Omron BP Monitor", kind: "home_monitor", icon: "monitor", last_sync_at: "2026-09-13T08:42:00-05:00", status: "connected", data: ["Blood pressure", "Pulse"] },
  { id: "d3", name: "Dexcom G7 CGM", kind: "continuous", icon: "droplet", last_sync_at: "2026-09-13T08:42:00-05:00", status: "connected", data: ["Blood glucose", "Trends"] },
  { id: "d4", name: "Withings Scale", kind: "home_monitor", icon: "scale", last_sync_at: "2026-09-12T07:30:00-05:00", status: "connected", data: ["Weight", "BMI", "Body composition"], short_data: ["Weight", "BMI", "Body fat"] },
  { id: "d5", name: "Pulse Oximeter", kind: "home_monitor", icon: "health", last_sync_at: "2026-09-07T08:45:00-05:00", status: "overdue", data: ["Blood oxygen", "Pulse"] },
];

// Phone sync (proposed, Figma 212:3117): the iPhone uploaded 4 minutes before "now".
export const sync: SyncStatus = {
  platforms: [{ platform: "healthkit", last_upload_at: "2026-09-13T08:41:00-05:00", uploads_week: 6, readings_week: 1296, changed_week: 412, devices: 5 }],
  recent: [
    { id: 6, at: "2026-09-13T08:41:00-05:00", platform: "healthkit", submitted: 216, inserted: 216, updated: 0, unchanged: 0 },
    { id: 5, at: "2026-09-13T07:02:00-05:00", platform: "healthkit", submitted: 180, inserted: 36, updated: 0, unchanged: 144 },
    { id: 4, at: "2026-09-12T21:15:00-05:00", platform: "healthkit", submitted: 412, inserted: 140, updated: 20, unchanged: 252 },
  ],
};

export const library: Library = {
  categories: ["Heart", "Train", "Nutrition", "Sleep", "Fitness", "Medication", "Diabetes", "Mental Wellbeing", "First Aid", "Disease Prevention", "Emergency Care"],
  featured: {
    id: "l0",
    title: "Understanding your blood pressure numbers",
    category: "Heart",
    minutes: 8,
    reviewer: "Dr. Laura Bennett",
    summary: "What systolic and diastolic actually measure, what counts as elevated, and when a single high reading is worth acting on.",
  },
  featured_mobile: { id: "l1", title: "How often should you train each muscle group", category: "Training", minutes: 6, reviewer: null },
  trending: [
    { id: "l1", title: "How often should you train each muscle group", category: "Fitness", minutes: 5, reviewer: null },
    { id: "l2", title: "Protein timing: does it actually matter?", category: "Nutrition", minutes: 6, reviewer: null },
    { id: "l3", title: "Reading a glucose curve", category: "Diabetes", minutes: 7, reviewer: null },
    { id: "l4", title: "Why deep sleep drops with age", category: "Sleep", minutes: 4, reviewer: null },
    { id: "l5", title: "Treating a muscle strain", category: "First Aid", minutes: 3, reviewer: null },
    { id: "l6", title: "What to do about a missed dose", category: "Medication", minutes: 4, reviewer: null },
    { id: "l7", title: "Understanding your resting heart rate", category: "Heart", minutes: 6, reviewer: null },
  ],
};

export const account: AccountProfile = {
  first_name: "Alex",
  last_name: "Carter",
  email: "alex.carter@email.com",
  phone: "+12145550148",
  zip: "75201",
  city: "Dallas, TX",
  language: "en-US",
  height_cm: 178,
  member_since: "2026-02-03T10:00:00-06:00",
  units: "metric",
  two_factor: true,
  theme: "light",
  date_of_birth: "1994-04-17",
  sex: null,
  timezone: "America/Chicago",
};
