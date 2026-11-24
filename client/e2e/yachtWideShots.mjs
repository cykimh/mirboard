// D-291 — 얏추 넓은 화면 배치 스크린샷(수동 도구). 서버·클라를 띄워 둔 채:
//   SHOT_BASE_URL=http://localhost:5205 node client/e2e/yachtWideShots.mjs
// 게스트로 들어가 얏추 방을 만들고 봇을 채워 시작한 뒤, 세 화면 크기 × 라이트/다크를 docs/design/yacht-wide/ 에 찍는다.
import { chromium } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const base = process.env.SHOT_BASE_URL ?? 'http://localhost:5173';
const out = fileURLToPath(new URL('../../docs/design/yacht-wide/', import.meta.url));
const SIZES = [
  [1920, 1080],
  [1280, 800],
  [375, 812],
];

const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, reducedMotion: 'no-preference' });
const page = await context.newPage();
await page.goto(`${base}/login`);
await page.getByRole('button', { name: '게스트로 바로 체험하기' }).click();
await page.getByRole('button', { name: '새 방 만들기' }).click();
const dialog = page.getByRole('dialog');
await dialog.getByRole('combobox', { name: '게임 선택' }).click();
await page.getByRole('option', { name: '얏추' }).click();
await dialog.getByRole('button', { name: '방 만들기' }).click();
// D-290 — 봇은 [＋ 봇 추가]로 한 명씩(얏추 정원 4 → 봇 셋).
for (let i = 0; i < 3; i++) {
  const add = page.getByRole('button', { name: /봇 추가/ });
  await add.waitFor();
  await page.waitForFunction(() => {
    const b = [...document.querySelectorAll('button')].find((x) => /봇 추가/.test(x.textContent ?? ''));
    return b && !b.disabled;
  });
  await add.click();
  await page.waitForTimeout(400);
}
await page.getByRole('button', { name: /명으로 시작$/ }).click();
await page.locator('.yt-table').waitFor();

const roll = page.getByRole('button', { name: /^굴리기, \d번 남음$/ });
// 내 차례가 올 때까지 기다렸다가 한 번 굴려 주사위·후보 점수가 보이는 상태로 찍는다.
await roll.waitFor({ timeout: 120_000 });
await roll.click();
await page.waitForTimeout(1200);

for (const theme of ['light', 'dark']) {
  await page.evaluate((t) => document.documentElement.classList.toggle('dark', t === 'dark'), theme);
  for (const [width, height] of SIZES) {
    await page.setViewportSize({ width, height });
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${out}yacht-${width}x${height}-${theme}.png` });
  }
}

// 리뷰 — 넓은 화면 2단의 좁은 쪽(1024~1110px, iPad 가로)에서도 주사위가 정사각이고 크기 변수와 같은지 잰다.
for (const width of [1024, 1080, 1100, 1280]) {
  await page.setViewportSize({ width, height: 768 });
  await page.waitForTimeout(300);
  const m = await page.evaluate(() => {
    const dice = document.querySelector('.yt-dice');
    const size = parseFloat(getComputedStyle(dice.querySelector('.yt-die')).width);
    const boxes = [...dice.querySelectorAll('.yt-die')].map((d) => d.getBoundingClientRect());
    const row = dice.getBoundingClientRect();
    return {
      die: boxes.map((b) => `${Math.round(b.width)}x${Math.round(b.height)}`).join(' '),
      square: boxes.every((b) => Math.abs(b.width - b.height) < 0.5 && Math.abs(b.width - size) < 0.5),
      step: Math.round((boxes[1].left - boxes[0].left) * 10) / 10,
      fits: boxes[0].left >= row.left - 0.5 && boxes[4].right <= row.right + 0.5,
      noScroll: document.documentElement.scrollWidth <= window.innerWidth,
    };
  });
  console.log(`주사위 ${width}px:`, JSON.stringify(m));
  if (width === 1024) await page.screenshot({ path: `${out}yacht-1024x768-dark.png` });
}

// 굴림 연출 한 장면(1920 다크) — 굴리는 도중을 잡는다.
await page.setViewportSize({ width: 1920, height: 1080 });
if (await roll.isEnabled().catch(() => false)) {
  await roll.click();
  await page.waitForTimeout(260);
  await page.screenshot({ path: `${out}yacht-1920x1080-dark-rolling.png` });
  await page.waitForTimeout(1000);
  // 좁은 2단(1024)에서의 굴림도 한 장 — 정육면체·컵 좌표가 주사위 자리와 맞는지.
  await page.setViewportSize({ width: 1024, height: 768 });
  if (await roll.isEnabled().catch(() => false)) {
    await roll.click();
    await page.waitForTimeout(260);
    await page.screenshot({ path: `${out}yacht-1024x768-dark-rolling.png` });
    await page.waitForTimeout(1000);
  }
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.waitForTimeout(900);
}

// D-292 — 꾹 눌러 흔들다가 주사위 판 밖으로 나가면 취소(마우스). 남은 굴림 수가 그대로인지 본다.
if (await roll.isVisible().catch(() => false)) {
  const before = await roll.getAttribute('aria-label');
  const box = await roll.boundingBox();
  const felt = await page.locator('.yt-felt').boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(450);
  const shaking = await roll.innerText();
  await page.screenshot({ path: `${out}yacht-1920x1080-dark-shaking.png` });
  await page.mouse.move(felt.x + felt.width / 2, felt.y + felt.height + 60, { steps: 6 });
  const cancelled = await roll.innerText();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 4 });
  await page.mouse.up();
  await page.waitForTimeout(900);
  const after = await roll.getAttribute('aria-label');
  console.log('흔들기 취소:', JSON.stringify({ before, shaking, cancelled, after, ok: before === after }));
}

// D-293 — 마지막 3초의 큰 숫자(내 차례를 그대로 두고 기다린다).
const final = page.locator('.yt-final');
try {
  await final.waitFor({ timeout: 60_000 });
  await page.waitForTimeout(120);
  await page.screenshot({ path: `${out}yacht-1920x1080-dark-final-count.png` });
  console.log('마지막 3초 숫자:', await final.innerText());
} catch {
  console.log('마지막 3초 숫자를 잡지 못했다(턴 제한 없음?)');
}
await browser.close();
