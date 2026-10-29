import { defineConfig, devices } from '@playwright/test';

/**
 * 화면 자동 점검(E2E). 서버·클라는 띄워 두고 돌린다 — 기본 대상은 로컬 개발 스택(`http://localhost:5173`, `/api`·`/ws` 는 Vite 가 8080 으로
 * 넘긴다). 다른 곳은 `E2E_BASE_URL` 로. 게스트 계정을 하나 만드므로 운영에 돌릴 때는 사람이 직접 고른다(`docs/qa-scenarios.md`).
 *
 * 브라우저: 로컬은 깔린 Chrome(`channel: 'chrome'` — 따로 받지 않는다), CI 는 `npx playwright install chromium` 뒤 번들 Chromium.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], channel: process.env.CI ? undefined : 'chrome' },
    },
    {
      name: 'phone',
      use: { ...devices['Pixel 7'], channel: process.env.CI ? undefined : 'chrome' },
    },
  ],
});
