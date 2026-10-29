import type { ReactNode } from "react";
import { Link, NavLink } from "react-router-dom";
import { Icon, type IconName } from "./Icon";
import { Logo } from "./ui";
import { ProfileMenu, TopSearch } from "./TopBar";
import s from "./AppShell.module.css";

// Sidebar items in Figma order (desktop Dashboard 12:2, sidebar). Below 1280px the shell follows the
// Tablet 1024 frames (147:5476): a 72px icon sidebar, avatar-only profile, 24px content gutters.
const NAV: { label: string; icon: IconName; to: string }[] = [
  { label: "Dashboard", icon: "dashboard-18", to: "/" },
  { label: "My Health", icon: "health-18", to: "/health" },
  { label: "Workouts", icon: "dumbbell-18", to: "/workouts" },
  { label: "Nutrition", icon: "nutrition-18", to: "/nutrition" },
  { label: "Goals", icon: "goals-18", to: "/goals" },
  { label: "Medical Records", icon: "records-18", to: "/records" },
  { label: "Medications", icon: "medications-18", to: "/medications" },
  { label: "Appointments", icon: "appointments-18", to: "/appointments" },
  { label: "Health Library", icon: "library-18", to: "/library" },
  { label: "Family", icon: "family-18", to: "/family" },
  { label: "Messages", icon: "messages-18", to: "/messages" },
  { label: "Notifications", icon: "notifications-18", to: "/notifications" },
  { label: "Emergency Profile", icon: "emergency-18", to: "/emergency" },
  { label: "Connected Devices", icon: "devices-18", to: "/devices" },
  { label: "Privacy", icon: "privacy-18", to: "/privacy" },
  { label: "Settings", icon: "settings-18", to: "/settings" },
];

export function AppShell({ children, name, initials, email }: { children: ReactNode; name: string; initials: string; email: string }) {
  return (
    <div className={s.shell}>
      <aside className={s.sidebar}>
        <div className={s.brand}>
          <Logo />
        </div>
        <nav className={s.nav} aria-label="Main">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.to === "/"} title={n.label} className={({ isActive }) => (isActive ? `${s.item} ${s.active}` : s.item)}>
              {({ isActive }) => (
                <>
                  <Icon name={n.icon} color={isActive ? "iconBrand" : "iconSubtle"} />
                  <span className={s.label}>{n.label}</span>
                </>
              )}
            </NavLink>
          ))}
        </nav>
        <Link className={s.emergency} to="/emergency" title="Emergency Medical ID">
          <Icon name="emergency-16" color="textEnergy" />
          <span className={s.label}>Emergency Medical ID</span>
        </Link>
      </aside>
      <div className={s.main}>
        <header className={s.topbar}>
          <TopSearch className={s.search} />
          <ProfileMenu name={name} initials={initials} email={email} className={s.profile} labelClass={s.label} />
        </header>
        <main className={s.content}>{children}</main>
      </div>
    </div>
  );
}
