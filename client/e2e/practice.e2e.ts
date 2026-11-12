import { expect, test, type Page } from '@playwright/test';

/**
 * 첫 화면부터 공개 게임판 전부까지 한 번에 — 게스트로 들어가 허브를 보고, 게임마다 방을 만들어 대기실에서 [빈자리 봇으로 채우기]
 * → [N명으로 시작](D-174 — 방 종류 선택 없음)으로 판을 열어 게임판이 뜨는지(게임판은 따로 받는 조각이다 — D-141), 방장
 * ⋮ 메뉴의 "게임 종료"(D-135·D-186)로 끝내고 허브로 나온다. 게스트는 IP 당 발급 한도가 있어 한 번만 만든다.
 */
test.describe.configure({ mode: 'serial' });

let page: Page;

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
});

test.afterAll(async () => {
  await page.close();
});

test('게스트로 바로 들어가면 허브가 보인다', async () => {
  await page.goto('/login');
  await page.getByRole('button', { name: '게스트로 바로 체험하기' }).click();

  await expect(page).toHaveURL(/\/games$/);
  await expect(page.getByRole('button', { name: '새 방 만들기' })).toBeVisible();
});

const GAMES = [
  { name: '원카드', board: '.oc-table' },
  // D-207 — 표시 이름은 얏추(코드 이름 YACHT).
  { name: '얏추', board: '.yt-table' },
  // D-190 — 오목(1:1 기본 정원 2 — 봇 하나를 채워 [2명으로 시작]).
  { name: '오목', board: '.om-table' },
  // D-223 — 씨앗포(2~4인 — 봇 셋을 채워 [4명으로 시작]).
  { name: '씨앗포', board: '.ar-table' },
  { name: '스컬킹', board: '.sk-table' },
  { name: '티츄', board: '.game-table-layout' },
  // D-214 — 구름항로 공개. 2~4인이라 봇 3으로 4명 판.
  { name: '구름항로', board: '.cr-table' },
] as const;

for (const game of GAMES) {
  test(`${game.name} — 대기실에서 봇으로 채워 시작하면 게임판이 뜨고, 게임 종료 뒤 허브로 나온다`, async () => {
    await page.goto('/games');
    await page.getByRole('button', { name: '새 방 만들기' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('combobox', { name: '게임 선택' }).click();
    await page.getByRole('option', { name: game.name }).click();
    await dialog.getByLabel('방 이름').fill(`${game.name} 봇 판`);
    await dialog.getByRole('button', { name: '방 만들기' }).click();

    await expect(page).toHaveURL(/\/rooms\/[^/]+$/);
    await page.getByRole('button', { name: '빈자리 봇으로 채우기' }).click();
    await expect(page.getByRole('button', { name: '봇 빼기' })).toBeVisible();
    await page.getByRole('button', { name: /명으로 시작$/ }).click();
    await expect(page.locator(game.board)).toBeVisible();

    // D-186 — 게임 종료·나가기는 머리 줄 ⋮ 메뉴 안에 있다.
    page.once('dialog', (d) => void d.accept());
    await page.getByRole('button', { name: '게임 메뉴' }).click();
    await page.getByRole('menuitem', { name: '게임 종료' }).click();
    await page.getByRole('button', { name: '게임 메뉴' }).click();
    await expect(page.getByRole('menuitem', { name: '나가기' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '게임 종료' })).toHaveCount(0);

    // D-240 — 티츄도 끝난 판의 게임판을 유지한다. 결과 없이 끝났으니(강제 종료) 게임판 위 안내 + [메인으로].
    if (game.name === '티츄') {
      await page.keyboard.press('Escape');
      await expect(page.locator(game.board)).toBeVisible();
      await expect(page.getByText('게임이 종료되었습니다.')).toBeVisible();
      await page.getByRole('button', { name: '메인으로' }).click();
    } else {
      await page.getByRole('menuitem', { name: '나가기' }).click();
    }
    await expect(page).toHaveURL(/\/games$/);
  });
}
