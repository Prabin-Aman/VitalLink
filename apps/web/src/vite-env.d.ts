/// <reference types="vite/client" />
interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  /** Local only: a dev token (DevToken tool) used when the tab has none in sessionStorage. */
  readonly VITE_DEV_TOKEN?: string;
}
