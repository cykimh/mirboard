// D-296 — 얏추 주사위·경기판 스킨 스크린샷(수동 도구). 서버·클라를 띄워 둔 채:
//   SHOT_BASE_URL=http://localhost:5209 node client/e2e/yachtSkinShots.mjs
// 게스트로 들어가 봇 하나와 얏추를 시작하고, ⋮ 메뉴 "주사위·판 꾸미기"로 스킨을 바꿔 가며(화면에서 고르는 길 그대로)
// 스킨마다 375·1280 × 라이트/다크를 docs/design/yacht-skins/ 에 찍는다. 주사위와 판은 한 쌍씩 묶어 찍는다.
import { chromium } from '@playwright/test';
import { fileURLToPath } from 'node:url';

const base = process.env.SHOT_BASE_URL ?? 'http://localhost:5173';
const out = fileURLToPath(new URL('../../docs/design/yacht-skins/', import.meta.url));
/** [파일 이름, 경기판 라디오 이름, 주사위 라디오 이름] */
const PAIRS = [
  ['felt-ivory', '초록 펠트', '상아'],
  ['ocean-amber', '바다', '호박'],
  ['night-jade', '밤하늘', '옥'],
  ['tartan-obsidian', '타탄 체크', '흑요석'],
  ['wood-walnut', '나무 판', '호두나무'],
];
const SIZES = [
  [375, 812],
  [1280, 800],
];

const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, reducedMotion: 'no-preference' });
const page = await context.newPage();
page.on('dialog', (d) => void d.accept().catch(() => {}));
await page.goto(`${base}/login`);
await page.getByRole('button', { name: '게스트로 바로 체험하기' }).click();
await page.getByRole('button', { name: '새 방 만들기' }).click();
const dialog = page.getByRole('dialog');
await dialog.getByRole('combobox', { name: '게임 선택' }).click();
await page.getByRole('option', { name: '얏추' }).click();
await dialog.getByLabel('방 이름').fill('스킨 확인');
await dialog.getByRole('button', { name: '방 만들기' }).click();
const add = page.getByRole('button', { name: /봇 추가/ });
await add.waitFor();
await add.click();
await page.waitForTimeout(500);
await page.getByRole('button', { name: /명으로 시작$/ }).click();
await page.locator('.yt-table').waitFor();

// 내 차례에 두 번 굴리고 하나를 고정해 둔다 — 주사위·고정 테·후보 점수가 보이는 상태.
const roll = page.getByRole('button', { name: /^굴리기, \d번 남음$/ });
await roll.waitFor({ timeout: 120_000 });
await roll.click();
await page.waitForTimeout(1300);
await page.locator('.yt-die').first().click();
await page.waitForTimeout(700);

const picker = page.getByRole('dialog', { name: '주사위·판 꾸미기' });
async function choose(board, dice) {
  await page.getByRole('button', { name: '게임 메뉴' }).click();
  await page.getByRole('menuitem', { name: '주사위·판 꾸미기' }).click();
  // 고른 것은 이름 앞에 "✓ " 가 붙는다 — 이미 고른 것이면 그대로 둔다.
  for (const name of [board, dice]) {
    const radio = picker.getByRole('radio', { name: new RegExp(`^(✓ )?${name}$`) });
    // 라디오는 칸을 덮은 투명한 입력이다 — 칸(label)을 누른다(창이 열리는 움직임이 멎을 때까지 기다린다).
    if (!(await radio.isChecked())) await picker.locator('label', { hasText: name }).click();
    if (!(await radio.isChecked())) throw new Error(`${name} 을(를) 고르지 못했다`);
  }
}

for (const [file, board, dice] of PAIRS) {
  await page.setViewportSize({ width: 1280, height: 800 });
  await choose(board, dice);
  if (file === 'ocean-amber') {
    // 꾸미기 창 자체도 한 벌 찍는다.
    for (const theme of ['light', 'dark']) {
      await page.evaluate((t) => document.documentElement.classList.toggle('dark', t === 'dark'), theme);
      for (const [width, height] of SIZES) {
        await page.setViewportSize({ width, height });
        await page.waitForTimeout(300);
        await page.screenshot({ path: `${out}picker-${width}-${theme}.png` });
      }
    }
    await page.setViewportSize({ width: 1280, height: 800 });
  }
  await picker.getByRole('button', { name: '닫기' }).click();
  for (const theme of ['light', 'dark']) {
    await page.evaluate((t) => document.documentElement.classList.toggle('dark', t === 'dark'), theme);
    for (const [width, height] of SIZES) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(350);
      await page.screenshot({ path: `${out}${file}-${width}-${theme}.png` });
    }
  }
  const saved = await page.evaluate(() => [
    localStorage.getItem('mirboard.yacht.skin.board'),
    localStorage.getItem('mirboard.yacht.skin.dice'),
    document.documentElement.scrollWidth <= window.innerWidth,
  ]);
  console.log(`${file}: 저장 ${saved[0]}·${saved[1]}, 가로 스크롤 없음 ${saved[2]}`);
}

// 리뷰 — 창 안의 글자를 누른 뒤에도 Esc 로 닫히고, Tab 을 여러 번 눌러도 초점이 창 안에 머문다(공용 Radix 다이얼로그).
await choose('나무 판', '호두나무');
await picker.getByText(/내 화면에만 보여요/).click();
let inside = true;
for (let i = 0; i < 14; i++) {
  await page.keyboard.press('Tab');
  inside &&= await page.evaluate(() => !!document.activeElement?.closest('[role="dialog"]'));
}
await picker.getByText(/내 화면에만 보여요/).click();
await page.keyboard.press('Escape');
await page.waitForTimeout(300);
console.log(`꾸미기 창: Tab 이 창 안에 머묾 ${inside}, 글자 누른 뒤 Esc 로 닫힘 ${!(await picker.isVisible())}`);

// 굴리는 도중 한 장(정육면체 옆면도 스킨 색) — 타탄 + 흑요석, 1280 다크.
await page.setViewportSize({ width: 1280, height: 800 });
await choose('타탄 체크', '흑요석');
await picker.getByRole('button', { name: '닫기' }).click();
if (await roll.isEnabled().catch(() => false)) {
  await roll.click();
  await page.waitForTimeout(240);
  await page.screenshot({ path: `${out}tartan-obsidian-1280-dark-rolling.png` });
}
await browser.close();
