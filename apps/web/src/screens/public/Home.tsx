// Public Homepage — Desktop 1440 (Figma 99:2).
// Left out on purpose (conflict with the project's hard constraints, flagged to the owner):
// the "For Providers" nav item, the "For organisations" footer column, the
// "HIPAA-aligned" security card, and the testimonials section (blood-bank quotes
// and unverified endorsements). See CLAUDE.md › Open issues.
import { Link } from "react-router-dom";
import type { ColorToken } from "@vitallink/tokens";
import { Icon, tokenVar, type IconName } from "../../components/Icon";
import { cx, Txt } from "../../components/kit";
import { AccentDot, Logo } from "../../components/ui";
import s from "./Home.module.css";

const NAV = ["Health Tracking", "Workouts", "Find a Clinic", "Training Plans", "Health Library", "About", "Contact"];

const MODES: { title: string; body: string; color: ColorToken }[] = [
  { title: "Cardio", body: "Runs, rides, rows: pace, distance, heart-rate zones", color: "fitnessCardio" },
  { title: "Strength", body: "Sets, reps, load and volume per muscle group", color: "fitnessStrength" },
  { title: "Mobility", body: "Stretching, yoga and range-of-motion work", color: "fitnessMobility" },
  { title: "Recovery", body: "Rest days, sleep quality and readiness to train", color: "fitnessRecovery" },
];

const ZONES: { pct: number; label: string; color: ColorToken }[] = [
  { pct: 18, label: "Zone 1 · Recovery", color: "intensityLight" },
  { pct: 34, label: "Zone 2 · Easy", color: "intensityLight" },
  { pct: 26, label: "Zone 3 · Moderate", color: "intensityModerate" },
  { pct: 15, label: "Zone 4 · Hard", color: "intensityVigorous" },
  { pct: 7, label: "Zone 5 · Peak", color: "intensityPeak" },
];

const FEATURES: { title: string; body: string; icon: IconName; alert?: boolean }[] = [
  { title: "Health tracking", body: "Vitals, activity, sleep and screen time from your watch and phone, in one timeline.", icon: "health-22" },
  { title: "Workouts & training", body: "Log strength, cardio and mobility sessions, or sync them automatically from your watch.", icon: "dumbbell-22" },
  { title: "Medical records", body: "Lab results with reference ranges, prescriptions, imaging and visit summaries.", icon: "records-22" },
  { title: "Medication tracking", body: "Doses, reminders, refills and a history your prescriber can actually read.", icon: "medications-22" },
  { title: "Appointments", body: "Book, reschedule, get directions or join a telehealth visit.", icon: "appointments-22" },
  { title: "Emergency Medical ID", body: "Allergies, conditions, medications and contacts visible to responders without unlocking your phone.", icon: "emergency-22", alert: true },
  { title: "Family profiles", body: "Manage a parent or child’s care with permissions they control.", icon: "family-22" },
  { title: "Connected devices", body: "Apple Watch, glucose monitors, blood pressure cuffs and smart scales.", icon: "devices-22" },
  { title: "Privacy controls", body: "See who accessed your record, why, and revoke access at any time.", icon: "privacy-22" },
];

const GOALS: { title: string; detail: string; status: string; color: ColorToken; fraction: number }[] = [
  { title: "Weekly training volume", detail: "4 of 5 sessions", status: "On track", color: "goalOnTrack", fraction: 0.8 },
  { title: "Daily steps", detail: "8,412 of 10,000", status: "On track", color: "goalOnTrack", fraction: 0.84 },
  { title: "Sleep 8 hours", detail: "7h 12m average", status: "At risk", color: "goalAtRisk", fraction: 0.9 },
  { title: "Body fat 17%", detail: "18.4% · down 0.6", status: "Improving", color: "goalAchieved", fraction: 0.62 },
];

