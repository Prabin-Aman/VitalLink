# VitalLink web portal

React 19, Vite 5, React Router 6 and CSS modules. Hosted on Vercel as static files. Commands are in the
[root README](../../README.md).

## Structure

```
src/
  main.tsx        Start-up: theme, waits for sign-in to be read, renders the app
  App.tsx         Every route, and the signed-in shell's redirects
  auth.ts         Sign-in: Supabase in production, the API's local accounts otherwise
  data.tsx        API client for React: useApi, useScreen, notifyChanged
  theme.ts        Light, dark and system theme
  download.ts     Saving files from the browser
  components/     Shell, top bar, charts, shared page parts (kit.tsx), form parts (Form.tsx)
  forms/          Dialogs, grouped by area
  screens/        One folder per area
    dashboard/ health/ fitness/ records/ care/ sharing/ inbox/ library/ settings/ public/
```

## How a screen works

1. A `loadX(api)` function in `packages/api/src/views/` fetches data and returns display strings.
2. The screen calls `useScreen(loadX)` and renders the result. It does no formatting.
3. `ScreenState` shows loading and error states.
4. A dialog saves through `FormDialog`, using rules from `packages/api/src/forms.ts`. A successful save calls
   `notifyChanged()`, so every screen on view reloads.

## Adding a screen

1. Design it in Figma, on page 15 "Proposed".
2. Add the view-model in `packages/api/src/views/`, and any fields in `types.ts`.
3. Add the screen in `screens/<area>/`, starting with a comment naming its Figma frame.
4. Add the route in `App.tsx`, and the sidebar item in `components/AppShell.tsx` if it needs one.
5. Check it at 1440 and 1024 wide, in light and dark.

## Styling

- Use `var(--vl-*)`, `tokenVar(token)` and `.vl-<text-style>`, not raw colours, sizes or fonts.
- Below 1280px the shell follows the Figma tablet frames: icon sidebar, and rails under the main column.
- `.vl-light` and `.vl-dark` pin a part of the page to one theme.

To use a new icon size, add it to `USES` in `scripts/write-icons.py` and run the script.

## Sign-in modes

`auth.ts` picks the mode once at start-up:

| Mode | When | Sign-in |
|---|---|---|
| Sample data | No `VITE_API_URL` | None; saves say nothing was saved |
| Local accounts | `VITE_API_URL` only | The API's email and password routes |
| Hosted | `VITE_API_URL` and `VITE_SUPABASE_*` | Supabase, with email confirmation, Google and two-step verification |

Change email, Sign out everywhere and two-step verification exist only in hosted mode.
