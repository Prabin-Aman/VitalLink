/// <reference types="vite/client" />
interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  /** Local only: a dev token (DevToken tool) used when the tab has none in sessionStorage. */
  readonly VITE_DEV_TOKEN?: string;
  /** Production sign-in: the Supabase project URL and its publishable (public) key. */
  readonly VITE_SUPABASE_URL?: string;
  readonly VITE_SUPABASE_PUBLISHABLE_KEY?: string;
  /** "1" when the Supabase project has the Google provider set up. */
  readonly VITE_SUPABASE_GOOGLE?: string;
  /** Optional: where Android phone > Download the app points. */
  readonly VITE_ANDROID_APK_URL?: string;
  /** Optional: an iCloud link to a built VitalLink Shortcut; the iPhone dialog then offers it to install. */
  readonly VITE_IPHONE_SHORTCUT_URL?: string;
}