const SECURITY: { title: string; body: string; icon: IconName }[] = [
  { title: "Encrypted end to end", body: "Encrypted in transit and at rest. Nobody at VitalLink reads your record.", icon: "privacy-20" },
  { title: "Every access logged", body: "An append-only audit trail shows who opened your record, when, and why.", icon: "clock-20" },
  { title: "Revoke any time", body: "Grant a doctor access for one visit, or a family member ongoing. Withdraw in one tap.", icon: "user-20" },
];

const FAQ: { q: string; a?: string }[] = [
  { q: "Is VitalLink a medical service?", a: "No. VitalLink organises your health and training information. It does not diagnose, treat, or replace a clinician or a coach." },
  { q: "How accurate is the training data?" },
  { q: "Who can see my medical records?" },
  { q: "Can I use it for a family member?" },
  { q: "Does it work with my smartwatch?" },
  { q: "What does it cost?" },
];

const FOOTER: { title: string; links: string[] }[] = [
  { title: "Platform", links: ["Health Tracking", "Workouts", "Health Library", "Emergency Medical ID"] },
  { title: "Find care", links: ["Find a Clinic", "Training Plans", "Find a Clinician"] },
  { title: "Company", links: ["About", "Contact", "Privacy Policy", "Terms of Service"] },
];

const PREVIEW = [
  { label: "Heart Rate", value: "72", unit: "BPM", color: "dataHeartRate" as ColorToken },
  { label: "Blood Pressure", value: "118/76", unit: "mmHg", color: "dataBloodPressure" as ColorToken },
  { label: "Blood Oxygen", value: "98", unit: "%", color: "dataBloodOxygen" as ColorToken },
  { label: "Steps", value: "8,412", unit: "", color: "dataActivity" as ColorToken },
];

function SectionHead({ overline, title, body, center, inverse }: { overline: string; title: string; body?: string; center?: boolean; inverse?: boolean }) {
  return (
    <div className={cx(s.head, center && s.center)}>
      <Txt s="overline" c="textBrand">{overline}</Txt>
      <Txt s="displayM" c={inverse ? "textInverse" : "textPrimary"} as="h2">{title}</Txt>
      {body ? <Txt s="bodyL" c="textSecondary" as="p">{body}</Txt> : null}
    </div>
  );
}

