// D-319 — 낱말 온도 단계별 스크린샷(수동 도구). 서버·클라를 띄워 둔 채(공개 전에는 MIRBOARD_WORD_TEMP_STATUS=AVAILABLE):
//   SHOT_BASE_URL=http://localhost:5219 SHOT_API_URL=http://localhost:8219 node client/e2e/wordtempShots.mjs
// 계정 둘(찍는 사람 + 친구)과 봇 둘이 보통(2라운드) 한 판을 끝까지 하면서, 단계가 바뀔 때마다 찍는 사람의 화면을 375×812 로
// client/e2e/wordtemp-shots/ 에 찍는다. 친구는 한 박자 늦게 움직인다 — "골랐어요/냈어요, 기다리는 중" 화면이 남게(사람이 하나뿐이면 내가
// 내는 순간 단계가 넘어간다).
// 실시간 채점이 꺼진 로컬에서도 돌게 힌트는 서버 자원의 유사도 표(table-v1.tsv.gz)에서 고른다 — 1라운드 내 차례에는 일부러 차가운 힌트로
// 시작해 "최저 온도 못 넘김"을, 2라운드에는 가장 뜨거운 힌트로 "거의 정답"을 찍는다. 추리는 1차에 엉뚱한 답, 2차부터는 공개된 힌트·온도와
// 표가 맞는 낱말을 낸다(정답 축하를 찍으려고 — 사람이 하는 방식이 아니다). 찍을 때마다 가로 넘침을 재서 0 이 아니면 적는다.
import { chromium } from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const base = process.env.SHOT_BASE_URL ?? 'http://localhost:5173';
const api = process.env.SHOT_API_URL ?? 'http://localhost:8080';
const out = fileURLToPath(new URL('./wordtemp-shots/', import.meta.url));
const tablePath = fileURLToPath(new URL('../../server/src/main/resources/wordtemp/table-v1.tsv.gz', import.meta.url));
const PHONE = { width: 375, height: 812 };
/** 휴대폰 자판이 올라온 높이 어림(812 − 자판 약 330). */
const KEYBOARD_UP = { width: 375, height: 480 };
const WIDE = { width: 1280, height: 800 };
const LIMIT_MS = 20 * 60_000;
/** 친구가 따라 움직이기까지(ms). */
const FRIEND_LAG_MS = 3500;

mkdirSync(out, { recursive: true });

/** 정답 → (힌트 → 온도). */
const table = new Map();
for (const line of gunzipSync(readFileSync(tablePath)).toString('utf-8').split('\n')) {
  if (!line || line.startsWith('#')) continue;
  const [answer, pairs] = line.split('\t');
  table.set(answer, new Map(pairs.split(',').map((p) => [p.slice(0, p.lastIndexOf(':')), Number(p.slice(p.lastIndexOf(':') + 1))])));
}

