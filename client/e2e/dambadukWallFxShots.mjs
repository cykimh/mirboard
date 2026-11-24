// D-307 — 담바둑 담 세우기 연출 스크린샷(수동 도구). 서버·클라를 띄워 둔 채:
//   SHOT_BASE_URL=http://localhost:5212 node client/e2e/dambadukWallFxShots.mjs
// 게스트로 들어가 봇 하나와 담바둑을 두면서, 담이 서는 순간(내 수·봇 수)의 연출을 **멈춰 놓고** 시각별로 찍는다 — 판 안의 애니메이션을
// 새 담 조각(`.db-wall-fx`)이 붙는 즉시 멈추고 `currentTime` 을 옮겨 가며 docs/design/dambaduk-wall-fx/ 에 저장한다.
// 집이 새로 생기면 집 고리(`.db-home-ring`)도 한 장, 끝에 동작 줄이기(빛만)도 한 장.
import { chromium } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const base = process.env.SHOT_BASE_URL ?? 'http://localhost:5173';
const out = fileURLToPath(new URL('../../docs/design/dambaduk-wall-fx/', import.meta.url));
/** 수가 닿은 뒤의 시각(ms) — 미끄러짐 220 → 떨어짐 → 350 에 "쿵" → 튐 → 빛이 스러짐. */
const FRAMES = [
  [290, 'drop'],
  [365, 'thud'],
  [430, 'grit'],
  [520, 'bounce'],
  [760, 'glow'],
];

const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({ viewport: { width: 375, height: 812 }, deviceScaleFactor: 2, reducedMotion: 'no-preference' });
const page = await context.newPage();
page.on('dialog', (d) => void d.accept().catch(() => {}));

// 새 담 조각·집 고리가 붙는 즉시 판 안의 애니메이션을 멈춘다(찍는 쪽이 시각을 옮긴다).
await page.addInitScript(() => {
  const w = window;
  w.__fx = { wall: 0, home: 0, hold: false };
  const boardAnims = () =>
    document.getAnimations().filter((a) => {
      const t = a.effect && a.effect.target;
      return t && t.closest && t.closest('.db-board');
    });
  w.__fxSeek = (ms) => boardAnims().forEach((a) => {
    a.pause();
    a.currentTime = ms;
  });
  w.__fxResume = () => {
    boardAnims().forEach((a) => {
      // 끝없이 도는 것(맥박·숨쉬기)은 finish 할 수 없다 — 다시 돌린다.
      try {
        a.finish();
      } catch {
        a.play();
      }
    });
    w.__fx.hold = false;
  };
  new MutationObserver((list) => {
    for (const m of list) {
      for (const n of m.addedNodes) {
        if (!(n instanceof Element)) continue;
        const cls = n.getAttribute('class') ?? '';
        if (cls.includes('db-wall-fx')) w.__fx.wall++;
        else if (cls.includes('db-home-ring')) w.__fx.home++;
        else continue;
        w.__fx.hold = true;
        requestAnimationFrame(() => boardAnims().forEach((a) => a.pause()));
      }
    }
  }).observe(document, { childList: true, subtree: true });
});

await page.goto(`${base}/login`);
await page.getByRole('button', { name: '게스트로 바로 체험하기' }).click();
await page.getByRole('button', { name: '새 방 만들기' }).click();
const dialog = page.getByRole('dialog');
await dialog.getByRole('combobox', { name: '게임 선택' }).click();
await page.getByRole('option', { name: '담바둑' }).click();
await dialog.getByLabel('방 이름').fill('담 연출 확인');
await dialog.getByRole('button', { name: '방 만들기' }).click();
const add = page.getByRole('button', { name: /봇 추가/ });
await add.waitFor();
await add.click();
await page.waitForTimeout(600);
await page.getByRole('button', { name: /명으로 시작$/ }).click();
await page.locator('.db-table').waitFor();

const quick = { timeout: 10_000 };
async function myMove() {
  const text = (await page.locator('.db-status').textContent()) ?? '';
  if (!text.startsWith('내 차례')) return false;
  if (text.includes('놓을 빈 칸')) {
    // 가운데 쪽 빈 칸부터 — 판 가운데에서 담이 서는 모습을 찍기 좋게.
    const cells = page.locator('.db-cell.db-cell-on');
    const n = await cells.count();
    const cell = cells.nth(Math.floor(n / 2));
    await cell.click(quick);
    await cell.click(quick);
    return true;
  }
  await page.locator('.db-cell-on[aria-label*="옮길 수 있음"]').first().click(quick);
  await page.locator('.db-cell-on[aria-label*="갈 수 있는 칸"]').first().click(quick);
  await page.locator('.db-wall-pad button:not([disabled])').first().click(quick);
  return true;
}

const board = page.locator('.db-board-wrap');
let seen = { wall: 0, home: 0 };
let wallSets = 0;
let homeShots = 0;
let reducedShot = false;
const result = page.getByRole('status', { name: '판 결과' });

async function shootIfHeld() {
  const fx = await page.evaluate(() => ({ ...window.__fx }));
  if (!fx.hold) return;
  if (fx.wall > seen.wall && wallSets < 3) {
    wallSets++;
    for (const [ms, name] of FRAMES) {
      await page.evaluate((t) => window.__fxSeek(t), ms);
      await board.screenshot({ path: `${out}wall-${wallSets}-${name}-${ms}ms.png` });
    }
    if (wallSets === 1) {
      await page.evaluate(() => window.__fxSeek(365));
      await page.screenshot({ path: `${out}table-375-thud.png` });
    }
  } else if (fx.wall > seen.wall && !reducedShot && wallSets >= 3) {
    // 동작 줄이기 — 움직임 없이 새 담 둘레의 빛만.
    reducedShot = true;
    await page.evaluate(() => window.__fxSeek(120));
    await board.screenshot({ path: `${out}reduced-motion-glow-only.png` });
  }
  if (fx.home > seen.home && homeShots < 2) {
    homeShots++;
    // 집 번짐·고리는 터가 실린 TURN_CHANGED 뒤 720ms 에 피기 시작한다.
    for (const ms of [860, 1000]) {
      await page.evaluate((t) => window.__fxSeek(t), ms);
      await board.screenshot({ path: `${out}home-${homeShots}-ring-${ms}ms.png` });
    }
  }
  seen = { wall: fx.wall, home: fx.home };
  await page.evaluate(() => window.__fxResume());
}

for (let i = 0; i < 900 && !(await result.isVisible()); i++) {
  if (wallSets >= 3 && !reducedShot) await page.emulateMedia({ reducedMotion: 'reduce' });
  if (wallSets >= 3 && reducedShot && homeShots >= 1) break;
  await shootIfHeld();
  if (await myMove()) {
    for (let k = 0; k < 40; k++) {
      await page.waitForTimeout(100);
      const held = await page.evaluate(() => window.__fx.hold);
      if (held) {
        await page.waitForTimeout(60);
        await shootIfHeld();
        break;
      }
      if ((await page.locator('.db-status').textContent())?.startsWith('내 차례') === false && k > 15) break;
    }
  } else {
    await page.waitForTimeout(120);
  }
}
console.log(JSON.stringify({ wallSets, homeShots, reducedShot }));
await browser.close();
