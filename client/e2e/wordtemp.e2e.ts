import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

/**
 * 낱말 온도 한 판(D-319 — S3 완료 기준). 게스트로 들어가 낱말 온도 방을 만들고 [＋ 봇 추가] 두 번 → 판 길이 "짧게"(1라운드) → [3명으로 시작],
 * 판이 끝날 때까지 화면으로만 한다 — 낱말은 첫 후보, 내 출제 차례에는 기준보다 낮은 힌트 하나와 기준을 넘기는 힌트 하나(D-320 — 넘기면 바로
 * 추리로, 표에 없으면 [그만 내기]), 남의 차례에는 공개되는 힌트를 보고 [패스]. 끝나면 결과와 [대기실로].
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

  // 게임판이 뜬 때와 첫 낱말 고르기가 보인 때를 잰다 — 서버가 상태를 저장한 뒤 WORD_SELECT_STARTED 를 방송하므로(D-319) 첫 resync 가
  // 빈손이어도 곧 판을 받는다. 클라의 안전망(1.5초 뒤 다시 청하기)이 돌기 전이어야 한다.
  await page.evaluate(() => {
    const w = window as unknown as { __wt: { mounted: number; select: number } };
    w.__wt = { mounted: 0, select: 0 };
    new MutationObserver(() => {
      const t = document.querySelector('.wt-table');
      if (t && !w.__wt.mounted) w.__wt.mounted = performance.now();
      if (t?.getAttribute('data-phase') === 'WORD_SELECT' && !w.__wt.select) w.__wt.select = performance.now();
    }).observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['data-phase'] });
  });
  await page.getByRole('button', { name: '3명으로 시작' }).click();
  await expect(page.locator('.wt-table')).toBeVisible();
  await expect(page.locator('.wt-table[data-phase="WORD_SELECT"]')).toBeVisible();
  const timing = await page.evaluate(() => (window as unknown as { __wt: { mounted: number; select: number } }).__wt);
  expect(timing.select - timing.mounted, '첫 낱말 고르기까지(ms)').toBeLessThan(1200);
  // 후보 카드에 서버가 준 범주 이름(한글)과 뜻풀이가 보인다
  await expect(page.locator('.wt-cand-category').first()).toHaveText(/[가-힣]/);
  await expect(page.locator('.wt-cand-gloss')).toHaveCount(0); // 뜻풀이는 보이지 않는다(D-320)

  const result = page.getByRole('status', { name: '판 결과' });
  const seen = new Set<string>();
  const hinted = new Set<string>();
  let myHints = 0;
  let othersHintsSeen = 0;
  let passed = 0;
  let missed = 0;
  let myGuesses = 0;
  let lowHints = 0;
  let flagged = 0;
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
        const attemptNo = await textOf(page, '.wt-attempt-now .wt-attempt-no');
        const key = `${answer}-${attemptNo}`;
        if (!answer) {
          // 남(봇)의 차례 — 힌트는 낼 때마다 모두에게 보인다(D-320): 낱말 옆에 온도계가 뜬다
          if ((await page.locator('.wt-round-now .wt-hint .wt-gauge').count()) > 0) othersHintsSeen++;
        } else if ((await input.count()) > 0 && (await input.isEnabled()) && !hinted.has(key)) {
          hinted.add(key);
          const floor = Number(/(\d+)/.exec(await textOf(page, '.wt-round-now .wt-round-floor'))?.[1] ?? 0);
          expect(floor, '이번 차수의 기준 온도가 보인다').toBeGreaterThanOrEqual(50);
          const legal = (h: [string, number]) =>
            !h[0].includes(answer) && !(h[0].length >= 2 && answer.includes(h[0])) && !hinted.has(`w:${h[0]}`);
          const usable = (table.get(answer) ?? []).filter(legal); // 온도 높은 순
          // 먼저 기준보다 낮은 힌트 하나 — 공개되지만 단계는 이어진다
          const low = usable.find((h) => h[1] < floor && h[1] >= floor - 25);
          if (low) {
            hinted.add(`w:${low[0]}`);
            // D-323 — 빈 입력란에서는 [힌트 내기]가 꺼져 있고, 낼 수 있는 낱말을 쓰면 켜진다
            const submit = page.getByRole('button', { name: '힌트 내기' });
            await expect(submit).toBeDisabled(quick);
            await input.fill(low[0]);
            await expect(submit).toBeEnabled(quick);
            await input.press('Enter');
            const row = page.locator('.wt-round-now .wt-hint', { hasText: low[0] });
            await expect(row.locator('.wt-gauge')).toHaveAttribute('data-temp', String(low[1]), { timeout: 10_000 });
            expect(await phaseOf(page)).toBe('HINT');
            myHints++;
            lowHints++;
            if (flagged === 0) {
              // D-323 — "온도가 이상해요": 방향을 골라 알리면 토스트가 뜨고 그 줄이 "알림 ✓" 로 바뀐다
              await row.getByRole('button', { name: `${low[0]} 온도가 이상해요` }).click(quick);
              await row.getByRole('button', { name: '너무 낮아요' }).click(quick);
              await expect(row.locator('.wt-flag-done')).toBeVisible(quick);
              await expect(page.getByText(`'${low[0]}' 온도가 이상하다고 알렸어요. 고마워요!`)).toBeVisible(quick);
              flagged++;
            }
          }
          // 그다음 기준을 넘기는 힌트(표에 있으면) — 그 자리에서 추리로 넘어간다
          const pass = [...usable].reverse().find((h) => h[1] >= floor && !hinted.has(`w:${h[0]}`));
          if (pass) {
            hinted.add(`w:${pass[0]}`);
            await input.fill(pass[0]);
            await input.press('Enter');
            await expect(page.locator('.wt-round-now .wt-verdict')).toContainText(`기준 ${floor}° 넘김`, { timeout: 10_000 });
            await expect(page.locator('.wt-round-now .wt-hint-pass')).toContainText(pass[0]);
            myHints++;
            passed++;
          } else if (low) {
            // 표에 기준 이상인 낱말이 없다 — [그만 내기]: 기준을 못 넘긴 채 추리로
            await page.getByRole('button', { name: '그만 내기' }).click(quick);
            await expect(page.locator('.wt-round-now .wt-verdict')).toContainText('기준 못 넘김', { timeout: 10_000 });
            missed++;
          }
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
  expect(passed + missed, '내 힌트 단계가 판정으로 끝났다(넘김/못 넘김)').toBeGreaterThan(0);
  expect(othersHintsSeen, '남의 힌트와 온도가 보였다').toBeGreaterThan(0);
  expect(myGuesses).toBeGreaterThan(0);
  expect(flagged, '낮은 힌트를 냈으면 한 번 알렸다').toBe(Math.min(1, lowHints));
  await expect(result.locator('.wt-end-row')).toHaveCount(3);
  await expect(result.locator('.wt-end-turn')).toHaveCount(3); // 짧게 = 1라운드 = 세 사람이 한 번씩
  // 모르는 거절 코드(`CODE: 원문`)가 화면에 뜨지 않았다
  await expect(page.getByText(/^[A-Z_]{4,}: /)).toHaveCount(0);
  expect(errors).toEqual([]);

  await result.getByRole('button', { name: '대기실로' }).click();
  await expect(page.getByRole('button', { name: /봇 추가/ })).toBeVisible();
});