async function call(path, { token, method = 'GET', body } = {}) {
  const res = await fetch(`${api}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

const tag = Date.now().toString(36).slice(-4);
async function user(name) {
  const body = { username: `${name}${tag}`, password: 'shotpass1' };
  await call('/api/auth/register', { method: 'POST', body });
  const login = await call('/api/auth/login', { method: 'POST', body });
  return { token: login.accessToken, userId: login.user.userId, username: body.username };
}
const me = await user('가람');
const friend = await user('나래');
const room = await call('/api/rooms', { token: me.token, method: 'POST', body: { name: '낱말 온도 한 판', gameType: 'WORD_TEMP' } });
await call(`/api/rooms/${room.roomId}/join`, { token: friend.token, method: 'POST' });
for (let i = 0; i < 2; i++) await call(`/api/rooms/${room.roomId}/bots?count=1`, { token: me.token, method: 'POST' });

const browser = await chromium.launch({ channel: 'chrome' });
async function open(u) {
  const context = await browser.newContext({ viewport: PHONE });
  await context.addInitScript(
    ([t, userId, name]) => {
      localStorage.setItem('mirboard.auth', JSON.stringify({ token: t, expiresAt: Date.now() + 3_600_000, user: { userId, username: name } }));
    },
    [u.token, u.userId, u.username],
  );
  const p = await context.newPage();
  await p.goto(`${base}/rooms/${room.roomId}`);
  await p.getByRole('heading', { name: /^참가자/ }).waitFor();
  return p;
}
const page = await open(me);
const other = await open(friend);
await page.screenshot({ path: `${out}00-waiting-room.png` });
await call(`/api/rooms/${room.roomId}/start`, { token: me.token, method: 'POST' });
await page.locator('.wt-table').waitFor();
await other.locator('.wt-table').waitFor();

const taken = new Set();
const overflow = [];
/** 이름마다 한 번만 찍는다. */
async function shot(name, { settle = 700, fullPage = false } = {}) {
  if (taken.has(name)) return false;
  taken.add(name);
  await page.waitForTimeout(settle);
  const x = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (x > 0) overflow.push(`${name}: 가로 넘침 ${x}px`);
  await page.screenshot({ path: `${out}${name}.png`, fullPage });
  console.log('shot', name);
  return true;
}

/** 다른 화면 크기·테마로 한 장 더 — 끝나면 휴대폰 라이트로 돌아온다. */
async function variant(name, { size = PHONE, dark = false, focus } = {}) {
  if (taken.has(name)) return;
  await page.setViewportSize(size);
  if (dark) await page.evaluate(() => document.documentElement.classList.add('dark'));
  if (focus) {
    // 자판이 올라오면 브라우저가 입력란을 보이는 곳으로 민다 — 그 모습을 흉내 낸다
    await page.locator(focus).focus().catch(() => {});
    await page.locator(focus).evaluate((el) => el.scrollIntoView({ block: 'end' })).catch(() => {});
    await page.mouse.wheel(0, 24).catch(() => {});
  }
  await shot(name, { settle: 500 });
  if (dark) await page.evaluate(() => document.documentElement.classList.remove('dark'));
  await page.setViewportSize(PHONE);
}

const state = (p) =>
  p.evaluate(() => {
    const t = document.querySelector('.wt-table');
    const txt = (sel) => document.querySelector(sel)?.textContent?.trim() ?? '';
    return {
      phase: t?.getAttribute('data-phase') ?? '',
      role: t?.getAttribute('data-role') ?? '',
      ended: !!document.querySelector('.wt-end[aria-label="판 결과"]'),
      round: Number(/(\d+)\/\d+R/.exec(txt('.wt-header-info'))?.[1] ?? 1),
      attempt: Number(document.querySelector('.wt-attempt-now .wt-attempt-no')?.textContent?.replace('차', '') ?? 1),
      answer: txt('.wt-answer-word'),
      revealed: [...document.querySelectorAll('.wt-reveal:not(.wt-reveal-wait)')].map((li) => ({
        text: li.querySelector('.wt-reveal-word')?.textContent ?? '',
        temp: Number(li.querySelector('.wt-gauge')?.getAttribute('data-temp') ?? -1),
      })),
      pips: document.querySelectorAll('.wt-pip-on').length,
      canHint: !!document.querySelector('.wt-setter .wt-input:not(:disabled)'),
      canGuess: !!document.querySelector('.wt-guess .wt-input'),
      guessed: !!document.querySelector('.wt-guess') && !document.querySelector('.wt-guess .wt-input'),
      picked: !!document.querySelector('.wt-cand-picked'),
      canPick: !!document.querySelector('.wt-cand:not(:disabled)'),
    };
  });

const used = new Set();
/** 표에서 이 정답의 힌트 하나 — 온도가 [lo, hi] 인 것 가운데 아직 안 낸 것(정답이 든 낱말은 서버가 거절하니 피한다). */
function pickHint(answer, lo, hi) {
  const hints = [...(table.get(answer) ?? [])]
    .filter(([w, t]) => t >= lo && t <= hi && !used.has(w) && !w.includes(answer) && !(w.length >= 2 && answer.includes(w)))
    .sort((a, b) => b[1] - a[1]);
  return hints.length ? hints[Math.floor(hints.length / 2)][0] : null;
}

async function sendHint(p, word) {
  if (!word) return;
  used.add(word);
  const input = p.locator('.wt-setter .wt-input');
  await input.fill(word);
  await input.press('Enter');
  await p.locator('.wt-setter .wt-own-hint', { hasText: word }).locator('.wt-gauge').waitFor({ timeout: 8000 }).catch(() => {});
}

/** 공개된 힌트·온도와 표가 모두 맞는 정답 후보. */
function infer(revealed) {
  const fits = [...table].filter(([, hints]) => revealed.every((r) => hints.get(r.text) === r.temp)).map(([answer]) => answer);
  return fits[0] ?? null;
}

// ── 친구: 한 박자 늦게 — 낱말은 첫 후보, 출제 차례에는 미지근한 힌트 하나, 추리는 패스 ──
let friendSig = '';
let friendSince = 0;
let friendDone = '';
async function driveFriend(main) {
  const s = await state(other);
  // 찍는 사람이 먼저 — 내가 고르기·답을 내기 전에는 친구가 움직이지 않는다(내가 낼 때 단계가 넘어가지 않게)
  if (s.phase === 'WORD_SELECT' && main.canPick && !main.picked) return;
  if (s.phase === 'GUESS' && main.role === 'guesser' && main.canGuess) return;
  const sig = `${s.phase}/${s.role}/r${s.round}/a${s.attempt}/${s.answer}`;
  if (sig !== friendSig) {
    friendSig = sig;
    friendSince = Date.now();
  }
  if (friendDone === sig || Date.now() - friendSince < FRIEND_LAG_MS) return;
  try {
    if (s.phase === 'WORD_SELECT' && s.canPick && !s.picked) {
      await other.locator('.wt-cand:not(:disabled)').first().click({ timeout: 2000 });
    } else if (s.phase === 'HINT' && s.role === 'setter' && s.canHint && s.answer) {
      const floor = [50, 60, 70][s.attempt - 1];
      await sendHint(other, pickHint(s.answer, floor + 1, floor + 14) ?? pickHint(s.answer, floor, 95));
      await other.getByRole('button', { name: '힌트 끝' }).click({ timeout: 2000 });
    } else if (s.phase === 'GUESS' && s.canGuess) {
      await other.getByRole('button', { name: '패스' }).click({ timeout: 2000 });
    } else {
      return;
    }
    friendDone = sig;
  } catch {
    // 누르는 사이 단계가 넘어갔다 — 다음 바퀴에 다시 본다
  }
}

let setterTurns = 0;
let lastTurnKey = '';
let lastSig = '';
const started = Date.now();
for (;;) {
  if (Date.now() - started > LIMIT_MS) throw new Error('판이 끝나지 않았다(20분)');
  const s = await state(page);
  if (s.ended) break;
  const sig = `${s.phase}/${s.role}/r${s.round}/a${s.attempt}`;
  if (sig !== lastSig) console.log('—', sig);
  lastSig = sig;
  try {
    if (s.phase === 'WORD_SELECT') {
      if (s.canPick && !s.picked) {
        await shot(`01-word-select-r${s.round}`);
        // 2라운드에는 "거의 정답"(96 이상) 힌트가 표에 있는 낱말을 먼저 고른다
        const words = await page.locator('.wt-cand:not(:disabled) .wt-cand-word').allTextContents();
        const hot = words.findIndex((w) => [...(table.get(w) ?? [])].some(([, t]) => t >= 96));
        await page.locator('.wt-cand:not(:disabled)').nth(s.round >= 2 && hot >= 0 ? hot : 0).click();
        await page.locator('.wt-cand-picked').waitFor({ timeout: 3000 });
        await shot(`02-word-picked-r${s.round}`, { settle: 300 });
      }
    } else if (s.phase === 'HINT' && s.role === 'setter' && s.canHint && s.answer) {
      const key = `${s.round}-${s.attempt}`;
      if (key !== lastTurnKey) {
        lastTurnKey = key;
        if (s.attempt === 1) setterTurns += 1;
        await shot('03-setter-empty');
        if (setterTurns === 1 && s.attempt === 1) {
          // 일부러 차가운 힌트 하나만 — 최저 온도를 못 넘긴다
          await sendHint(page, pickHint(s.answer, 25, 45) ?? pickHint(s.answer, 5, 49));
          await shot('04-setter-below-floor');
        } else if (setterTurns >= 2 && s.attempt === 1 && pickHint(s.answer, 96, 100)) {
          await sendHint(page, pickHint(s.answer, 30, 55));
          await sendHint(page, pickHint(s.answer, 96, 100)); // 96 이상 — 그 자리에서 공개된다
          await shot('13-boil-reveal', { settle: 900 });
          continue;
        } else {
          const floor = [50, 60, 70][s.attempt - 1];
          await sendHint(page, pickHint(s.answer, 20, 45));
          await sendHint(page, pickHint(s.answer, floor + 2, floor + 18) ?? pickHint(s.answer, floor, 95));
          await shot('05-setter-hints');
          await variant('05b-setter-keyboard', { size: KEYBOARD_UP, focus: '.wt-setter .wt-input' });
          await variant('05c-setter-dark', { dark: true });
        }
        await page.getByRole('button', { name: '힌트 끝' }).click({ timeout: 3000 });
      }
    } else if (s.phase === 'HINT' && s.role === 'guesser' && s.pips >= 1) {
      await shot('06-guesser-hint-count');
      if (!taken.has('16-tutorial')) {
        await page.getByRole('button', { name: '게임 메뉴' }).click();
        await page.getByRole('menuitem', { name: /규칙/ }).click();
        await shot('16-tutorial');
        for (let i = 0; i < 3; i++) await page.getByRole('button', { name: '다음' }).click();
        await shot('16b-tutorial-floors');
        await page.keyboard.press('Escape');
      }
    } else if (s.phase === 'GUESS' && s.role === 'guesser' && s.canGuess) {
      await shot('07-guess-input');
      await variant('07b-guess-keyboard', { size: KEYBOARD_UP, focus: '.wt-guess .wt-input' });
      await variant('07c-guess-wide', { size: WIDE });
      const guess = s.attempt >= 2 ? (infer(s.revealed) ?? '우산') : '우산';
      const input = page.locator('.wt-guess .wt-input');
      await input.fill(guess);
      await input.press('Enter');
      await page.locator('.wt-my-guess').waitFor({ timeout: 3000 });
      await shot('08-guess-submitted', { settle: 300 });
    } else if (s.phase === 'GUESS' && s.role === 'setter') {
      await shot('09-setter-waiting');
    } else if (s.phase === 'ATTEMPT_RESULT') {
      const solved = await page.locator('.wt-result-solved').count();
      const zero = await page.locator('.wt-result-summary', { hasText: '0점' }).count();
      await shot(solved ? '10-result-solved' : zero ? '11-result-floor-zero' : '11b-result-missed', { settle: 1100 });
      if (solved) await variant('10b-result-solved-dark', { dark: true });
    } else if (s.phase === 'TURN_END') {
      await shot('12-turn-end', { settle: 500 });
      await variant('12b-turn-end-wide', { size: WIDE });
    }
    if (s.revealed.some((r) => r.temp >= 96)) await shot('13b-boil-board', { settle: 200 });
  } catch (e) {
    console.log('skip', s.phase, s.role, String(e).split('\n')[0]);
  }
  await driveFriend(await state(page));
  await page.waitForTimeout(300);
}

await shot('14-match-end', { settle: 800, fullPage: true });
await variant('14b-match-end-dark', { dark: true });
await variant('14c-match-end-wide', { size: WIDE });
await browser.close();
console.log(`끝 — ${taken.size}장, ${out}`);
if (overflow.length) console.log(overflow.join('\n'));
else console.log('가로 넘침 없음');
