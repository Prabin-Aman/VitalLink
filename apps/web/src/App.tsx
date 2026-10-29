import { loadShell } from "@vitallink/api";
import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { AppShell } from "./components/AppShell";
import { tokenVar } from "./components/Icon";
import { useScreen } from "./data";
import { Activity } from "./screens/Activity";
import { Appointments } from "./screens/Appointments";
import { CreateAccount } from "./screens/CreateAccount";
import { Devices } from "./screens/Devices";
import { DocumentViewer } from "./screens/DocumentViewer";
import { Emergency } from "./screens/Emergency";
import { Family } from "./screens/Family";
import { LabResults } from "./screens/LabResults";
import { Library } from "./screens/Library";
import { Medications } from "./screens/Medications";
import { Messages } from "./screens/Messages";
import { Notifications } from "./screens/Notifications";
import { Settings } from "./screens/Settings";
import { Dashboard } from "./screens/Dashboard";
import { Goals } from "./screens/Goals";
import { Home } from "./screens/Home";
import { Nutrition } from "./screens/Nutrition";
import { Privacy } from "./screens/Privacy";
import { Records } from "./screens/Records";
import { SignIn } from "./screens/SignIn";
import { Workouts } from "./screens/Workouts";
import { Body } from "./screens/health/Body";
import { Digital } from "./screens/health/Digital";
import { Heart } from "./screens/health/Heart";
import { Mental } from "./screens/health/Mental";
import { HealthOverview } from "./screens/health/Overview";
import { Respiratory } from "./screens/health/Respiratory";
import { Sleep } from "./screens/health/Sleep";
import { Vitals } from "./screens/health/Vitals";

function Shell() {
  const me = useScreen(loadShell);
  const who = me.status === "ready" ? me.data : { name: "", initials: "", email: "" };
  return (
    <AppShell name={who.name} initials={who.initials} email={who.email}>
      <Outlet />
    </AppShell>
  );
}

function NotBuilt() {
  return (
    <p className="vl-body-m" style={{ color: tokenVar("textTertiary") }}>
      This screen is in the next batch.
    </p>
  );
}

export function App() {
  return (
    <Routes>
      <Route path="/welcome" element={<Home />} />
      <Route path="/sign-in" element={<SignIn />} />
      <Route path="/create-account" element={<CreateAccount />} />
      <Route element={<Shell />}>
        <Route index element={<Dashboard />} />
        <Route path="health" element={<Navigate to="/health/overview" replace />} />
        <Route path="health/overview" element={<HealthOverview />} />
        <Route path="health/vitals" element={<Vitals />} />
        <Route path="health/activity" element={<Activity />} />
        <Route path="health/sleep" element={<Sleep />} />
        <Route path="health/heart" element={<Heart />} />
        <Route path="health/respiratory" element={<Respiratory />} />
        <Route path="health/body" element={<Body />} />
        <Route path="health/mental" element={<Mental />} />
        <Route path="health/digital" element={<Digital />} />
        <Route path="workouts" element={<Workouts />} />
        <Route path="nutrition" element={<Nutrition />} />
        <Route path="goals" element={<Goals />} />
        <Route path="records" element={<Records />} />
        <Route path="records/labs" element={<LabResults />} />
        <Route path="records/labs/:panel" element={<LabResults />} />
        <Route path="records/:id" element={<DocumentViewer />} />
        <Route path="medications" element={<Medications />} />
        <Route path="appointments" element={<Appointments />} />
        <Route path="library" element={<Library />} />
        <Route path="family" element={<Family />} />
        <Route path="messages" element={<Messages />} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="emergency" element={<Emergency />} />
        <Route path="devices" element={<Devices />} />
        <Route path="privacy" element={<Privacy />} />
        <Route path="settings" element={<Settings />} />
        <Route path="*" element={<NotBuilt />} />
      </Route>
    </Routes>
  );
}
