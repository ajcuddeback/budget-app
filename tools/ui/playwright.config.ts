import { defineConfig, devices } from '@playwright/test';
import { chromiumExecutable } from './helpers/browser.js';
import { OUT_DIR } from './helpers/artifacts.js';

const BASE_URL = process.env.UI_BASE_URL ?? 'http://localhost:4200';
const executablePath = chromiumExecutable();

export default defineConfig({
  testDir: './specs',
  outputDir: `${OUT_DIR}/_playwright`,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [
    ['list'],
    ['html', { outputFolder: `${OUT_DIR}/_html-report`, open: 'never' }],
    ['json', { outputFile: `${OUT_DIR}/results.json` }],
  ],
  timeout: 30_000,
  expect: { timeout: 5_000 },

  use: {
    baseURL: BASE_URL,
    // Traces and video only on failure — they are for debugging a red run, not every run.
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: { executablePath },
  },

  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 }, launchOptions: { executablePath } },
    },
    {
      name: 'mobile',
      use: { ...devices['Pixel 7'], launchOptions: { executablePath } },
    },
  ],
});
