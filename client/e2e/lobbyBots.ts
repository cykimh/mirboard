import { expect, type Page } from '@playwright/test';

/**
 * D-290 — 대기실 봇은 [＋ 봇 추가]로 한 명씩 앉힌다(한 번에 채우기 버튼은 없앴다). 머리 줄 "참가자 N/정원"이 정원에 닿을 때까지
 * 하나씩 누르고, 누를 때마다 인원이 오르는 것을 기다린다(요청 중에는 버튼이 잠긴다).
 */
export async function fillBots(page: Page): Promise<void> {
  const heading = page.getByRole('heading', { name: /^참가자/ });
  const add = page.getByRole('button', { name: /봇 추가/ });
  const counts = async () => {
    const m = /(\d+)\s*\/\s*(\d+)/.exec((await heading.textContent()) ?? '');
    return m ? [Number(m[1]), Number(m[2])] : [0, 0];
  };
  await expect(heading).toBeVisible();
  for (let i = 0; i < 8; i++) {
    const [n, cap] = await counts();
    if (n >= cap) return;
    await expect(add).toBeEnabled();
    await add.click();
    await expect.poll(async () => (await counts())[0]).toBe(n + 1);
  }
}
