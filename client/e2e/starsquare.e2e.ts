import { expect, test, type APIRequestContext, type Browser, type Page } from '@playwright/test';

/**
 * 별자리 수판 S3 화면 점검(D-286·D-288, D-294 — 전체 리셋·완성한 사람의 남의 판 보기). 코드 기본값은 공개(D-287)지만 가입 계정 5개·브라우저 넷을 쓰는 무거운 점검이라 기본 e2e 에서는
 * 건너뛰고 `E2E_STAR_SQUARE=1` 일 때만 돈다 — 보통 `.claude/launch.json` 의 starsquare-server·starsquare-client 스택(`E2E_BASE_URL=http://localhost:5203`).
 * 가입은 IP 레이트리밋(`/api/auth/**`)에 걸리므로 두 프로젝트(desktop·phone)를 한 번에 돌리면 뒤쪽이 429 로 멈출 수 있다 — `--project` 로 하나씩.
 *
 * 1. 혼자 한 판 — 짧게(퍼즐 1개)·쉬움(3×3): 화면의 단서를 읽어 테스트가 마방진을 풀고 칸·숫자판을 눌러 넣는다 → 완성·판 끝까지.
 * 2. 넷이 동시에 입력 — 어려움(5×5): 네 브라우저가 한꺼번에 칸을 채우는 동안 서버가 `BUSY` 를 내도 보내기가 다시 보내, 끝나면 넷 모두
 *    서버의 내 판(resync 의 비공개 뷰)이 화면의 판과 같다. 받은 `BUSY` 수를 적는다.
 * 3. 둘이 한 판(D-294) — 먼저 완성한 사람만 "누구 판 보기"로 아직 푸는 사람의 판을 보고(서버의 비공개 뷰 `peers` 도 그 사람만), 아직 푸는
 *    사람에게는 선택지도 `peers` 도 없다.
 * 계정은 가입으로 만든다(게스트는 IP 당 하루 발급 한도가 있다).
 */
test.skip(process.env.E2E_STAR_SQUARE !== '1', '별자리 수판 점검은 무거워 E2E_STAR_SQUARE=1 일 때만');
test.describe.configure({ mode: 'serial' });
test.setTimeout(120_000);

interface Session {
  token: string;
  userId: number;
  username: string;
}

async function newSession(request: APIRequestContext): Promise<Session> {
  const username = `sq${Math.random().toString(36).slice(2, 9)}`;
  const password = 'starsquare-e2e';
  const reg = await request.post('/api/auth/register', { data: { username, password } });
  expect(reg.status()).toBe(201);
  const login = await request.post('/api/auth/login', { data: { username, password } });
  const body = await login.json();
  return { token: body.accessToken, userId: body.user.userId, username };
}

async function api(request: APIRequestContext, s: Session, method: 'post' | 'put', path: string, data?: unknown) {
  const res = await request[method](path, { data, headers: { Authorization: `Bearer ${s.token}` } });
  expect(res.ok(), `${method} ${path} → ${res.status()} ${await res.text()}`).toBeTruthy();
  return res.json().catch(() => null);
}

async function openAs(browser: Browser, s: Session, roomId: string): Promise<Page> {
  const context = await browser.newContext();
  await context.addInitScript(
    ([token, userId, username]) => {
      localStorage.setItem(
        'mirboard.auth',
        JSON.stringify({ token, expiresAt: Date.now() + 3_600_000, user: { userId, username } }),
      );
    },
    [s.token, s.userId, s.username] as const,
  );
  const page = await context.newPage();
  await page.goto(`/rooms/${roomId}`);
  return page;
}

/** 화면 칸 → [값, 단서?] (aria-label "1행 2열 7 (단서)"). */
async function readBoard(page: Page): Promise<{ values: number[]; clue: boolean[] }> {
  // 별 칸만(줄 합 칩도 gridcell 이지만 aria-label 이 없다 — 읽는 글자는 sr-only)
  const labels = await page.getByRole('grid', { name: '내 판' }).locator('[role="gridcell"][data-cell]').evaluateAll((els) =>
    els.map((e) => e.getAttribute('aria-label') ?? ''),
  );
  return {
    values: labels.map((l) => {
      const m = /열 (\d+)/.exec(l);
      return m ? Number(m[1]) : 0;
    }),
    clue: labels.map((l) => l.includes('(단서)')),
  };
}

