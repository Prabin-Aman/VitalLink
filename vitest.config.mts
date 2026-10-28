import { defineConfig } from "vitest/config";

// Unit tests for the shared packages (form rules, time zones, view-models). Run with `npm test`.
export default defineConfig({
  test: { include: ["packages/*/test/**/*.test.ts"] },
});
