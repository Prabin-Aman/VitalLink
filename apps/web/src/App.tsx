// Every route in the portal, and the signed-in shell that decides who may see them.
import { ApiError, loadShell } from "@vitallink/api";
import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { AppShell } from "./components/AppShell";
import { tokenVar } from "./components/Icon";
import { endSession, needsSignIn, useApi, useScreen } from "./data";
import { Activity } from "./screens/health/Activity";
import { Appointments } from "./screens/care/Appointments";
import { CreateAccount } from "./screens/public/CreateAccount";
import { Devices } from "./screens/care/Devices";
import { GoogleCallback } from "./screens/care/GoogleCallback";
import { DocumentViewer } from "./screens/records/DocumentViewer";
import { Emergency } from "./screens/care/Emergency";
import { Family } from "./screens/sharing/Family";
import { LabResults } from "./screens/records/LabResults";
import { Article } from "./screens/library/Article";
import { Library } from "./screens/library/Library";
import { Medications } from "./screens/care/Medications";
import { Notifications } from "./screens/inbox/Notifications";
import { Settings } from "./screens/settings/Settings";
import { SettingsFrame } from "./screens/settings/SettingsFrame";
import { Dashboard } from "./screens/dashboard/Dashboard";
import { Goals } from "./screens/fitness/Goals";
import { Home } from "./screens/public/Home";
import { Nutrition } from "./screens/fitness/Nutrition";
import { Privacy } from "./screens/sharing/Privacy";
import { Records } from "./screens/records/Records";
import { ForgotPassword, ResetPassword, SignIn } from "./screens/public/SignIn";
import { Workouts } from "./screens/fitness/Workouts";
import { Body } from "./screens/health/Body";
import { Digital } from "./screens/health/Digital";
import { Heart } from "./screens/health/Heart";
import { Mental } from "./screens/health/Mental";
import { HealthOverview } from "./screens/health/Overview";
import { Respiratory } from "./screens/health/Respiratory";
import { Sleep } from "./screens/health/Sleep";
import { Vitals } from "./screens/health/Vitals";

function Shell() {
  const api = useApi();
  const me = useScreen(loadShell);
  // Nobody signed in, or the session expired: sign in first (mock mode never needs it).
  if (needsSignIn(api)) return <Navigate to="/sign-in" replace />;
  if (me.status === "error" && me.error instanceof ApiError && me.error.status === 401) {
    endSession();
    return <Navigate to="/sign-in" replace state={{ expired: true }} />;
  }
  // Two-step verification is on and the code hasn't been entered (Google sign-in lands here).
  if (me.status === "error" && me.error instanceof ApiError && me.error.code === "mfa_required") {
    return <Navigate to="/sign-in" replace state={{ code: true }} />;
  }
  // New accounts finish setting up first; Google sign-ups arrive here without having seen the form.
  if (me.status === "ready" && !me.data.onboarded) return <Navigate to="/create-account" replace />;
  const who = me.status === "ready" ? me.data : { name: "", initials: "", email: "", photo: null };
  return (
    <AppShell name={who.name} initials={who.initials} email={who.email} photo={who.photo}>
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
      <Route path="/forgot-password" element={<ForgotPassword />} />
      <Route path="/reset-password" element={<ResetPassword />} />
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
        <Route path="library/:slug" element={<Article />} />
        <Route path="family" element={<Family />} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="emergency" element={<Emergency />} />
        <Route path="devices" element={<SettingsFrame current="Connected Devices"><Devices /></SettingsFrame>} />
        <Route path="devices/google/callback" element={<SettingsFrame current="Connected Devices"><GoogleCallback /></SettingsFrame>} />
        <Route path="privacy" element={<SettingsFrame current="Privacy"><Privacy /></SettingsFrame>} />
        <Route path="settings" element={<Settings />} />
        <Route path="*" element={<NotBuilt />} />
      </Route>
    </Routes>
  );
}
