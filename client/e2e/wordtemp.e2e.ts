import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

/**
 * 낱말 온도 한 판(D-319 — S3 완료 기준). 게스트로 들어가 낱말 온도 방을 만들고 [＋ 봇 추가] 두 번 → 판 길이 "짧게"(1라운드) → [3명으로 시작],
 * 판이 끝날 때까지 화면으로만 한다 — 낱말은 첫 후보, 내 출제 차례에는 힌트 하나를 내고 [힌트 끝], 남의 차례에는 [패스]. 끝나면 결과와 [대기실로].
 *
 * <p>공개(S4) 전에는 코드 기본값이 `DISABLED` 라 기본 e2e 에서는 건너뛰고 `E2E_WORD_TEMP=1` 일 때만 돈다 — `.claude/launch.json` 의
 * wordtemp-server·wordtemp-client 스택(`E2E_BASE_URL=http://localhost:5219`). 실시간 채점이 꺼진 로컬에서도 돌게 힌트는 서버 자원의 유사도
 * 표에서 고른다(표 밖 낱말은 `UNKNOWN_WORD` 로 거절된다). 끝까지 가는 배선을 보는 점검이지 실력 점검이 아니다. 제 속도로 5~10분
 * (서버를 `MIRBOARD_WORDTEMP_TIMESCALE=0.5` 로 띄우면 봇 간격·추리·결과 시간이 줄어 2~4분).
 */
test.skip(process.env.E2E_WORD_TEMP !== '1', '낱말 온도는 공개 전이라 E2E_WORD_TEMP=1 일 때만');
test.describe.configure({ mode: 'serial', timeout: 1_500_000 });

/** 판이 끝나기를 기다리는 상한 — 제 속도(배율 1)로 세 출제 차례가 모두 3차까지 가면 10분쯤 걸린다. */
const MATCH_LIMIT_MS = 1_320_000;

