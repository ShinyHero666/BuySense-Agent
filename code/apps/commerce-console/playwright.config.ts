import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.MOYUAN_E2E_BASE_URL ?? "http://127.0.0.1:19190";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: "line",
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    launchOptions: { args: ["--no-proxy-server"] },
    ...devices["Desktop Chrome"],
  },
});
