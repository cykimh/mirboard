import { expect, test, type Page } from '@playwright/test';

/**
 * 첫 화면부터 세 게임판까지 한 번에 — 게스트로 들어가 허브를 보고, 게임마다 "봇과 연습"으로 판을 열어 게임판이 뜨는지(게임판은
 * 따로 받는 조각이다 — D-141), 방장 "게임 종료"(D-135)로 끝내고 허브로 나온다. 게스트는 IP 당 발급 한도가 있어 한 번만 만든다.
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
  await expect(page.getByRole('heading', { name: '미르보드카페' })).toBeVisible();
});

const GAMES = [
  { name: '원카드', board: '.oc-table' },
  { name: '스컬킹', board: '.sk-table' },
  { name: '티츄', board: '.game-table-layout' },
] as const;

for (const game of GAMES) {
  test(`${game.name} — 봇과 연습을 열면 게임판이 뜨고, 게임 종료 뒤 허브로 나온다`, async () => {
    await page.goto('/games');
    await page.getByRole('button', { name: '새 방 만들기' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByText('봇과 연습', { exact: true }).click();
    await dialog.getByRole('combobox', { name: '게임 선택' }).click();
    await page.getByRole('option', { name: game.name }).click();
    await dialog.getByRole('button', { name: '연습 시작' }).click();

    await expect(page).toHaveURL(/\/rooms\/[^/]+$/);
    await expect(page.locator(game.board)).toBeVisible();

    page.once('dialog', (d) => void d.accept());
    await page.getByRole('button', { name: '게임 종료' }).click();
    await expect(page.getByRole('button', { name: '게임 종료' })).toHaveCount(0);

    await page.getByRole('button', { name: '나가기' }).first().click();
    await expect(page).toHaveURL(/\/games$/);
  });
}