export function Home() {
  return (
    <div className={s.page}>
      <header className={s.nav}>
        <Logo />
        <nav className={s.items} aria-label="Site">
          {NAV.map((n) => (
            <a key={n} href="#" className="vl-body-m">{n}</a>
          ))}
        </nav>
        <div className={s.actions}>
          <Link to="/sign-in" className={cx("vl-label-l", s.ghost)}>Sign In</Link>
          <Link to="/create-account" className={cx("vl-label-l", s.primary)}>Create Account</Link>
        </div>
      </header>

      {/* The dark bands are Figma's light-mode bg/inverse; pinned to light so dark mode doesn't turn them white. */}
      <section className={cx("vl-light", s.band, s.inverse, s.hero)}>
        <div className={s.copy}>
          <span className={s.badge}>
            <AccentDot color="bgEnergy" size={7} />
            <Txt s="labelM" c="textBrand">Built for everyday training and long-term health</Txt>
          </span>
          <Txt s="displayXl" c="textInverse" as="h1">
            Your Health.<br />Your Fitness.<br />Your Progress.
          </Txt>
          {/* Grey text on the navy bands takes the dark theme's grey: Figma's light-mode grey is under 2.5:1 there. */}
          <Txt s="bodyL" c="textSecondary" as="p" className="vl-dark">
            Track your training, understand your body, keep your medical records in one place, and stay prepared for anything, all from one secure platform.
          </Txt>
          <div className={s.buttons}>
            <Link to="/create-account" className={cx("vl-label-l", s.primary, s.big)}>Get Started</Link>
            <Link to="/sign-in" className={cx("vl-label-l", s.outlineInverse, s.big)}>Sign In</Link>
            <Link to="/sign-in" className={cx("vl-label-l", s.primary, s.big)}>Start Workout</Link>
          </div>
          <dl className={s.stats}>
            {[["40+", "connected devices supported"], ["1.2M+", "workouts logged"], ["< 30 sec", "to log a session"]].map(([v, l]) => (
              <div key={l}>
                <Txt s="h2" c="textInverse" as="dt">{v}</Txt>
                <Txt s="caption" c="textTertiary" as="dd" className="vl-dark">{l}</Txt>
              </div>
            ))}
          </dl>
        </div>
        <div className={s.preview} aria-hidden>
          <div className={s.previewHead}>
            <Txt s="h4">Good Morning, Alex</Txt>
            <span className={cx("vl-label-m", s.score)}>Score 82 · Good</span>
          </div>
          <div className={s.tiles}>
            {PREVIEW.map((m) => (
              <div key={m.label} className={s.tile}>
                <span className={s.tileLabel}>
                  <AccentDot color={m.color} size={7} />
                  <Txt s="labelM" c="textSecondary">{m.label}</Txt>
                </span>
                <span className={s.tileValue}>
                  <Txt s="metricM">{m.value}</Txt>
                  {m.unit ? <Txt s="metricUnit" c="textTertiary">{m.unit}</Txt> : null}
                </span>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className={cx(s.band, s.canvas)}>
        <SectionHead overline="TRAINING" title="Every session, every metric, one timeline" body="Log a workout in seconds or let your watch do it. VitalLink connects what you did to how you recovered." />
        <div className={s.modes}>
          {MODES.map((m) => (
            <article key={m.title} className={s.mode}>
              <span className={s.modeAccent} style={{ background: tokenVar(m.color) }} />
              <Txt s="h4" as="h3">{m.title}</Txt>
              <Txt s="bodyS" c="textSecondary" as="p">{m.body}</Txt>
            </article>
          ))}
        </div>
        <div className={s.zones} role="img" aria-label={ZONES.map((z) => `${z.label} ${z.pct}%`).join(", ")}>
          {ZONES.map((z) => (
            <div key={z.label} style={{ background: tokenVar(z.color) }}>
              <Txt s="h4" c="textOnBrand">{z.pct}%</Txt>
              <Txt s="caption" c="textOnBrand">{z.label}</Txt>
            </div>
          ))}
        </div>
        <Txt s="bodyS" c="textTertiary" as="p">Heart-rate zone distribution across this week’s sessions: the single clearest signal of whether training is balanced.</Txt>
      </section>

      <section className={cx(s.band, s.surface)}>
        <SectionHead overline="WHAT YOU GET" title="Everything about your health, in one place" body="Built for the moments that matter: a routine check-up and an emergency at 2am." />
        <div className={s.features}>
          {FEATURES.map((f) => (
            <article key={f.title} className={s.feature}>
              <span className={s.featureTile} style={{ background: tokenVar(f.alert ? "bgEnergySubtle" : "bgBrandSubtle") }}>
                <Icon name={f.icon} color={f.alert ? "iconEnergy" : "iconBrand"} />
              </span>
              <Txt s="h4" as="h3">{f.title}</Txt>
              <Txt s="bodyM" c="textSecondary" as="p">{f.body}</Txt>
            </article>
          ))}
        </div>
      </section>

      <section className={cx("vl-light", s.band, s.inverse)}>
        <div className={s.progress}>
          <div className={s.progressCopy}>
            <Txt s="overline" c="textBrand" className="vl-dark">PROGRESS &amp; GOALS</Txt>
            <Txt s="displayM" c="textInverse" as="h2">Set a target. See whether you are actually hitting it.</Txt>
            <Txt s="bodyL" c="textSecondary" as="p" className="vl-dark">
              Weekly training volume, step targets, sleep duration, body composition. Goals are tracked against real logged data, not self-reported effort, so an amber week is information, not a failure.
            </Txt>
            <div className={s.buttons}>
              <Link to="/sign-in" className={cx("vl-label-l", s.primary, s.big)}>Explore Goals</Link>
              <Link to="/sign-in" className={cx("vl-label-l", s.light, s.big)}>See Training Plans</Link>
            </div>
          </div>
          <div className={s.goalCards}>
            {GOALS.map((g) => (
              <div key={g.title} className={s.goalCard}>
                <div className={s.goalHead}>
                  <Txt s="bodySStrong">{g.title}</Txt>
                  <span className={cx("vl-label-s", s.goalChip)} style={{ color: tokenVar(g.color) }}>{g.status}</span>
                </div>
                <Txt s="caption" c="textTertiary">{g.detail}</Txt>
                <span className={s.goalTrack}>
                  <span style={{ width: `${g.fraction * 100}%`, background: tokenVar(g.color) }} />
                </span>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className={cx(s.band, s.canvas)}>
        <SectionHead overline="SECURITY & PRIVACY" title="Your record. Your permissions." body="Health data is the most sensitive data there is. We treat access as something you grant, not something we assume." />
        <div className={s.security}>
          {SECURITY.map((x) => (
            <article key={x.title} className={s.secCard}>
              <span className={s.secTile}>
                <Icon name={x.icon} color="rangeNormal" />
              </span>
              <Txt s="h5" as="h3">{x.title}</Txt>
              <Txt s="bodyS" c="textSecondary" as="p">{x.body}</Txt>
            </article>
          ))}
        </div>
      </section>

      <section className={cx(s.band, s.canvas, s.faqBand)}>
        <SectionHead overline="FREQUENTLY ASKED" title="Questions worth answering" />
        <div className={s.faq}>
          {FAQ.map((f, i) =>
            f.a ? (
              <details key={f.q} className={s.qa} open={i === 0}>
                <summary>
                  <Txt s="h5">{f.q}</Txt>
                  <Icon name="chevron-right-18" color="iconSubtle" className={s.chev} />
                </summary>
                <Txt s="bodyM" c="textSecondary" as="p">{f.a}</Txt>
              </details>
            ) : (
              <div key={f.q} className={s.qa}>
                <div className={s.q}>
                  <Txt s="h5">{f.q}</Txt>
                  <Icon name="chevron-right-18" color="iconSubtle" />
                </div>
              </div>
            ),
          )}
        </div>
      </section>

      <section className={cx("vl-light", s.band, s.inverse, s.cta)}>
        <Txt s="displayL" c="textInverse" as="h2">Train with the full picture.</Txt>
        <Txt s="bodyL" c="textSecondary" as="p" className="vl-dark">Create a free account, connect your watch, and see how training, sleep and recovery actually move together.</Txt>
        <div className={s.buttons}>
          <Link to="/create-account" className={cx("vl-label-l", s.primary, s.cta48)}>Create Free Account</Link>
          <Link to="/sign-in" className={cx("vl-label-l", s.energy, s.cta48)}>Log a Workout</Link>
        </div>
      </section>

      <footer className={s.footer}>
        <div className={s.footTop}>
          <div className={s.footBrand}>
            <Logo />
            <Txt s="bodyS" c="textTertiary" as="p">A cross-platform health and fitness platform: training, vitals and medical records in one place.</Txt>
          </div>
          {FOOTER.map((col) => (
            <nav key={col.title} className={s.footCol} aria-label={col.title}>
              <Txt s="labelL">{col.title}</Txt>
              {col.links.map((l) => (
                <a key={l} href="#" className="vl-body-s">{l}</a>
              ))}
            </nav>
          ))}
        </div>
        <div className={s.footBottom}>
          <Txt s="caption" c="textTertiary">© 2026 VitalLink · A student capstone project. Not a licensed medical service.</Txt>
          <Txt s="caption" c="textTertiary">VitalLink does not provide medical advice, diagnosis or treatment. In an emergency, call 911.</Txt>
        </div>
      </footer>
    </div>
  );
}
