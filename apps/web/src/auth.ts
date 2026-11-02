// Sign-in. Production uses Supabase Auth (VITE_SUPABASE_URL + VITE_SUPABASE_PUBLISHABLE_KEY): Supabase keeps
// and refreshes the session, and the API verifies its access tokens. Without them (local and test) the API's
// own email/password accounts sign in and the portal keeps their 12-hour token itself.
import { ApiError, type SignUpBody, type VitalLinkClient } from "@vitallink/api";
import { createClient, type SupabaseClient } from "@supabase/supabase-js";

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL;
const SUPABASE_KEY = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY;

// ---- Where a session lives: "Remember me" keeps it in localStorage, otherwise only for this tab.
const REMEMBER = "vl.remember";
const store = (s: () => Storage) => ({
  get: (k: string) => {
    try {
      return s().getItem(k);
    } catch {
      return null;
    }
  },
  set: (k: string, v: string) => {
    try {
      s().setItem(k, v);
    } catch {
      // storage blocked: the session can't be kept, so the user signs in again next time
    }
  },
  remove: (k: string) => {
    try {
      s().removeItem(k);
    } catch {
      // nothing stored
    }
  },
});
const local = store(() => localStorage);
const tab = store(() => sessionStorage);
const remembering = () => local.get(REMEMBER) !== "0";
const setRemembering = (on: boolean) => local.set(REMEMBER, on ? "1" : "0");

/** Supabase's session storage, following Remember me. */
const SESSION_KEY = "vl.supabase";
const sessionStore = {
  getItem: (k: string) => tab.get(k) ?? local.get(k),
  setItem: (k: string, v: string) => {
    (remembering() ? local : tab).set(k, v);
    (remembering() ? tab : local).remove(k);
  },
  removeItem: (k: string) => {
    tab.remove(k);
    local.remove(k);
  },
};

