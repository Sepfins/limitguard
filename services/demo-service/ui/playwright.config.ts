import { defineConfig } from '@playwright/test';

const baseURL = process.env['DEMO_BASE_URL'] ?? 'http://localhost:8081';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    channel: 'chrome',
    trace: 'retain-on-failure',
  },
  webServer: process.env['DEMO_BASE_URL']
    ? undefined
    : {
        command: '../gradlew -p .. bootRun',
        url: `${baseURL}/actuator/health`,
        reuseExistingServer: true,
        timeout: 180_000,
      },
});
