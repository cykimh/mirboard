// D-295 — 얏추 굴림·흔들기 프레임 측정(수동 도구). 서버·클라를 띄워 둔 채:
//   SHOT_BASE_URL=http://localhost:5209 node client/e2e/yachtPerf.mjs
// 게스트로 들어가 봇 하나와 얏추를 시작하고, 내 차례마다 ① 가만히 있기 ② 톡 굴리기 ③ 꾹 눌러 흔들다 굴리기를 재서
// 조건(휴대폰 흉내 375px·CPU 4배 느리게 / PC 1920px)마다 프레임 간격과 메인 스레드·래스터 시간을 JSON 으로 찍는다.
//   PERF_TURNS=3  잴 차례 수(기본 3)   PERF_SKIN_DICE / PERF_SKIN_BOARD  잴 스킨(저장값을 미리 심는다)
//   PERF_CSS='…'  고칠 후보를 CSS 로 덮어 보고 잰다(실험)   PERF_DUMP=1  구간마다 트레이스 상위 항목을 stderr 로
// 읽는 법: 프레임 간격(rAF)은 메인 스레드가 막힐 때만 벌어진다 — transform 애니메이션의 끊김은 안 잡히므로, 프레임마다 드는
// 스타일·배치·그리기·GPU 시간을 같이 본다(이 값이 작을수록 느린 기기에서 프레임을 덜 놓친다).
import { chromium } from '@playwright/test';

const base = process.env.SHOT_BASE_URL ?? 'http://localhost:5173';
const TURNS = Number(process.env.PERF_TURNS ?? 3);
const CONDITIONS = [
  { name: '휴대폰 흉내(375px, CPU 4배 느리게)', viewport: { width: 375, height: 812 }, dsf: 3, cpu: 4, touch: true },
  { name: 'PC(1920px)', viewport: { width: 1920, height: 1080 }, dsf: 1, cpu: 1, touch: false },
];
/** 트레이스에서 더할 일 — 메인 스레드의 스타일·배치·그리기 기록과 래스터. */
const TRACE_EVENTS = ['UpdateLayoutTree', 'Layout', 'PrePaint', 'Paint', 'Layerize', 'Commit', 'RasterTask', 'GPUTask', 'FunctionCall'];

