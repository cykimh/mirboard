import { expect, test, type Page } from '@playwright/test';
import { fillBots } from './lobbyBots';

/**
 * 담바둑 한 판(D-279 — S3 완료 기준). 게스트로 들어가 담바둑 방을 만들고 [＋ 봇 추가]로 정원까지(D-290) → [3명으로 시작], 판이 끝날 때까지
 * 화면으로만 둔다 — 배치는 빈 칸 두 번 누르기, 옮김은 내 돌 → 갈 수 있는 칸 → 담 방향 버튼. 끝나면 결과 현판과 [대기실로].
 *
 * <p>D-281 공개 뒤에는 기본 스택에서 돈다(`npm --prefix client run e2e -- dambaduk`, 다른 곳은 `E2E_BASE_URL`). 수는 고르지 않는다(늘 첫
 * 후보) — 끝까지 가는 배선을 보는 점검이지 실력 점검이 아니다. 게스트를 하나 만든다(IP 당 발급 한도 — `practice.e2e.ts` 와 같은 주의).
 */

test.describe.configure({ mode: 'serial', timeout: 300_000 });

/** 판이 나아간 정도 — 머리 줄의 수 표시(배치 중 "돌 놓기", 옮김 중 "N수")와 판 위 돌 개수. */
async function progress(page: Page): Promise<string> {
  const badge = (await page.locator('.db-badge').allTextContents()).join(' ');
  const stones = await page.locator('.db-cell[aria-label*="은돌"]').count();
  return `${badge}|${stones}`;
}

async function myMove(page: Page): Promise<boolean> {
  const status = page.locator('.db-status');
  const text = (await status.textContent()) ?? '';
  if (!text.startsWith('내 차례')) return false;
  const cells = page.locator('.db-cell.db-cell-on');
  const quick = { timeout: 10_000 };
  if (text.includes('놓을 빈 칸')) {
    const cell = cells.first();
    await cell.click(quick);
    await cell.click(quick);
    return true;
  }
  // 고를 수 있는 돌은 지금 모드에서 갈 칸이 있는 돌뿐이다(담을 넘어야만 움직이면 넘기가 저절로 켜진다).
  await page.locator('.db-cell-on[aria-label*="옮길 수 있음"]').first().click(quick);
  await page.locator('.db-cell-on[aria-label*="갈 수 있는 칸"]').first().click(quick);
  await page.locator('.db-wall-pad button:not([disabled])').first().click(quick);
  return true;
}

test('담바둑 — 봇 둘과 한 판을 끝까지 두고 대기실로 돌아온다', async ({ page }) => {
  await page.goto('/login');
  await page.getByRole('button', { name: '게스트로 바로 체험하기' }).click();
  await expect(page).toHaveURL(/\/games$/);

  await page.getByRole('button', { name: '새 방 만들기' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('combobox', { name: '게임 선택' }).click();
  await page.getByRole('option', { name: '담바둑' }).click();
  await dialog.getByLabel('방 이름').fill('담바둑 한 판');
  await dialog.getByRole('button', { name: '방 만들기' }).click();
  await expect(page).toHaveURL(/\/rooms\/[^/]+$/);

  await fillBots(page);
  await page.getByRole('button', { name: '3명으로 시작' }).click();
  await expect(page.locator('.db-table')).toBeVisible();

  const result = page.getByRole('status', { name: '판 결과' });
  let moves = 0;
  for (let i = 0; i < 600 && !(await result.isVisible()); i++) {
    const before = await progress(page);
    if (await myMove(page)) {
      moves++;
      // 보낸 수가 반영될 때까지(돌 수·수 개수가 바뀜) 또는 판이 끝날 때까지 기다린다. "내 차례" 문구로 재지 않는다 — 마지막 돌을 놓은 사람이
      // 첫 수도 두거나(선이 배치 끝과 같은 좌석), 다른 사람이 모두 건너뛰면 차례가 곧바로 다시 온다.
      await expect
        .poll(async () => (await result.isVisible()) || (await progress(page)) !== before, { timeout: 15_000 })
        .toBe(true);
    } else {
      await page.waitForTimeout(250);
    }
  }

  await expect(result).toBeVisible();
  expect(moves).toBeGreaterThan(2);
  await expect(result.getByRole('table')).toBeVisible();
  await result.getByRole('button', { name: '대기실로' }).click();
  await expect(page.getByRole('button', { name: /봇 추가/ })).toBeVisible();
});