export const supabase: SupabaseClient | null =
  SUPABASE_URL && SUPABASE_KEY
    ? createClient(SUPABASE_URL, SUPABASE_KEY, {
        auth: { storage: sessionStore, storageKey: SESSION_KEY, persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
      })
    : null;

/** Resolves once Supabase has read any session from the address bar (email links, Google), so the first render knows who's signed in. */
export const authReady: Promise<void> = supabase ? supabase.auth.getSession().then(() => undefined, () => undefined) : Promise.resolve();

// ---- Local accounts (POST /v1/auth/*): the API's token, kept as it is.
const KEY = "vl.token";
const localToken = () => tab.get(KEY) ?? local.get(KEY);
/** Keeps a local account's token (sign-in, Google through the API). */
export function keepLocalToken(token: string, remember: boolean) {
  endSession();
  (remember ? local : tab).set(KEY, token);
}

/** The token for API calls; Supabase refreshes its own before it expires. */
export async function accessToken(): Promise<string | null> {
  if (supabase) return (await supabase.auth.getSession()).data.session?.access_token ?? null;
  return localToken();
}

/** Whether someone is signed in, without waiting (the shell's redirect). */
export const hasSession = () => (supabase ? sessionStore.getItem(SESSION_KEY) !== null : localToken() !== null);

/** Forgets the session in this browser. */
export function endSession() {
  tab.remove(KEY);
  local.remove(KEY);
  if (supabase) {
    sessionStore.removeItem(SESSION_KEY);
    void supabase.auth.signOut({ scope: "local" }).catch(() => undefined);
  }
}

/** Supabase's messages, in the portal's words. */
function problem(e: { message: string; status?: number; code?: string }): Error {
  const m = e.message.toLowerCase();
  if (m.includes("invalid login credentials")) return new Error("That email and password don't match an account.");
  if (m.includes("email not confirmed")) return new Error("Confirm your email first: open the link we sent you, then sign in.");
  if (m.includes("already registered") || e.code === "user_already_exists") return new Error("An account already uses this email. Sign in instead.");
  if (m.includes("rate limit") || e.status === 429) return new Error("Too many attempts. Wait a few minutes and try again.");
  if (m.includes("password")) return new Error(e.message);
  return new Error("Sign-in isn't reachable right now. Try again in a moment.");
}

const origin = () => window.location.origin;

export async function signIn(api: VitalLinkClient, email: string, password: string, remember: boolean) {
  if (supabase) {
    setRemembering(remember);
    const { error } = await supabase.auth.signInWithPassword({ email, password });
    if (error) throw problem(error);
    return;
  }
  const r = await api.signIn(email, password);
  keepLocalToken(r!.access_token, remember);
}

// With email confirmation on, Supabase signs the person in only once they open the link, so the profile
// details wait in this browser until then (applyPendingProfile).
const PENDING = "vl.signup.pending";

/** "signed-in", or "confirm-email" when the person has to open the link Supabase sent first. */
export async function signUp(api: VitalLinkClient, body: SignUpBody): Promise<"signed-in" | "confirm-email"> {
  if (supabase) {
    setRemembering(true);
    const { email, password, first_name, last_name, ...profile } = body;
    const { data, error } = await supabase.auth.signUp({
      email,
      password,
      options: { data: { first_name, last_name }, emailRedirectTo: `${origin()}/create-account` },
    });
    if (error) throw problem(error);
    // Supabase answers an address that's already registered with a user that has no identities, and sends nothing.
    if (data.user && data.user.identities?.length === 0) throw problem({ message: "already registered" });
    if (Object.keys(profile).length) local.set(PENDING, JSON.stringify(profile));
    if (!data.session) return "confirm-email";
    await applyPendingProfile(api);
    return "signed-in";
  }
  const r = await api.signUp(body);
  keepLocalToken(r!.access_token, true);
  return "signed-in";
}

/** Saves the sign-up details that waited for email confirmation. Safe to call on every visit. */
export async function applyPendingProfile(api: VitalLinkClient) {
  const raw = local.get(PENDING);
  if (!raw || !hasSession()) return;
  try {
    await api.updateAccount(JSON.parse(raw));
  } catch (e) {
    // A detail the API refuses shouldn't block the account; the person can fix it in Settings.
    if (!(e instanceof ApiError)) throw e;
  }
  local.remove(PENDING);
}

export async function resendConfirmation(email: string) {
  if (!supabase) return;
  const { error } = await supabase.auth.resend({ type: "signup", email, options: { emailRedirectTo: `${origin()}/create-account` } });
  if (error) throw problem(error);
}

export async function forgotPassword(api: VitalLinkClient, email: string) {
  if (supabase) {
    const { error } = await supabase.auth.resetPasswordForEmail(email, { redirectTo: `${origin()}/reset-password` });
    // Like the API, never say whether the address has an account; only a rate limit is worth reporting.
    if (error && error.status === 429) throw problem(error);
    return;
  }
  await api.forgotPassword(email);
}

/** Supabase: the link from the email has already signed the person in (a recovery session). Local: the link's token. */
export const resetNeedsToken = !supabase;

export async function resetPassword(api: VitalLinkClient, token: string, password: string) {
  if (supabase) {
    if (!hasSession()) throw new Error("This link has expired or was already used. Ask for a new one from Forgot password.");
    const { error } = await supabase.auth.updateUser({ password });
    if (error) throw problem(error);
    return;
  }
  const r = await api.resetPassword(token, password);
  if (!r) throw new Error("Password reset works when the portal runs with the API.");
  keepLocalToken(r.access_token, true);
}

export async function changePassword(api: VitalLinkClient, current: string, next: string) {
  if (supabase) {
    const email = (await supabase.auth.getSession()).data.session?.user.email;
    if (!email) throw new Error("Sign in again to change your password.");
    // Supabase changes a password with just the session, so check the current one first.
    const check = await supabase.auth.signInWithPassword({ email, password: current });
    if (check.error) throw new Error(check.error.message.toLowerCase().includes("invalid") ? "Your current password isn't right." : problem(check.error).message);
    const { error } = await supabase.auth.updateUser({ password: next });
    if (error) throw problem(error);
    return;
  }
  await api.changePassword(current, next);
}

/** Google through Supabase, when the project has the Google provider set up (VITE_SUPABASE_GOOGLE=1). */
export const supabaseGoogle = !!supabase && import.meta.env.VITE_SUPABASE_GOOGLE === "1";

export async function signInWithGoogle(remember: boolean) {
  if (!supabase) return;
  setRemembering(remember);
  const { error } = await supabase.auth.signInWithOAuth({ provider: "google", options: { redirectTo: `${origin()}/` } });
  if (error) throw problem(error);
}