/** 단서와 맞는 마방진 하나(줄 합 범위 가지치기 백트래킹 — 3×3 은 즉시). */
function solve(clues: number[]): number[] {
  const n = Math.round(Math.sqrt(clues.length));
  const nn = n * n;
  const m = (n * (nn + 1)) / 2;
  const lines: number[][] = [];
  for (let i = 0; i < n; i++) {
    lines.push(Array.from({ length: n }, (_, j) => i * n + j));
    lines.push(Array.from({ length: n }, (_, j) => j * n + i));
  }
  lines.push(Array.from({ length: n }, (_, j) => j * n + j));
  lines.push(Array.from({ length: n }, (_, j) => j * n + (n - 1 - j)));
  const g = [...clues];
  const used = new Set(g.filter(Boolean));
  const ok = () =>
    lines.every((l) => {
      const vals = l.map((c) => g[c]);
      const sum = vals.reduce((a, b) => a + b, 0);
      return vals.includes(0) ? sum < m : sum === m;
    });
  const step = (i: number): boolean => {
    if (i === nn) return true;
    if (g[i] !== 0) return step(i + 1);
    for (let v = 1; v <= nn; v++) {
      if (used.has(v)) continue;
      g[i] = v;
      used.add(v);
      if (ok() && step(i + 1)) return true;
      used.delete(v);
      g[i] = 0;
    }
    return false;
  };
  if (!step(0)) throw new Error('no solution');
  return g;
}

test('혼자 한 판 — 단서를 읽어 풀고 넣으면 완성·판 끝', async ({ browser, request }) => {
  const me = await newSession(request);
  const room = await api(request, me, 'post', '/api/rooms', { name: '별 혼자', gameType: 'STAR_SQUARE' });
  await api(request, me, 'put', `/api/rooms/${room.roomId}/settings`, {
    options: { MATCH_LENGTH: 'SHORT', DIFFICULTY: 'EASY' },
  });
  await api(request, me, 'post', `/api/rooms/${room.roomId}/start`);
  const page = await openAs(browser, me, room.roomId);

  await expect(page.getByRole('grid', { name: '내 판' })).toBeVisible({ timeout: 20_000 });
  const { values, clue } = await readBoard(page);
  const answer = solve(values);
  const fill = async (cells: number[]) => {
    for (const i of cells) {
      const r = Math.floor(i / 3) + 1;
      const c = (i % 3) + 1;
      await page.getByRole('gridcell', { name: new RegExp(`^${r}행 ${c}열`) }).click();
      await page.getByRole('button', { name: new RegExp(`^${answer[i]}( |$)`) }).click();
    }
  };
  const empties = values.map((_, i) => i).filter((i) => !clue[i]);

  // D-294 전체 리셋 — 두 칸 넣고, 한 번 누르면 그대로("한 번 더"), 두 번째에 단서만 남는다. 되돌리기 한 번으로 복구
  await fill(empties.slice(0, 2));
  const reset = page.getByRole('button', { name: /전체 리셋|한 번 더 누르면/ });
  await reset.click();
  await expect(reset).toHaveText(/한 번 더/);
  expect((await readBoard(page)).values.filter(Boolean).length).toBe(values.filter(Boolean).length + 2);
  await reset.click();
  expect((await readBoard(page)).values).toEqual(values);
  await page.getByRole('button', { name: '되돌리기' }).click();
  expect((await readBoard(page)).values.filter(Boolean).length).toBe(values.filter(Boolean).length + 2);
  await reset.click();
  await reset.click();
  expect((await readBoard(page)).values).toEqual(values);

  await fill(empties);
  await expect(page.getByRole('region', { name: '판 결과' })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('region', { name: '퍼즐 1 결과' })).toContainText('모두 완성');
  await expect(page.getByRole('button', { name: '대기실로' })).toBeVisible();
  await page.context().close();
});

test('넷이 동시에 입력 — BUSY 가 나도 다시 보내 넷 모두 서버 판 = 화면 판', async ({ browser, request }) => {
  const people = await Promise.all([0, 1, 2, 3].map(() => newSession(request)));
  const [host, ...guests] = people;
  const room = await api(request, host, 'post', '/api/rooms', { name: '별 넷', gameType: 'STAR_SQUARE' });
  for (const g of guests) await api(request, g, 'post', `/api/rooms/${room.roomId}/join`);
  await api(request, host, 'put', `/api/rooms/${room.roomId}/settings`, {
    options: { MATCH_LENGTH: 'SHORT', DIFFICULTY: 'HARD' },
  });
  await api(request, host, 'post', `/api/rooms/${room.roomId}/start`);

  const pages = await Promise.all(people.map((p) => openAs(browser, p, room.roomId)));
  let busy = 0;
  for (const page of pages) {
    page.on('websocket', (ws) =>
      ws.on('framereceived', (f) => {
        if (typeof f.payload === 'string' && f.payload.includes('"code":"BUSY"')) busy++;
      }),
    );
  }
  for (const page of pages) await expect(page.getByRole('grid', { name: '내 판' })).toBeVisible({ timeout: 20_000 });

  // 넷이 한꺼번에 — 빈 칸마다 칸을 누르고 두 자리 숫자를 이어 친다(쓰로틀 0.4초보다 빠르게)
  await Promise.all(
    pages.map(async (page, who) => {
      const { clue } = await readBoard(page);
      const empties = clue.map((c, i) => (c ? -1 : i)).filter((i) => i >= 0);
      let v = 1 + who * 3;
      for (const i of empties) {
        const r = Math.floor(i / 5) + 1;
        const c = (i % 5) + 1;
        await page.getByRole('gridcell', { name: new RegExp(`^${r}행 ${c}열`) }).click();
        await page.keyboard.type(String(((v++ - 1) % 25) + 1), { delay: 15 });
      }
    }),
  );
  // 마지막 보내기(쓰로틀·BUSY 재전송)가 끝나기를 기다린 뒤 서버의 내 판과 화면을 맞춘다
  await pages[0].waitForTimeout(2_500);
  for (const [k, page] of pages.entries()) {
    const screenBoard = (await readBoard(page)).values;
    const res = await request.get(`/api/rooms/${room.roomId}/resync`, {
      headers: { Authorization: `Bearer ${people[k].token}` },
    });
    const snap = await res.json();
    expect(snap.privateHand.grid, `player ${k}`).toEqual(screenBoard);
  }
  console.log(`starsquare e2e: 4인 동시 입력 중 받은 BUSY = ${busy}`);
  for (const page of pages) await page.context().close();
});

