// D-303~D-306 — 내 프로필(촘촘한 배치·통합 레이팅)과 메인 랭킹 스크린샷(수동 도구). 서버·클라를 띄워 둔 채:
//   SHOT_BASE_URL=http://localhost:5211 SHOT_API_URL=http://localhost:8211 SHOT_DB_SCHEMA=public node client/e2e/profileCompactShots.mjs
// API 로 계정을 만들고, 전적은 로컬 DB(docker `mirboard-postgres`)에 직접 넣은 뒤 V24 스크립트로 통합 레이팅을 만든다.
// 두 화면 크기 × 라이트/다크로 docs/design/profile-compact/ 에 찍고, 가로 넘침·누르는 것 높이(40px)를 잰다.
import { chromium } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const base = process.env.SHOT_BASE_URL ?? 'http://localhost:5173';
const api = process.env.SHOT_API_URL ?? 'http://localhost:8080';
const schema = process.env.SHOT_DB_SCHEMA ?? 'public';
const out = fileURLToPath(new URL('../../docs/design/profile-compact/', import.meta.url));
const v24 = readFileSync(
  fileURLToPath(new URL('../../server/src/main/resources/db/migration/V24__unified_rating_d305.sql', import.meta.url)),
  'utf8',
);
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
function sql(text) {
  return execFileSync(
    'docker',
    ['exec', '-i', 'mirboard-postgres', 'psql', '-U', 'mirboard', '-d', 'mirboard', '-v', 'ON_ERROR_STOP=1', '-q'],
    { input: `SET search_path TO ${schema};\n${text}`, encoding: 'utf8' },
  );
}

const tag = Date.now().toString(36).slice(-3);
async function user(name) {
  const body = { username: `${name}${tag}`, password: 'shotpass1' };
  await call('/api/auth/register', { method: 'POST', body }).catch(() => {});
  return call('/api/auth/login', { method: 'POST', body });
}
const names = ['가람', '나래', '다온', '라온', '마루', '바다'];
const logins = [];
for (const n of names) logins.push(await user(n));
const ids = logins.map((l) => l.user.userId);
const [me, ...others] = ids;

// 게임별 전적(레이팅·승·패·탈주). 통합 행은 넣지 않는다 — 아래 V24 가 만든다.
const stat = (id, game, rating, w, l, d = 0) =>
  `INSERT INTO user_game_stats (user_id, game_type, rating, win_count, lose_count, desert_count) VALUES (${id}, '${game}', ${rating}, ${w}, ${l}, ${d});`;
const month = (id, game, w, l, recent) =>
  `INSERT INTO user_monthly_stats (user_id, game_type, stat_month, win_count, lose_count, recent)
   VALUES (${id}, '${game}', date_trunc('month', (now() AT TIME ZONE 'Asia/Seoul'))::date, ${w}, ${l}, '${recent}');`;
const seed = [
  stat(me, 'ONE_CARD', 1236, 21, 9, 1),
  stat(me, 'TICHU', 1088, 7, 5),
  stat(me, 'SKULL_KING', 1012, 3, 4),
  stat(me, 'YACHT', 1140, 6, 2),
  stat(me, 'OMOK', 964, 1, 5),
  stat(others[0], 'ONE_CARD', 1420, 40, 12),
  stat(others[1], 'ONE_CARD', 1305, 22, 15),
  stat(others[2], 'TICHU', 1170, 12, 9),
  stat(others[3], 'YACHT', 1046, 4, 5),
  stat(others[4], 'OMOK', 930, 1, 8),
  month(me, '*', 9, 4, 'WWLWW'),
  month(others[0], '*', 12, 3, 'WWWLW'),
  month(others[1], '*', 7, 6, 'LWLWL'),
  month(others[2], '*', 5, 5, 'WLLWD'),
  month(others[3], '*', 2, 4, 'LLWL'),
  month(others[4], '*', 0, 3, 'LLL'),
];
// 상대 전적 — 원카드 2인 판 몇 개(내가 이긴 판·진 판).
let rivalSql = '';
const duel = (opp, iWin) => {
  rivalSql += `WITH m AS (INSERT INTO onecard_match_results (room_id, end_reason, payload_json) VALUES ('shot-${tag}', 'FINISHED', '{}') RETURNING id)
    INSERT INTO onecard_match_participants (match_id, user_id, seat, final_rank, cards_left, is_win, deserted)
    SELECT id, ${me}, 0, ${iWin ? 1 : 2}, 0, ${iWin}, FALSE FROM m UNION ALL
    SELECT id, ${opp}, 1, ${iWin ? 2 : 1}, 0, ${!iWin}, FALSE FROM m;\n`;
};
[
  [others[0], [true, false, false, true, false]],
  [others[1], [true, true, true, false]],
  [others[2], [true, false]],
  [others[3], [true, true, true]],
  [others[4], [false]],
].forEach(([opp, results]) => results.forEach((w) => duel(opp, w)));
sql(seed.join('\n') + '\n' + rivalSql + '\n' + v24);

const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await context.newPage();
await page.goto(`${base}/login`);
await page.evaluate((snap) => localStorage.setItem('mirboard.auth', JSON.stringify(snap)), {
  token: logins[0].accessToken,
  expiresAt: logins[0].expiresAt,
  user: logins[0].user,
});

async function shoot(path, name, ready) {
  await page.goto(`${base}${path}`);
  await ready();
  for (const theme of ['light', 'dark']) {
    await page.evaluate((t) => document.documentElement.classList.toggle('dark', t === 'dark'), theme);
    for (const [width, height] of SIZES) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(400);
      await page.screenshot({ path: `${out}${name}-${width}x${height}-${theme}.png`, fullPage: true });
      const m = await page.evaluate(() => ({
        overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
        shortButtons: [...document.querySelectorAll('main button, section button, form button, form input')]
          .map((x) => [x.getAttribute('aria-label') ?? x.textContent?.trim().slice(0, 12), Math.round(x.getBoundingClientRect().height)])
          .filter(([, h]) => h > 0 && h < 40),
        rating: document.querySelector('[data-testid="profile-rating"]')?.textContent ?? null,
      }));
      console.log(name, theme, width, JSON.stringify(m));
    }
  }
}

await shoot('/profile', 'profile', async () => {
  await page.getByTestId('profile-rating').waitFor();
  await page.getByText('상대 전적').waitFor();
  await page.waitForTimeout(500);
});
await shoot('/games', 'hub', async () => {
  await page.getByText('이번 달 전적').waitFor();
  await page.getByTestId('ranking-scroll').locator('tbody tr').first().waitFor();
});

// 계급표 창(통합 레이팅 문구) — 프로필에서 한 장.
await page.goto(`${base}/profile`);
await page.getByTestId('profile-rating').waitFor();
await page.evaluate(() => document.documentElement.classList.remove('dark'));
await page.setViewportSize({ width: 375, height: 812 });
await page.getByRole('button', { name: '계급표' }).click();
await page.getByRole('dialog', { name: '계급표' }).waitFor();
await page.waitForTimeout(400);
await page.screenshot({ path: `${out}tier-table-375x812-light.png` });

await browser.close();