async function startGame(page) {
  await page.goto(`${base}/login`);
  await page.getByRole('button', { name: '게스트로 바로 체험하기' }).click();
  await page.getByRole('button', { name: '새 방 만들기' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('combobox', { name: '게임 선택' }).click();
  await page.getByRole('option', { name: '얏추' }).click();
  await dialog.getByRole('button', { name: '방 만들기' }).click();
  const add = page.getByRole('button', { name: /봇 추가/ });
  await add.waitFor();
  await add.click();
  await page.waitForTimeout(500);
  await page.getByRole('button', { name: /명으로 시작$/ }).click();
  await page.locator('.yt-table').waitFor();
}

/** 한 구간을 잰다 — rAF 간격·긴 작업(페이지 안) + 트레이스 합계. */
async function measure(browser, page, ms, act) {
  await browser.startTracing(page, { categories: ['devtools.timeline', 'disabled-by-default-devtools.timeline'] });
  await page.evaluate(() => {
    const w = window;
    w.__perf = { deltas: [], long: [], run: true };
    let last = performance.now();
    const tick = (now) => {
      if (!w.__perf.run) return;
      w.__perf.deltas.push(now - last);
      last = now;
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
    try {
      w.__perfObs = new PerformanceObserver((list) => {
        for (const e of list.getEntries()) w.__perf.long.push(e.duration);
      });
      w.__perfObs.observe({ entryTypes: ['longtask'] });
    } catch {
      // longtask 미지원 — rAF 만.
    }
  });
  await act();
  await page.waitForTimeout(ms);
  const raw = await page.evaluate(() => {
    const w = window;
    w.__perf.run = false;
    w.__perfObs?.disconnect();
    return w.__perf;
  });
  const trace = JSON.parse((await browser.stopTracing()).toString());
  const sums = {};
  const all = {};
  for (const e of trace.traceEvents ?? []) {
    // 놓친 프레임(컴포지터가 제때 못 그린 프레임) — rAF 간격에는 안 잡히는 transform 애니메이션의 끊김.
    if (e.name === 'DroppedFrame') sums.DroppedFrame = (sums.DroppedFrame ?? 0) + 1;
    if (e.ph !== 'X') continue;
    all[e.name] = (all[e.name] ?? 0) + (e.dur ?? 0) / 1000;
    if (!TRACE_EVENTS.includes(e.name)) continue;
    sums[e.name] = (sums[e.name] ?? 0) + (e.dur ?? 0) / 1000;
  }
  if (process.env.PERF_DUMP) {
    const top = Object.entries(all).sort((a, b) => b[1] - a[1]).slice(0, 25);
    console.error(top.map(([k, v]) => `${k}=${v.toFixed(1)}`).join(' '));
  }
  // 첫 간격은 측정 시작 전의 틈이라 뺀다.
  const d = raw.deltas.slice(1).sort((a, b) => a - b);
  const avg = d.reduce((a, b) => a + b, 0) / Math.max(1, d.length);
  return {
    frames: d.length,
    avg,
    p95: d[Math.floor(d.length * 0.95)] ?? 0,
    max: d[d.length - 1] ?? 0,
    over20: d.filter((x) => x > 20).length,
    over34: d.filter((x) => x > 34).length,
    longTasks: raw.long.length,
    longMs: raw.long.reduce((a, b) => a + b, 0),
    sums,
  };
}

function merge(list) {
  const out = { frames: 0, avg: 0, p95: 0, max: 0, over20: 0, over34: 0, longTasks: 0, longMs: 0, sums: {} };
  for (const m of list) {
    out.frames += m.frames;
    out.avg += m.avg * m.frames;
    out.p95 = Math.max(out.p95, m.p95);
    out.max = Math.max(out.max, m.max);
    out.over20 += m.over20;
    out.over34 += m.over34;
    out.longTasks += m.longTasks;
    out.longMs += m.longMs;
    for (const [k, v] of Object.entries(m.sums)) out.sums[k] = (out.sums[k] ?? 0) + v;
  }
  out.avg = out.frames ? out.avg / out.frames : 0;
  const r = (x) => Math.round(x * 10) / 10;
  return {
    구간수: list.length,
    프레임: out.frames,
    평균간격ms: r(out.avg),
    p95ms: r(out.p95),
    최대ms: r(out.max),
    '20ms넘김': out.over20,
    '34ms넘김': out.over34,
    긴작업: out.longTasks,
    긴작업ms: r(out.longMs),
    스타일ms: r(out.sums.UpdateLayoutTree ?? 0),
    배치ms: r(out.sums.Layout ?? 0),
    그리기ms: r((out.sums.Paint ?? 0) + (out.sums.PrePaint ?? 0) + (out.sums.Layerize ?? 0) + (out.sums.Commit ?? 0)),
    래스터ms: r(out.sums.RasterTask ?? 0),
    스크립트ms: r(out.sums.FunctionCall ?? 0),
    GPUms: r(out.sums.GPUTask ?? 0),
    놓친프레임: out.sums.DroppedFrame ?? 0,
  };
}

const browser = await chromium.launch({ channel: 'chrome' });
const report = {};
for (const cond of CONDITIONS) {
  const context = await browser.newContext({
    viewport: cond.viewport,
    deviceScaleFactor: cond.dsf,
    hasTouch: cond.touch,
    reducedMotion: 'no-preference',
  });
  await context.addInitScript(
    ([dice, board]) => {
      try {
        localStorage.setItem('mirboard.cardAnim', '1');
        localStorage.setItem('mirboard.sfxMuted', '1');
        if (dice) localStorage.setItem('mirboard.yacht.skin.dice', dice);
        if (board) localStorage.setItem('mirboard.yacht.skin.board', board);
      } catch {
        // 저장 불가 — 기본값으로 잰다.
      }
    },
    [process.env.PERF_SKIN_DICE ?? '', process.env.PERF_SKIN_BOARD ?? ''],
  );
  const page = await context.newPage();
  // 실험용 — 고칠 후보를 CSS 로 덮어 보고 잰다.
  if (process.env.PERF_CSS) await page.addInitScript((css) => {
    addEventListener('DOMContentLoaded', () => {
      const el = document.createElement('style');
      el.textContent = css;
      document.head.appendChild(el);
    });
  }, process.env.PERF_CSS);
  // 0점 확인 창은 받는다.
  page.on('dialog', (d) => void d.accept().catch(() => {}));
  await startGame(page);
  const cdp = await context.newCDPSession(page);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: cond.cpu });
  const roll = page.getByRole('button', { name: /^굴리기, \d번 남음$/ });
  const phases = { idle: [], roll: [], shake: [] };
  for (let t = 0; t < TURNS; t++) {
    await roll.waitFor({ timeout: 120_000 });
    await page.waitForFunction(() => {
      const b = document.querySelector('.yt-roll-main');
      return b && !b.disabled && b.getAttribute('aria-disabled') !== 'true';
    });
    await page.waitForTimeout(600);
    // ① 내 차례에 가만히(펠트 맥박·버튼 광택만 돈다).
    phases.idle.push(await measure(browser, page, 1500, async () => {}));
    // ② 톡 굴리기 — 컵·정육면체 다섯 개.
    phases.roll.push(await measure(browser, page, 1100, () => roll.click()));
    // ③ 꾹 눌러 흔들다 굴리기 — 흔들기 1.2초 + 굴림.
    const box = await roll.boundingBox();
    if (box) {
      const x = box.x + box.width / 2;
      const y = box.y + box.height / 2;
      phases.shake.push(
        await measure(browser, page, 1000, async () => {
          await page.mouse.move(x, y);
          await page.mouse.down();
          await page.waitForTimeout(1200);
          await page.mouse.up();
        }),
      );
    }
    await page.waitForTimeout(900);
    // 차례를 넘긴다 — 첫 후보 칸에 기록.
    await page.locator('.yt-pick').first().click();
    await page.waitForTimeout(800);
  }
  report[cond.name] = { '가만히(내 차례)': merge(phases.idle), 굴리기: merge(phases.roll), '흔들다 굴리기': merge(phases.shake) };
  await context.close();
}
await browser.close();
console.log(JSON.stringify(report, null, 2));
