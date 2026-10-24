import { VitalLinkClient } from "@vitallink/api";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

// No EXPO_PUBLIC_API_URL → mock mode (design data). Until Supabase sign-in is in the app, local runs
// against the backend can pass a dev token in EXPO_PUBLIC_DEV_TOKEN (the API refuses those in production).
const ApiContext = createContext<VitalLinkClient>(new VitalLinkClient());

export function ApiProvider({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new VitalLinkClient({
        baseUrl: process.env.EXPO_PUBLIC_API_URL || undefined,
        getToken: async () => process.env.EXPO_PUBLIC_DEV_TOKEN || null,
      }),
  );
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

/** The client, for screens that write (mark a dose taken…). */
export const useApi = () => useContext(ApiContext);

export type Loadable<T> = { status: "loading" } | { status: "error"; error: Error } | { status: "ready"; data: T };

export function useScreen<T>(load: (api: VitalLinkClient) => Promise<T>): Loadable<T> {
  return useReloadableScreen(load)[0];
}

/** Like useScreen, plus `reload()` to fetch again after a write. */
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
