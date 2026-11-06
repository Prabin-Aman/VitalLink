// What a screen shows while its data loads, or when loading fails.
import type { Loadable } from "../data";
import { tokenVar } from "../components/Icon";

export function ScreenState({ state }: { state: Exclude<Loadable<unknown>, { status: "ready" }> }) {
  if (state.status === "loading") {
    return (
      <p className="vl-body-m" role="status" style={{ color: tokenVar("textTertiary") }}>
        Loading…
      </p>
    );
  }
  return (
    <p className="vl-body-m" role="alert" style={{ color: tokenVar("textCritical") }}>
      Couldn't load this page. {state.error.message}
    </p>
  );
}
