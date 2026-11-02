import { VitalLinkClient } from "@vitallink/api";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { accessToken, hasSession } from "./auth";

// No VITE_API_URL → mock mode (design data). Set it to point at the backend (http://localhost:8000).
const ApiContext = createContext<VitalLinkClient>(new VitalLinkClient());

export function ApiProvider({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new VitalLinkClient({
        baseUrl: import.meta.env.VITE_API_URL || undefined,
        // The signed-in session (Sign In / Create Account), or VITE_DEV_TOKEN from apps/web/.env.local for local tools.
        getToken: async () => (await accessToken()) ?? import.meta.env.VITE_DEV_TOKEN ?? null,
      }),
  );
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

// Sessions live in auth.ts (Supabase in production, the API's local accounts otherwise).
export { endSession } from "./auth";

/** Live mode with nobody signed in (and no local dev token): the app sends people to Sign In. */
export const needsSignIn = (api: VitalLinkClient) => !api.isMock && !hasSession() && !import.meta.env.VITE_DEV_TOKEN;

/** The client, for screens that write (mark a dose taken, save settings…). */
export const useApi = () => useContext(ApiContext);

/** Fired after a form saves, so every screen on view fetches again. */
const changes = new EventTarget();
export const notifyChanged = () => changes.dispatchEvent(new Event("changed"));

export type Loadable<T> = { status: "loading" } | { status: "error"; error: Error } | { status: "ready"; data: T };

export function useScreen<T>(load: (api: VitalLinkClient) => Promise<T>): Loadable<T> {
  return useReloadableScreen(load)[0];
}

/** Like useScreen, plus `reload()` to fetch again after a write. The old data stays on screen meanwhile. */
export function useReloadableScreen<T>(load: (api: VitalLinkClient) => Promise<T>): [Loadable<T>, () => void] {
  const api = useContext(ApiContext);
  const [state, setState] = useState<Loadable<T>>({ status: "loading" });
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let live = true;
    load(api).then(
      (data) => live && setState({ status: "ready", data }),
      (error: Error) => live && setState({ status: "error", error }),
    );
    return () => {
      live = false;
    };
  }, [api, load, version]);
  const reload = useCallback(() => setVersion((v) => v + 1), []);
  useEffect(() => {
    changes.addEventListener("changed", reload);
    return () => changes.removeEventListener("changed", reload);
  }, [reload]);
  return [state, reload];
}
