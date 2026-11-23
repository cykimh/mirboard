// D-298 — 메인 "열린 방"(대기 중 + 게임 중) 스크린샷(수동 도구). 서버·클라를 띄워 둔 채:
//   SHOT_BASE_URL=http://localhost:5210 SHOT_API_URL=http://localhost:8210 node client/e2e/hubPlayingShots.mjs
// API 로 계정·방(대기 중 2 + 게임 중 2)을 만들고, 게스트로 들어간 허브를 두 화면 크기 × 라이트/다크로 docs/design/hub-playing-rooms/ 에 찍는다.
// 끝에 [관전]이 방으로 들어가는지, 버튼 높이(40px 이상)·가로 넘침이 없는지도 잰다.
import { chromium } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const base = process.env.SHOT_BASE_URL ?? 'http://localhost:5173';
const api = process.env.SHOT_API_URL ?? 'http://localhost:8080';
const out = fileURLToPath(new URL('../../docs/design/hub-playing-rooms/', import.meta.url));
const SIZES = [
  [1280, 800],
  [375, 812],
];

async function call(path, { token, method = 'GET', body } = {}) {
  const res = await fetch(`${api}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

const tag = Date.now().toString(36).slice(-4);
async function user(name) {
  const body = { username: `${name}${tag}`, password: 'shotpass1' };
  await call('/api/auth/register', { method: 'POST', body }).catch(() => {});
  return (await call('/api/auth/login', { method: 'POST', body })).accessToken;
}
async function room(tokens, name, gameType, { bots = 0, start = false, spectators = [] } = {}) {
  const created = await call('/api/rooms', { token: tokens[0], method: 'POST', body: { name, gameType } });
  for (const t of tokens.slice(1)) await call(`/api/rooms/${created.roomId}/join`, { token: t, method: 'POST' });
  for (let i = 0; i < bots; i++) await call(`/api/rooms/${created.roomId}/bots?count=1`, { token: tokens[0], method: 'POST' });
  if (start) await call(`/api/rooms/${created.roomId}/start`, { token: tokens[0], method: 'POST' });
  for (const t of spectators) await call(`/api/rooms/${created.roomId}/spectate`, { token: t, method: 'POST' });
  return created.roomId;
}

const [a, b, c, d, e, f, g, h, i] = await Promise.all(
  ['가람', '나래', '다온', '라온', '마루', '바다', '사랑', '아라', '자람'].map(user),
);
await room([a], '티츄 한 판 하실 분', 'TICHU');
await room([b, c], '저녁 스컬킹', 'SKULL_KING');
const watched = await room([d, e], '원카드 결승전 — 이름이 아주 긴 방입니다', 'ONE_CARD', {
  bots: 1,
  start: true,
  spectators: [f, g],
});
await room([h, i], '얏추 한 판', 'YACHT', { start: true });

const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await context.newPage();
await page.goto(`${base}/login`);
await page.getByRole('button', { name: '게스트로 바로 체험하기' }).click();
await page.locator('[data-room-kind="playing"]').first().waitFor();

for (const theme of ['light', 'dark']) {
  await page.evaluate((t) => document.documentElement.classList.toggle('dark', t === 'dark'), theme);
  for (const [width, height] of SIZES) {
    await page.setViewportSize({ width, height });
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${out}hub-${width}x${height}-${theme}.png` });
    const m = await page.evaluate(() => {
      const list = document.querySelector('[data-testid="room-list"]');
      return {
        overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
        listHeight: list.getBoundingClientRect().height,
        rows: [...list.querySelectorAll('li')].map((li) => li.dataset.roomKind),
        buttonHeights: [...list.querySelectorAll('button')].map((x) => Math.round(x.getBoundingClientRect().height)),
        rowOverflow: [...list.querySelectorAll('li')].map((li) => li.scrollWidth - li.clientWidth),
      };
    });
    console.log(theme, width, JSON.stringify(m));
  }
}

// [관전] → 그 방의 게임판(관전)으로 들어간다. 본인이 고른 관전이라 "…관전으로 들어왔어요" 안내는 없다(D-133).
await page
  .locator('[data-room-kind="playing"]')
  .filter({ hasText: '원카드 결승전' })
  .getByRole('button', { name: /관전$/ })
  .click();
await page.waitForURL(new RegExp(`/rooms/${watched}$`));
await page.locator('.oc-table').waitFor();
console.log('spectate ok', page.url(), 'notice', await page.getByText(/관전으로 들어왔어요/).count());
await browser.close();
