# @vitallink/api

Everything the web portal knows about the API. It has no React in it, so its rules can be unit tested.

| File | Purpose |
|---|---|
| `src/client.ts` | `VitalLinkClient`: one method per API call. With no `baseUrl` it serves sample data. |
| `src/types.ts` | Hand-written types for API responses |
| `src/openapi.gen.ts` | Generated from the running API by `npm run api:types`. Don't edit. |
| `src/contract.ts` | Fails the type check if a field in `types.ts` isn't in the API's schema |
| `src/views/*.ts` | `loadX(api)` view-models, one file per area, returning display strings |
| `src/forms.ts` | Form options, validation and request bodies |
| `src/format.ts` | Dates, numbers and units, always in the account's time zone |
| `src/bluetooth.ts` | Decodes standard Bluetooth health readings |
| `src/mock.ts`, `src/mock-data/` | Sample data from the Figma frames |
| `test/` | Vitest tests, run with `npm test` |

## Rules

- **Screens don't format.** A new display string belongs in a view-model.
- **Form rules live in `forms.ts`.** A `checkX` function returns `{ ok: true, body }` or `{ ok: false, error }`.
- **Live mode never shows sample numbers.** A call the API can't serve says so on screen.

## When the API changes

1. Run the API locally, then `npm run api:types`.
2. Update `types.ts`, and `contract.ts` for a new response type.
3. Run `npm run typecheck`. CI also fails if `openapi.gen.ts` is out of date.
