import { loadDashboard, passwordNeeds, VitalLinkClient } from "@vitallink/api";
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { Icon, tokenVar } from "../../components/Icon";
import { AccentDot, Button, Chip, Logo } from "../../components/ui";
import { forgotPassword, hasSession, keepLocalToken, resetNeedsToken, resetPassword, signIn, signInWithGoogle, supabase, supabaseGoogle } from "../../auth";
import { useApi, useScreen } from "../../data";
import s from "./SignIn.module.css";

// Brand-panel preview card: always the sample dashboard (nobody is signed in on this page).
const SAMPLE = new VitalLinkClient();

function Preview() {
  const state = useScreen(useCallback(() => loadDashboard(SAMPLE), []));
  if (state.status !== "ready") return null;
  const d = state.data;
  const pick = ["heart_rate", "blood_pressure", "oxygen_saturation"];
  const tiles = d.metrics.filter((m) => pick.includes(m.key));
  return (
    <div className={s.preview} aria-hidden>
      <div className={s.previewHead}>
        <span className="vl-h5">Your health today</span>
        {d.score !== null && <Chip chip={{ label: `Score ${d.score} · ${d.scoreLabel}`, fg: "rangeNormal", bg: "rangeNormalBg" }} />}
      </div>
      <div className={s.tiles}>
        {tiles.map((m) => (
          <div key={m.key} className={s.tile}>
            <span className={s.tileLabel}>
              <AccentDot color={m.accent} size={6} />
              {m.key === "oxygen_saturation" ? "Oxygen" : m.label}
            </span>
            <span className={s.tileValue}>
              <span className="vl-metric-m">{m.value}</span>
              <span className="vl-caption" style={{ color: tokenVar("textTertiary") }}>
                {m.unit}
              </span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** The sign-in pages' frame (Figma 24:2): brand panel on the left, the page's form on the right. */
export function AuthLayout({ children, onSubmit }: { children: ReactNode; onSubmit: (e: FormEvent) => void }) {
  return (
    <div className={s.page}>
      <aside className={s.brand}>
        <Logo large inverse />
        <div className={s.pitch}>
          <h1 className="vl-display-m" style={{ color: tokenVar("textInverse") }}>
            One secure place for your health.
          </h1>
          <p className="vl-body-l" style={{ color: tokenVar("textSecondary") }}>
            Track your training and vitals, keep your medical records together, and see how the two affect each other over
            time.
          </p>
          <Preview />
        </div>
        <ul className={s.trust}>
          {["HIPAA-aligned", "End-to-end encrypted", "You control access"].map((t) => (
            <li key={t}>
              <AccentDot color="rangeNormal" size={6} />
              {t}
            </li>
          ))}
        </ul>
      </aside>
      <main className={s.formWrap}>
        <form className={s.form} onSubmit={onSubmit} noValidate>
          {children}
        </form>
      </main>
    </div>
  );
}

function Heading({ title, sub }: { title: string; sub: string }) {
  return (
    <header className={s.formHead}>
      <h2 className="vl-h1">{title}</h2>
      <p className="vl-body-l" style={{ color: tokenVar("textSecondary") }}>
        {sub}
      </p>
    </header>
  );
}

function Problem({ text }: { text: string | null }) {
  return text ? (
    <p role="alert" className="vl-body-s" style={{ color: tokenVar("textCritical") }}>
      {text}
    </p>
  ) : null;
}

// ---------------------------------------------------------------- Sign in with Google

type Gsi = {
  accounts: {
    id: {
      initialize(o: { client_id: string; callback: (r: { credential: string }) => void }): void;
      renderButton(el: HTMLElement, o: Record<string, unknown>): void;
    };
  };
};

/** Loads Google Identity Services once (https://accounts.google.com/gsi/client). */
let gsi: Promise<Gsi> | null = null;
const loadGsi = () =>
  (gsi ??= new Promise<Gsi>((resolve, reject) => {
    const el = Object.assign(document.createElement("script"), { src: "https://accounts.google.com/gsi/client", async: true });
    el.onload = () => resolve((window as unknown as { google: Gsi }).google);
    el.onerror = () => {
      gsi = null;
      reject(new Error("Couldn't load Google sign-in."));
    };
    document.head.append(el);
  }));

/**
 * Google's own button (its branding rules require it) when GOOGLE_CLIENT_ID is set on the API; otherwise the
 * design's button, which says Google sign-in isn't set up.
 */
function GoogleButton({ onToken, onProblem, remember }: { onToken: (credential: string) => void; onProblem: (m: string) => void; remember: boolean }) {
  const api = useApi();
  const box = useRef<HTMLDivElement>(null);
  const [clientId, setClientId] = useState<string | null>(null);
  useEffect(() => {
    // With Supabase, Google goes through Supabase's provider instead (below), not the API's client id.
    if (supabase) return setClientId("");
    api.authConfig().then((c) => setClientId(c.google_client_id), () => setClientId(""));
  }, [api]);
  useEffect(() => {
    if (!clientId || !box.current) return;
    loadGsi().then(
      (g) => {
        g.accounts.id.initialize({ client_id: clientId, callback: (r) => onToken(r.credential) });
        g.accounts.id.renderButton(box.current!, { theme: "outline", size: "large", text: "continue_with", width: box.current!.offsetWidth || 400 });
      },
      (e: Error) => onProblem(e.message),
    );
  }, [clientId, onToken, onProblem]);
  if (clientId) return <div ref={box} className={s.google} />;
  return (
    <Button
      variant="secondary"
      size="block"
      onClick={() =>
        supabaseGoogle
          ? signInWithGoogle(remember).catch((e: Error) => onProblem(e.message))
          : onProblem(
              api.isMock
                ? "Google sign-in works when the portal runs with the API."
                : supabase
                  ? "Google sign-in isn't set up yet. Use your email and password for now."
                  : "Google sign-in isn't set up yet: the API needs a Google OAuth client ID (GOOGLE_CLIENT_ID). Use your email and password for now.",
            )
      }
    >
      <Icon name="user-17" color="iconDefault" />
      Continue with Google
    </Button>
  );
}

// ---------------------------------------------------------------- Sign in (Figma 24:2)

export function SignIn() {
  const nav = useNavigate();
  const api = useApi();
  const expired = (useLocation().state as { expired?: boolean } | null)?.expired;
  const [show, setShow] = useState(false);
  const [remember, setRemember] = useState(true);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(expired ? "Your session has expired. Sign in again." : null);

  const enter = useCallback(
    (token: string) => {
      keepLocalToken(token, remember);
      nav("/");
    },
    [nav, remember],
  );
  const google = useCallback(
    (credential: string) => {
      setError(null);
      api.signInWithGoogle(credential).then((r) => r && enter(r.access_token), (err: Error) => setError(err.message));
    },
    [api, enter],
  );

  // Email and password: Supabase in production, the API's local accounts otherwise. The mock build has no accounts: it just enters.
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (api.isMock) return nav("/");
    if (!email.trim() || !password) return setError("Enter your email and password.");
    setBusy(true);
    setError(null);
    signIn(api, email.trim(), password, remember)
      .then(
        () => nav("/"),
        (err: Error) => setError(err.message),
      )
      .finally(() => setBusy(false));
  };

  return (
    <AuthLayout onSubmit={submit}>
          <Heading title="Welcome back" sub="Sign in to your VitalLink account." />

          <label className={s.field}>
            <span className={s.label}>Email</span>
            <input className={s.input} name="email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
          </label>

          <label className={s.field}>
            <span className={s.label}>Password</span>
            <span className={s.inputWrap}>
              <input
                className={s.input}
                name="password"
                type={show ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <button type="button" className={s.show} onClick={() => setShow((v) => !v)} aria-pressed={show}>
                {show ? "Hide" : "Show"}
                <Icon name="eye-17" color="iconSubtle" />
              </button>
            </span>
          </label>

          <div className={s.row}>
            <label className={s.check}>
              <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
              <span className={s.box} aria-hidden />
              Remember me
            </label>
            <Link className={s.link} to="/forgot-password" state={{ email }}>
              Forgot password?
            </Link>
          </div>

          <Problem text={error} />
          <Button type="submit" size="block" disabled={busy}>
            {busy ? "Signing in…" : "Sign In"}
          </Button>

          <div className={s.or}>
            <span>or</span>
          </div>

          <div className={s.social}>
            <GoogleButton onToken={google} onProblem={setError} remember={remember} />
            <Button
              variant="secondary"
              size="block"
              onClick={() => setError("Sign in with Apple needs Apple's paid developer programme, so VitalLink doesn't offer it. Use Google or your email.")}
            >
              <Icon name="user-17" color="iconDefault" />
              Continue with Apple
            </Button>
          </div>

          <p className={s.footer}>
            New to VitalLink? <Link className={s.link} to="/create-account">Create Account</Link>
          </p>
    </AuthLayout>
  );
}


// ---------------------------------------------------------------- Forgot password (proposed, Figma 216:122 / 216:208)

export function ForgotPassword() {
  const api = useApi();
  const start = (useLocation().state as { email?: string } | null)?.email ?? "";
  const [email, setEmail] = useState(start);
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = (e?: FormEvent) => {
    e?.preventDefault();
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(email.trim())) return setError("Enter the email you signed up with.");
    if (api.isMock) return setError("Password reset works when the portal runs with the API.");
    setBusy(true);
    setError(null);
    forgotPassword(api, email.trim())
      .then(
        () => setSent(true),
        (err: Error) => setError(err.message),
      )
      .finally(() => setBusy(false));
  };

  if (sent)
    return (
      <AuthLayout onSubmit={(e) => e.preventDefault()}>
        <Heading
          title="Check your email"
          sub={`If an account uses ${email.trim()}, a link to reset its password is on its way. The link works for ${supabase ? "an hour" : "30 minutes"}.`}
        />
        <Link to="/sign-in" className={s.linkButton}>
          Back to Sign In
        </Link>
        <p className={s.footer}>
          Didn't get it?{" "}
          <button type="button" className={s.linkInline} onClick={() => send()} disabled={busy}>
            Send again
          </button>
        </p>
      </AuthLayout>
    );

  return (
    <AuthLayout onSubmit={send}>
      <Heading title="Reset your password" sub="Enter the email you signed up with. We'll send a link to choose a new password." />
      <label className={s.field}>
        <span className={s.label}>Email</span>
        <input className={s.input} type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" autoFocus />
      </label>
      <Problem text={error} />
      <Button type="submit" size="block" disabled={busy}>
        {busy ? "Sending…" : "Send reset link"}
      </Button>
      <p className={s.footer}>
        Remembered it? <Link className={s.link} to="/sign-in">Back to Sign In</Link>
      </p>
    </AuthLayout>
  );
}

// ---------------------------------------------------------------- Choose a new password (proposed, Figma 216:294)

export function ResetPassword() {
  const api = useApi();
  const nav = useNavigate();
  const token = useSearchParams()[0].get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  // Supabase: opening the email link already signed the person in for this. Local accounts: the link carries a token.
  const usable = resetNeedsToken ? !!token : hasSession();
  const [error, setError] = useState<string | null>(
    usable ? null : resetNeedsToken ? "This link is missing its code. Open the link from the email again." : "This link has expired or was already used. Ask for a new one.",
  );
  const needs = password ? passwordNeeds(password) : null;

  const save = (e: FormEvent) => {
    e.preventDefault();
    if (!usable) return;
    if (!password || needs) return setError(needs ?? "Choose a new password.");
    if (password !== confirm) return setError("The two passwords don't match.");
    setBusy(true);
    setError(null);
    resetPassword(api, token, password)
      .then(
        () => nav("/"),
        (err: Error) => setError(err.message),
      )
      .finally(() => setBusy(false));
  };

  return (
    <AuthLayout onSubmit={save}>
      <Heading title="Choose a new password" sub="Use at least 12 characters with a number and a symbol. Signing in again uses the new password." />
      <label className={s.field}>
        <span className={s.label}>New password</span>
        <input className={s.input} type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} autoFocus />
        {needs && <span className="vl-caption" style={{ color: tokenVar("textCritical") }}>{needs}</span>}
      </label>
      <label className={s.field}>
        <span className={s.label}>Confirm new password</span>
        <input className={s.input} type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
      </label>
      <Problem text={error} />
      <Button type="submit" size="block" disabled={busy || !usable}>
        {busy ? "Saving…" : "Save and sign in"}
      </Button>
      <p className={s.footer}>
        Link expired? <Link className={s.link} to="/forgot-password">Ask for a new one</Link>
      </p>
    </AuthLayout>
  );
}
