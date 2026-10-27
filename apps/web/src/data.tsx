import { VitalLinkClient } from "@vitallink/api";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

// No VITE_API_URL → mock mode (design data). Set it to point at the backend (http://localhost:8000).
const ApiContext = createContext<VitalLinkClient>(new VitalLinkClient());

export function ApiProvider({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new VitalLinkClient({
        baseUrl: import.meta.env.VITE_API_URL || undefined,
        // Until Supabase sign-in: a dev token from this tab, or VITE_DEV_TOKEN (apps/web/.env.local) for any tab.
        getToken: async () => sessionStorage.getItem("vl.token") ?? import.meta.env.VITE_DEV_TOKEN ?? null,
      }),
  );
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

/** The client, for screens that write (mark a dose taken, save settings…). */
export const useApi = () => useContext(ApiContext);

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
  return [state, reload];
}