test('둘이 한 판 — 먼저 완성한 사람만 아직 푸는 사람의 판을 본다(D-294)', async ({ browser, request }) => {
  const [first, second] = await Promise.all([newSession(request), newSession(request)]);
  const room = await api(request, first, 'post', '/api/rooms', { name: '별 둘', gameType: 'STAR_SQUARE' });
  await api(request, second, 'post', `/api/rooms/${room.roomId}/join`);
  await api(request, first, 'put', `/api/rooms/${room.roomId}/settings`, {
    options: { MATCH_LENGTH: 'SHORT', DIFFICULTY: 'EASY' },
  });
  await api(request, first, 'post', `/api/rooms/${room.roomId}/start`);
  const [fast, slow] = await Promise.all([openAs(browser, first, room.roomId), openAs(browser, second, room.roomId)]);
  for (const page of [fast, slow]) await expect(page.getByRole('grid', { name: '내 판' })).toBeVisible({ timeout: 20_000 });
  const resync = async (who: Session) =>
    (await request.get(`/api/rooms/${room.roomId}/resync`, { headers: { Authorization: `Bearer ${who.token}` } })).json();

  const { values, clue } = await readBoard(fast);
  const answer = solve(values);
  const empties = values.map((_, i) => i).filter((i) => !clue[i]);
  const put = async (page: Page, i: number) => {
    await page
      .getByRole('grid', { name: '내 판' })
      .getByRole('gridcell', { name: new RegExp(`^${Math.floor(i / 3) + 1}행 ${(i % 3) + 1}열`) })
      .click();
    await page.getByRole('button', { name: new RegExp(`^${answer[i]}( |$)`) }).click();
  };
  // 느린 쪽이 두 칸을 넣는다 — 아직 아무도 완성하지 않았으니 누구에게도 남의 판이 없다
  await put(slow, empties[0]);
  await put(slow, empties[1]);
  await slow.waitForTimeout(700);
  for (const who of [first, second]) expect((await resync(who)).privateHand.peers).toEqual([]);
  await expect(fast.getByRole('group', { name: '누구 판 보기' })).toHaveCount(0);

  // 빠른 쪽이 완성 — 마감 15초 안에 본다
  for (const i of empties) await put(fast, i);
  const tabs = fast.getByRole('group', { name: '누구 판 보기' });
  await expect(tabs).toBeVisible({ timeout: 10_000 });
  await expect(fast.getByRole('timer', { name: '마감까지' })).toBeVisible();
  await tabs.getByRole('button', { name: second.username }).click();
  const peer = fast.getByRole('grid', { name: `${second.username}의 판` });
  await expect(peer).toBeVisible();
  const slowBoard = (await readBoard(slow)).values;
  const seen = await peer.locator('[role="gridcell"][data-cell]').evaluateAll((els) =>
    els.map((e) => {
      const m = /열 (\d+)/.exec(e.getAttribute('aria-label') ?? '');
      return m ? Number(m[1]) : 0;
    }),
  );
  expect(seen).toEqual(slowBoard);
  await expect(peer.locator('[role="gridcell"][data-cell]:not([disabled])')).toHaveCount(0); // 보기 전용

  // 서버 — 완성한 사람의 비공개 뷰에만 남의 판이 있고, 공개 뷰에는 어느 쪽에도 없다
  const mine = await resync(first);
  if (mine.phase === 'PLAYING') {
    expect(mine.privateHand.peers).toEqual([{ seat: 1, grid: slowBoard }]);
    const theirs = await resync(second);
    expect(theirs.privateHand.peers).toEqual([]);
    expect(JSON.stringify(theirs.tableView)).not.toContain('"grid"');
    expect(JSON.stringify(mine.tableView)).not.toContain('peers');
    await expect(slow.getByRole('group', { name: '누구 판 보기' })).toHaveCount(0);
  }
  // 마감(첫 완성 + 15초) → 퍼즐·판 끝
  await expect(fast.getByRole('region', { name: '퍼즐 1 결과' })).toContainText('첫 완성 뒤 마감', { timeout: 25_000 });
  for (const page of [fast, slow]) await page.context().close();
});