/** 정답 → 힌트 목록(온도 높은 순). */
function loadTable(): Map<string, Array<[string, number]>> {
  const path = fileURLToPath(new URL('../../server/src/main/resources/wordtemp/table-v1.tsv.gz', import.meta.url));
  const table = new Map<string, Array<[string, number]>>();
  for (const line of gunzipSync(readFileSync(path)).toString('utf-8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const [answer, pairs] = line.split('\t');
    const hints = pairs.split(',').map((p): [string, number] => {
      const at = p.lastIndexOf(':');
      return [p.slice(0, at), Number(p.slice(at + 1))];
    });
    table.set(answer, hints.sort((a, b) => b[1] - a[1]));
  }
  return table;
}

/** 지금 화면의 것만 읽는다 — 없는 요소를 기다리지 않는다(locator 의 읽기는 요소가 생길 때까지 기다려 바퀴가 멈춘다). */
const phaseOf = (page: Page) => page.evaluate(() => document.querySelector('.wt-table')?.getAttribute('data-phase') ?? '');
const textOf = (page: Page, selector: string) =>
  page.evaluate((sel) => document.querySelector(sel)?.textContent?.trim() ?? '', selector);

test('낱말 온도 — 봇 둘과 짧은 판을 끝까지 하고 대기실로 돌아온다', async ({ page }) => {
  const table = loadTable();
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  await page.goto('/login');
  await page.getByRole('button', { name: '게스트로 바로 체험하기' }).click();
  await expect(page).toHaveURL(/\/games$/);

  await page.getByRole('button', { name: '새 방 만들기' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('combobox', { name: '게임 선택' }).click();
  await page.getByRole('option', { name: '낱말 온도' }).click();
  await dialog.getByRole('button', { name: '방 만들기' }).click();
  await expect(page).toHaveURL(/\/rooms\/[^/]+$/);
  const roomId = page.url().split('/').pop()!;

  // 봇 둘(최소 3인) — 정원 6 을 다 채우면 판이 길다
  const add = page.getByRole('button', { name: /봇 추가/ });
  const heading = page.getByRole('heading', { name: /^참가자/ });
  for (const n of [2, 3]) {
    await expect(add).toBeEnabled();
    await add.click();
    await expect(heading).toContainText(`${n}/`);
  }
  // 대기실 설정은 서버 카탈로그가 준 것 — "힌트 제한" 줄이 보인다(게임이 선언한 이름)
  await expect(page.getByText('힌트 제한').first()).toBeVisible();
  // 판 길이 짧게(1라운드)·힌트 제한 60초 — 방장 설정 API(화면의 선택 상자와 같은 요청)
  const token = await page.evaluate(() => JSON.parse(localStorage.getItem('mirboard.auth') ?? '{}').token as string);
  const put = await page.request.put(`/api/rooms/${roomId}/settings`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { turnSeconds: 60, options: { MATCH_LENGTH: 'SHORT' } },
  });
  expect(put.ok(), await put.text()).toBeTruthy();

  await page.getByRole('button', { name: '3명으로 시작' }).click();
  await expect(page.locator('.wt-table')).toBeVisible();

  const result = page.getByRole('status', { name: '판 결과' });
  const seen = new Set<string>();
  const hinted = new Set<string>();
  let myHints = 0;
  let myGuesses = 0;
  const quick = { timeout: 5_000 };
  const deadline = Date.now() + MATCH_LIMIT_MS;
  while (Date.now() < deadline && !(await result.isVisible())) {
    const phase = await phaseOf(page);
    seen.add(phase);
    try {
      if (phase === 'WORD_SELECT') {
        const pick = page.locator('.wt-cand:not(:disabled):not([aria-disabled="true"])').first();
        if ((await page.locator('.wt-cand-picked').count()) === 0 && (await pick.count()) > 0) {
          await pick.click(quick);
          await expect(page.locator('.wt-cand-picked')).toBeVisible(quick);
        }
      } else if (phase === 'HINT') {
        const input = page.getByRole('textbox', { name: '힌트 낱말' });
        const answer = await textOf(page, '.wt-answer-word');
        const key = `${answer}-${await textOf(page, '.wt-attempt-now .wt-attempt-no')}`;
        if (answer && (await input.count()) > 0 && (await input.isEnabled()) && !hinted.has(key)) {
          hinted.add(key);
          // 미지근한 힌트 하나(정답이 든 낱말·정답 안의 낱말은 서버가 거절한다)
          const hint = (table.get(answer) ?? []).find(
            ([w, t]) => t >= 50 && t <= 90 && !w.includes(answer) && !(w.length >= 2 && answer.includes(w)) && !hinted.has(`w:${w}`),
          );
          expect(hint, `표에 "${answer}"의 힌트가 있어야 한다`).toBeTruthy();
          hinted.add(`w:${hint![0]}`);
          await input.fill(hint![0]);
          await input.press('Enter');
          // 내 힌트의 온도가 온도계로 보인다(본인 큐)
          const row = page.locator('.wt-setter .wt-own-hint', { hasText: hint![0] });
          await expect(row.locator('.wt-gauge')).toHaveAttribute('data-temp', String(hint![1]), { timeout: 10_000 });
          myHints++;
          // 96 미만이면 내가 끝낸다
          if ((await phaseOf(page)) === 'HINT') await page.getByRole('button', { name: '힌트 끝' }).click(quick);
        }
      } else if (phase === 'GUESS') {
        const pass = page.getByRole('button', { name: '패스' });
        if ((await pass.count()) > 0) {
          // 공개 전에는 남의 답이 화면에 없다
          expect(await page.locator('.wt-guesses').count()).toBe(0);
          await pass.click(quick);
          // 사람이 나뿐이라 내가 내는 순간 봇도 같이 내고 단계가 넘어간다 — "패스했어요"를 기다리지 않는다
          myGuesses++;
        }
      }
    } catch (e) {
      // 누르는 사이 단계가 넘어갔다 — 다음 바퀴에서 지금 단계로 다시 본다
      if (!/Timeout|waiting for|detached|not attached/i.test(String(e))) throw e;
    }
    await page.waitForTimeout(250);
  }

  await expect(result).toBeVisible();
  // 한 판에 모든 단계를 지났고, 내 출제 차례·남의 차례를 모두 했다
  for (const p of ['WORD_SELECT', 'HINT', 'GUESS', 'ATTEMPT_RESULT', 'TURN_END']) expect([...seen], p).toContain(p);
  expect(myHints).toBeGreaterThan(0);
  expect(myGuesses).toBeGreaterThan(0);
  await expect(result.locator('.wt-end-row')).toHaveCount(3);
  await expect(result.locator('.wt-end-turn')).toHaveCount(3); // 짧게 = 1라운드 = 세 사람이 한 번씩
  // 모르는 거절 코드(`CODE: 원문`)가 화면에 뜨지 않았다
  await expect(page.getByText(/^[A-Z_]{4,}: /)).toHaveCount(0);
  expect(errors).toEqual([]);

  await result.getByRole('button', { name: '대기실로' }).click();
  await expect(page.getByRole('button', { name: /봇 추가/ })).toBeVisible();
});
