// 씨앗포 시안 공통 엔진 — 장면·흉내 물리·연출 타임라인. 그림은 전부 THEME(시안별 파일)이 그린다.
// 앱의 스킨 계약(ArtillerySkin, D-227)과 같은 모양으로 나눴다: 하늘·흙·포대 몸·포신·씨앗·폭발·이름표.
// 좌표는 전장 칸(640×360), 포대 기준점은 발(화분 바닥 가운데), 몸은 오른쪽을 보고 그린 뒤 뒤집는다.
(function () {
  'use strict';
  const T = window.THEME;
  const W = 640, H = 360, TICK = 1000 / 60, G = 0.046875;
  const SEATC = ['#e8613c', '#3f7fd9', '#9a5bd0', '#e0a323'];
  const TEAMC = ['#e0803a', '#3a86d6'];
  const SHELL = {
    PEA: { n: '콩알', r: 14, R: 18, dmg: 30, ang: 58 },
    PUMPKIN: { n: '호박탄', r: 24, R: 30, dmg: 45, ang: 50 },
    VINE: { n: '덩굴탄', r: 16, R: 18, dmg: 15, ang: 66, fill: true },
    CHESTNUT: { n: '알밤탄', r: 34, R: 30, dmg: 25, ang: 62 },
    SPORE: { n: '홀씨탄', r: 10, R: 14, dmg: 16, ang: 70, split: true },
  };
  const KIND = { SPROUT: { n: '새싹포', hp: 100 }, ACORN: { n: '도토리포', hp: 120 }, DANDELION: { n: '민들레포', hp: 85 } };
  const POSES = [['idle', '대기'], ['aim', '조준'], ['recoil', '반동'], ['hit', '피격'], ['down', '쓰러짐']];
  const $ = (s) => document.querySelector(s);
  const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
  const S = { mode: 'light', rm: mq.matches, shell: 'PEA', wind: 4 };

  // ── 도우미(시안 파일이 E.* 로 쓴다) ─────────────────────────────────
  function h(x, y) {
    let n = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263)) | 0;
    n = Math.imul(n ^ (n >>> 13), 1274126177);
    return ((n ^ (n >>> 16)) >>> 0) / 4294967296;
  }
  function vn(x, y, s) {
    const gx = Math.floor(x / s), gy = Math.floor(y / s);
    let fx = x / s - gx, fy = y / s - gy;
    fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
    const a = h(gx, gy), b = h(gx + 1, gy), c = h(gx, gy + 1), d = h(gx + 1, gy + 1);
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
  }
  const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
  const ease = (t) => 1 - Math.pow(1 - Math.max(0, Math.min(1, t)), 3);
  const back = (t) => { t = Math.max(0, Math.min(1, t)); const c = 2.2; return 1 + (c + 1) * Math.pow(t - 1, 3) + c * Math.pow(t - 1, 2); };
  function rr(ctx, x, y, w, hh, r) {
    r = Math.min(r, w / 2, hh / 2);
    ctx.beginPath();
    ctx.moveTo(x + r, y); ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + hh - r); ctx.quadraticCurveTo(x + w, y + hh, x + w - r, y + hh);
    ctx.lineTo(x + r, y + hh); ctx.quadraticCurveTo(x, y + hh, x, y + hh - r);
    ctx.lineTo(x, y + r); ctx.quadraticCurveTo(x, y, x + r, y); ctx.closePath();
  }
  const disc = (ctx, x, y, r) => { ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill(); };
  const parts = [];
  function emit(kind, x, y, o) {
    if (S.rm || parts.length > 420) return;
    parts.push(Object.assign({ kind, x, y, vx: 0, vy: 0, g: 0, life: 30, max: (o && o.life) || 30, size: 1, rot: 0, vr: 0, color: '#fff', drag: 1, w: 0 }, o));
  }
  const hpColor = (ratio, cols) => (ratio > 0.5 ? cols[0] : ratio > 0.25 ? cols[1] : cols[2]);
  const css = (c, a) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a === undefined ? 1 : a})`;
  const E = (window.E = { W, H, h, vn, mix, css, ease, back, rr, disc, emit, hpColor, S, SEATC });

  // ── 지형 ────────────────────────────────────────────────────────────
  let mask, terr;
  function genMask() {
    mask = new Uint8Array(W * H);
    for (const [cx, hw, top, dep] of [[150, 125, 212, 70], [486, 132, 198, 76], [318, 36, 150, 30]]) {
      for (let x = cx - hw; x <= cx + hw; x++) {
        if (x < 0 || x >= W) continue;
        const u = (x - cx) / hw;
        const t = Math.round(top - 18 * (1 - u * u) + (vn(x, cx, 28) - 0.5) * 14 + (vn(x, cx + 9, 9) - 0.5) * 5);
        const b = Math.round(top + dep * (1 - u * u));
        for (let y = t; y <= b; y++) if (y >= 0 && y < H) mask[y * W + x] = 1;
      }
    }
  }
  /** 마스크 → 흙 그림. 앱의 terrainImage(mask) 와 같은 일 — 칸마다 THEME.soil 이 색을 정한다. */
  function paint(m, w, hh, plain) {
    const c = document.createElement('canvas');
    c.width = w; c.height = hh;
    const g = c.getContext('2d');
    const img = g.createImageData(w, hh);
    const d = img.data, P = T.pal[S.mode];
    const e = new Uint8Array(w * hh);
    for (let y = 0; y < hh; y++) for (let x = 0; x < w; x++) {
      const i = y * w + x;
      if (!m[i]) continue;
      e[i] = (x > 0 && !m[i - 1]) || (x < w - 1 && !m[i + 1]) || (y > 0 && !m[i - w]) || (y < hh - 1 && !m[i + w]) ? 1 : 3;
    }
    for (let y = 1; y < hh - 1; y++) for (let x = 1; x < w - 1; x++) {
      const i = y * w + x;
      if (e[i] === 3 && (e[i - 1] === 1 || e[i + 1] === 1 || e[i - w] === 1 || e[i + w] === 1)) e[i] = 2;
    }
    for (let x = 0; x < w; x++) {
      let depth = 0;
      for (let y = 0; y < hh; y++) {
        const i = y * w + x;
        let col = null, a = 255;
        if (m[i]) {
          depth++;
          col = T.soil(x, y, depth, e[i], y < hh - 1 && !m[i + w], P);
        } else {
          depth = 0;
          if (T.above && !plain) {
            const dist = y + 1 < hh && m[i + w] ? 1 : y + 2 < hh && m[i + 2 * w] ? 2 : y + 3 < hh && m[i + 3 * w] ? 3 : 0;
            if (dist) { col = T.above(x, y, dist, P); if (col) a = col[3]; }
          }
        }
        if (!col) continue;
        d[i * 4] = Math.max(0, Math.min(255, col[0]));
        d[i * 4 + 1] = Math.max(0, Math.min(255, col[1]));
        d[i * 4 + 2] = Math.max(0, Math.min(255, col[2]));
        d[i * 4 + 3] = a;
      }
    }
    g.putImageData(img, 0, 0);
    return c;
  }
  const repaint = () => { terr = paint(mask, W, H); };
  function blast(cx, cy, r, fill) {
    for (let y = Math.max(0, cy - r); y <= Math.min(H - 1, cy + r); y++) for (let x = Math.max(0, cx - r); x <= Math.min(W - 1, cx + r); x++) {
      if ((x - cx) ** 2 + (y - cy) ** 2 > r * r) continue;
      if (!fill) { mask[y * W + x] = 0; continue; }
      let free = true;
      for (const u of units) if (u.alive && (x - u.x) ** 2 + (y - (u.footY - 5)) ** 2 <= 49) free = false;
      if (free) mask[y * W + x] = 1;
    }
  }
  const foot = (x) => { for (let y = 0; y < H; y++) if (mask[y * W + x]) return y - 1; return H; };
  const supported = (u) => { if (u.footY + 1 >= H) return false; for (let dx = -2; dx <= 2; dx++) if (mask[(u.footY + 1) * W + u.x + dx]) return true; return false; };

  // ── 판 상태 ─────────────────────────────────────────────────────────
  let units = [], seeds = [], trails = [], booms = [], dmgs = [], impacts = [];
  let phase = 'idle', phT = 0, clock = 0, tickAcc = 0, shake = 0, plan = null, seatSig = '';
  const cloudLevel = 344;
  function mk(seat, x, kind, name, hp) {
    return { seat, x, footY: foot(x), kind, name, hp, shown: hp, maxHp: KIND[kind].hp, alive: true, angle: x < W / 2 ? 45 : 135, hitAt: -1e9, recoilAt: -1e9, downAt: -1, fade: 1, fell: 0, color: SEATC[seat] };
  }
  function reset() {
    genMask();
    units = [mk(0, 95, 'SPROUT', '나', 100), mk(1, 430, 'ACORN', '도토리단', 58), mk(2, 205, 'DANDELION', '솜털이', 85), mk(3, 548, 'SPROUT', '봇 새싹', 44)];
    seeds = []; trails = []; booms = []; dmgs = []; parts.length = 0; phase = 'idle'; shake = 0;
    repaint();
  }
  const foes = () => units.filter((u) => u.alive && u.hp > 0 && u.seat % 2 === 1);

  function step(s, shooter) {
    s.vx += s.ax; s.vy += G;
    const nx = s.x + s.vx, ny = s.y + s.vy;
    const n = Math.floor(Math.max(Math.abs(s.vx), Math.abs(s.vy))) + 1;
    for (let k = 1; k <= n; k++) {
      const px = s.x + ((nx - s.x) * k) / n, py = s.y + ((ny - s.y) * k) / n;
      const cx = Math.floor(px), cy = Math.floor(py);
      if (cx < 0 || cx >= W) return { type: 'out' };
      if (cy >= Math.min(H, cloudLevel)) return { type: 'cloud', x: cx, y: cy };
      for (const u of units) {
        if (!u.alive || (u === shooter && s.age < 10)) continue;
        if ((cx - u.x) ** 2 + (cy - (u.footY - 5)) ** 2 <= 36) return { type: 'unit', x: cx, y: cy, u };
      }
      if (cy >= 0 && mask[cy * W + cx]) return { type: 'soil', x: cx, y: cy };
    }
    s.x = nx; s.y = ny;
    return null;
  }
  function launch(sh, angle, power) {
    const rad = (angle * Math.PI) / 180, v = power * 0.0625;
    return { x: sh.x + 0.5 + Math.cos(rad) * 10, y: sh.footY - 4.5 - Math.sin(rad) * 10, vx: v * Math.cos(rad), vy: -v * Math.sin(rad), ax: (S.wind * 46) / 65536, age: 0, rot: 0 };
  }
  /** 흉내: 정해 둔 각도에서 표적에 가장 가까이 떨어지는 힘을 찾는다(진짜 게임에서는 사람이 정한다). */
  function solve(sh, tg, shell) {
    let angle = SHELL[shell].ang;
    if (tg.x < sh.x) angle = 180 - angle;
    let best = null;
    for (let p = 30; p <= 100; p += 0.25) {
      const s = launch(sh, angle, p);
      let r = null;
      for (s.age = 1; s.age < 900 && !r; s.age++) r = step(s, sh);
      if (!r || !r.x) continue;
      const d = r.type === 'unit' && r.u === tg ? 0 : Math.hypot(r.x - tg.x, r.y - (tg.footY - 5)) + (r.type === 'unit' ? 500 : 0);
      if (!best || d < best.d) best = { d, p };
    }
    return { angle, power: best ? best.p : 60 };
  }
  function fire() {
    if (phase !== 'idle') return;
    if (!foes().length) { reset(); return; }
    const sh = units[0], tg = foes()[0];
    plan = solve(sh, tg, S.shell);
    sh.aimFrom = sh.angle; trails = [];
    phase = 'aim'; phT = clock;
  }
  function startFlight() {
    const sh = units[0];
    sh.angle = plan.angle; sh.recoilAt = clock;
    const s = launch(sh, plan.angle, plan.power);
    s.shell = S.shell; s.live = true; s.trail = [s.x, s.y];
    seeds = [s]; trails = [s.trail]; impacts = [];
    T.fx.muzzle(s.x, s.y, plan.angle, sh.color);
    phase = 'flight'; phT = clock; tickAcc = 0;
  }
  function tickFlight() {
    const sh = units[0];
    for (const s of seeds.slice()) {
      if (!s.live) continue;
      s.age++; s.rot += 0.12;
      const r = step(s, sh);
      if (s.age % 2 === 0 || r) s.trail.push(r && r.x !== undefined ? r.x : s.x, r && r.y !== undefined ? r.y : s.y);
      if (r) { s.live = false; if (r.type === 'soil' || r.type === 'unit') impacts.push(r); continue; }
      if (SHELL[s.shell].split && !s.child && s.vy >= 0) {
        s.live = false;
        for (const dv of [-0.75, 0, 0.75]) {
          const c = Object.assign({}, s, { vx: s.vx + dv, live: true, child: true, trail: [s.x, s.y] });
          seeds.push(c); trails.push(c.trail);
        }
        T.fx.split(s.x, s.y);
      }
    }
    if (!seeds.some((s) => s.live)) detonate();
  }
  function detonate() {
    const spec = SHELL[S.shell], sum = new Map();
    for (const im of impacts) {
      blast(im.x, im.y, spec.r, spec.fill);
      booms.push({ x: im.x, y: im.y, r: spec.r, t0: clock, fill: !!spec.fill, shell: S.shell });
      T.fx.impact(im.x, im.y, spec.r, !!spec.fill);
      shake = Math.max(shake, (T.shake * spec.r) / 14);
      for (const u of units) {
        if (!u.alive || u.hp <= 0) continue;
        const d = Math.floor(Math.hypot(u.x - im.x, u.footY - 5 - im.y));
        const amt = im.u === u ? spec.dmg : d < spec.R ? Math.floor((spec.dmg * (spec.R - d)) / spec.R) : 0;
        if (amt > 0) sum.set(u, (sum.get(u) || 0) + amt);
      }
    }
    for (const [u, amt] of sum) hurt(u, amt);
    if (impacts.length) repaint();
    phase = 'boom'; phT = clock;
  }
  function hurt(u, amt) {
    u.hp = Math.max(0, u.hp - amt); u.hitAt = clock;
    dmgs.push({ u, amt, t0: clock });
    if (u.hp <= 0) u.downAt = clock;
  }
  function tickFall() {
    let moving = false;
    for (const u of units) {
      if (!u.alive || supported(u)) continue;
      u.footY++; u.fell++; moving = true;
      if (u.footY >= cloudLevel) { u.alive = false; u.hp = 0; T.fx.impact(u.x, cloudLevel, 8, true); }
    }
    if (moving) return;
    for (const u of units) { if (u.alive && u.fell > 20 && u.hp > 0) hurt(u, Math.floor((u.fell - 20) / 2)); u.fell = 0; }
    phase = 'settle'; phT = clock;
  }
  function update(dt) {
    clock += dt;
    shake *= Math.pow(0.86, dt / TICK);
    if (shake < 0.05) shake = 0;
    for (const u of units) u.shown += (u.hp - u.shown) * Math.min(1, dt / 160);
    if (phase === 'aim') {
      const k = S.rm ? 1 : (clock - phT) / 450, sh = units[0];
      sh.angle = sh.aimFrom + (plan.angle - sh.aimFrom) * ease(k);
      if (k >= 1) startFlight();
    } else if (phase === 'flight') {
      tickAcc += dt / TICK;
      while (tickAcc >= 1 && phase === 'flight') { tickAcc--; tickFlight(); }
    } else if (phase === 'boom') {
      if (clock - phT >= 600) { phase = 'fall'; tickAcc = 0; }
    } else if (phase === 'fall') {
      tickAcc += dt / TICK;
      while (tickAcc >= 1 && phase === 'fall') { tickAcc--; tickFall(); }
    } else if (phase === 'settle') {
      let pending = false;
      for (const u of units) {
        if (!u.alive || u.hp > 0) continue;
        if (u.downAt < 0) u.downAt = clock;
        const el = clock - u.downAt;
        u.fade = el > 1300 ? Math.max(0, 1 - (el - 1300) / 400) : 1;
        if (el > 1700) u.alive = false; else pending = true;
      }
      if (!pending && clock - phT > 300) { phase = 'idle'; trails = []; seeds = []; }
    }
    booms = booms.filter((b) => clock - b.t0 < 600);
    dmgs = dmgs.filter((d) => clock - d.t0 < 1000);
    const k = dt / TICK;
    for (let i = parts.length - 1; i >= 0; i--) {
      const p = parts[i];
      p.life -= k;
      if (p.life <= 0) { parts.splice(i, 1); continue; }
      p.vy += p.g * k; p.vx += p.w * S.wind * 0.0012 * k;
      p.vx *= 1 - (1 - p.drag) * k; p.vy *= 1 - (1 - p.drag) * k;
      p.x += p.vx * k; p.y += p.vy * k; p.rot += p.vr * k;
    }
  }

  // ── 포대 상태(앱 unitPose.ts 와 같은 우선순위·길이) ─────────────────
  function poseOf(u) {
    if (u.hp <= 0 && u.downAt >= 0) return ['down', clock - u.downAt];
    if (clock - u.hitAt < 400) return ['hit', clock - u.hitAt];
    if (clock - u.recoilAt < 200) return ['recoil', clock - u.recoilAt];
    const loop = (S.rm ? 0 : clock) + u.seat * 137;
    return [u.seat === 0 && (phase === 'idle' || phase === 'aim') ? 'aim' : 'idle', loop];
  }
  /** 상태 → 움직임 값. 시안은 이 값에 자기 과장 정도를 곱해 쓴다. */
  function motion(pose, el) {
    const o = { bob: 0, sway: 0, back: 0, sq: 0, flash: 0, jx: 0, wilt: 0, eyes: 'open', wig: 0 };
    if (pose === 'idle') { o.bob = Math.sin((el / 1040) * Math.PI * 2); o.sway = Math.sin(el / 900); o.eyes = el % 3200 < 140 && !S.rm ? 'shut' : 'open'; }
    else if (pose === 'aim') { o.bob = Math.sin((el / 520) * Math.PI * 2) * 0.5; o.wig = Math.sin(el / 300) * 1.4; o.eyes = 'focus'; }
    else if (pose === 'recoil') { const k = 1 - Math.min(1, el / 200); o.back = k * k; o.sq = k; o.eyes = 'shut'; }
    else if (pose === 'hit') { const k = 1 - Math.min(1, el / 400); o.flash = el < 110 ? 1 : el < 240 && (Math.floor(el / 55) & 1) ? 0.6 : 0; o.jx = Math.sin(el / 22) * 1.6 * k; o.sq = -k; o.eyes = 'x'; }
    else { const k = Math.min(1, el / 700); o.wilt = 1 - (1 - k) * (1 - k); o.eyes = 'x'; }
    if (S.rm) { o.jx = 0; o.sq = 0; if (pose === 'hit') o.flash = el < 240 ? 0.6 : 0; if (pose === 'recoil') o.back = 0.5; if (pose === 'down') o.wilt = 1; }
    return o;
  }
  const U = { kind: 'SPROUT', color: '', seat: 0, pose: 'idle', el: 0, o: null, time: 0, top: 0, angle: 0 };
  function drawUnit(ctx, u, pose, el, scale, isTurn, top) {
    const o = motion(pose, el);
    U.kind = u.kind; U.color = u.color; U.seat = u.seat; U.pose = pose; U.el = el; U.o = o; U.time = S.rm ? 0 : clock; U.top = top; U.angle = u.angle;
    ctx.save();
    ctx.translate(u.x + 0.5 + o.jx, u.footY + 1);
    ctx.scale(scale, scale);
    ctx.globalAlpha = u.fade;
    if (T.under) T.under(ctx, U, isTurn);
    if (pose !== 'down') {
      ctx.save();
      ctx.translate(0, -5.5);
      ctx.rotate((-(u.angle + o.wig) * Math.PI) / 180);
      ctx.translate(-o.back * 2, 0);
      T.barrel(ctx, U);
      ctx.restore();
    }
    ctx.save();
    if (u.angle > 90) ctx.scale(-1, 1);
    T.body(ctx, U);
    ctx.restore();
    if (isTurn && pose !== 'down') T.turn(ctx, U);
    ctx.restore();
    ctx.globalAlpha = 1;
  }

  // ── 장면 그리기(앱 drawScene 과 같은 순서) ──────────────────────────
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  function fit(c) {
    const w = Math.round(c.clientWidth * dpr), hh = Math.round(c.clientHeight * dpr);
    if (w && hh && (c.width !== w || c.height !== hh)) { c.width = w; c.height = hh; }
    return c.getContext('2d');
  }
  function drawField(c) {
    if (!c.clientWidth) return;
    const ctx = fit(c), sc = c.width / W, cpc = c.clientWidth / W, P = T.pal[S.mode], amb = S.rm ? 0 : clock;
    const boost = Math.min(1.6, Math.max(1, 0.75 / cpc)), fs = Math.max(7, 11 / cpc), ui = Math.max(1, 0.85 / cpc);
    ctx.setTransform(sc, 0, 0, sc, 0, 0);
    ctx.globalAlpha = 1; ctx.globalCompositeOperation = 'source-over';
    T.sky(ctx, P, amb, S.wind);
    ctx.save();
    if (shake && !S.rm) ctx.translate((Math.random() - 0.5) * shake, (Math.random() - 0.5) * shake);
    ctx.imageSmoothingEnabled = !!T.smooth;
    ctx.drawImage(terr, 0, 0, W, H);
    const top = -(T.lift * boost + fs * 1.7) / boost;
    for (const u of units) {
      if (!u.alive) continue;
      const [pose, el] = poseOf(u);
      drawUnit(ctx, u, pose, el, boost, u.seat === 0 && phase === 'idle', top);
    }
    const me = units[0];
    if (phase === 'idle' && me.alive) {
      const rad = (me.angle * Math.PI) / 180, cx = me.x + 0.5, cy = me.footY - 4.5;
      ctx.strokeStyle = me.color; ctx.lineWidth = 1.2; ctx.setLineDash([2.5, 2.5]);
      ctx.beginPath(); ctx.moveTo(cx + Math.cos(rad) * 13 * boost, cy - Math.sin(rad) * 13 * boost); ctx.lineTo(cx + Math.cos(rad) * 40, cy - Math.sin(rad) * 40); ctx.stroke();
      ctx.setLineDash([]);
    }
    ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
    for (const u of units) {
      if (!u.alive) continue;
      ctx.globalAlpha = u.fade;
      const text = u.seat === 0 ? (fs > 10 ? '나' : '새싹이 (나)') : fs > 10 && [...u.name].length > 6 ? [...u.name].slice(0, 5).join('') + '…' : u.name;
      T.label(ctx, { x: u.x + 0.5, footY: u.footY, text, hp: u.shown, maxHp: u.maxHp, fs, unitScale: boost, color: u.color, team: TEAMC[u.seat % 2] }, P);
    }
    ctx.globalAlpha = 1;
    for (const tr of trails) T.trail(ctx, tr, P);
    for (const s of seeds) {
      if (!s.live) continue;
      if (s.y < 0) { ctx.fillStyle = P.label; ctx.beginPath(); ctx.moveTo(s.x, 2); ctx.lineTo(s.x - 4, 9); ctx.lineTo(s.x + 4, 9); ctx.fill(); }
      else T.seed(ctx, s.x, s.y, s.shell, S.rm ? 0 : s.rot, P);
    }
    for (const b of booms) T.boom(ctx, { x: b.x, y: b.y, r: b.r, t: (clock - b.t0) / 600, fill: b.fill }, P);
    for (const p of parts) T.particle(ctx, p, p.life / p.max, P);
    ctx.globalAlpha = 1; ctx.globalCompositeOperation = 'source-over';
    for (const d of dmgs) T.dmg(ctx, d.u.x + 0.5, d.u.footY - T.lift * boost - fs * 2.3, d.amt, (clock - d.t0) / 1000, fs * 1.15, P);
    ctx.restore();
    ctx.globalAlpha = 1;
    T.clouds(ctx, cloudLevel, P, amb);
    ctx.save(); ctx.translate(W / 2, 6 * ui); ctx.scale(ui, ui);
    T.wind(ctx, S.wind, P, amb);
    ctx.restore();
  }

  // ── 작은 그림(캐릭터 시트·견본) ─────────────────────────────────────
  const minis = [];
  let grounds = {};
  function mini(host, wc, hc, draw, cap, cls) {
    const f = document.createElement('figure'), c = document.createElement('canvas');
    c.style.aspectRatio = wc + '/' + hc;
    if (cls) f.className = cls;
    f.append(c);
    if (cap) { const fc = document.createElement('figcaption'); fc.textContent = cap; f.append(fc); }
    host.append(f);
    minis.push({ c, wc, hc, draw });
  }
  function backdrop(ctx, wc, hc, P, ground) {
    const g = ctx.createLinearGradient(0, 0, 0, hc);
    g.addColorStop(0, P.skyTop); g.addColorStop(1, P.skyBot);
    ctx.fillStyle = g; ctx.fillRect(0, 0, wc, hc);
    if (!ground) return;
    const key = wc + 'x' + hc;
    if (!grounds[key]) {
      const m = new Uint8Array(wc * hc);
      for (let x = 0; x < wc; x++) for (let y = hc - ground + Math.round(Math.sin(x / 5) * 0.6); y < hc; y++) m[y * wc + x] = 1;
      grounds[key] = paint(m, wc, hc, true);
    }
    ctx.imageSmoothingEnabled = !!T.smooth;
    ctx.drawImage(grounds[key], 0, 0, wc, hc);
  }
  function drawMinis() {
    const P = T.pal[S.mode], amb = S.rm ? 0 : clock;
    for (const m of minis) {
      if (!m.c.clientWidth) continue;
      const ctx = fit(m.c), sc = m.c.width / m.wc;
      ctx.setTransform(sc, 0, 0, sc, 0, 0);
      ctx.globalAlpha = 1; ctx.globalCompositeOperation = 'source-over';
      m.draw(ctx, P, amb);
      ctx.globalAlpha = 1; ctx.globalCompositeOperation = 'source-over';
    }
  }
  function buildSheet() {
    const sheet = $('#sheet');
    sheet.append(document.createElement('span'));
    for (const [, n] of POSES) { const s = document.createElement('span'); s.className = 'hd'; s.textContent = n; sheet.append(s); }
    Object.keys(KIND).forEach((kind, row) => {
      const rh = document.createElement('span'); rh.className = 'rh'; rh.textContent = KIND[kind].n; sheet.append(rh);
      for (const [pose] of POSES) {
        const u = { x: 12, footY: 23, kind, color: SEATC[row], seat: row, angle: 40, fade: 1 };
        mini(sheet, 30, 30, (ctx, P, amb) => {
          backdrop(ctx, 30, 30, P, 6);
          let p = pose, el = amb + row * 137;
          if (pose === 'recoil') { el = S.rm ? 60 : clock % 1300; if (el >= 200) { p = 'aim'; el = amb; } }
          else if (pose === 'hit') { el = S.rm ? 150 : clock % 1500; if (el >= 400) { p = 'idle'; el = amb; } }
          else if (pose === 'down') { el = S.rm ? 2000 : clock % 3400; if (el >= 3000) { p = 'idle'; el = amb; } }
          drawUnit(ctx, u, p, el, 1, false, -20);
        });
      }
    });
  }
  function buildStrip() {
    const strip = $('#strip');
    for (const k of Object.keys(SHELL)) mini(strip, 22, 16, (ctx, P, amb) => { backdrop(ctx, 22, 16, P, 0); T.seed(ctx, 11, 8, k, amb / 260, P); }, SHELL[k].n);
    const loop = (fill) => (ctx, P) => {
      backdrop(ctx, 48, 40, P, 12);
      const t = S.rm ? 0.35 : (clock % 1500) / 600;
      if (t < 1) T.boom(ctx, { x: 24, y: 27, r: 12, t, fill }, P);
    };
    mini(strip, 48, 40, loop(false), '폭발 (0.6초)');
    mini(strip, 48, 40, loop(true), '둔덕 — 덩굴탄');
    mini(strip, 96, 40, (ctx, P, amb) => {
      backdrop(ctx, 96, 40, P, 0);
      const n = S.rm ? 30 : 6 + (Math.floor(clock / 33) % 40), pts = [];
      for (let i = 0; i <= Math.min(n, 34); i++) pts.push(8 + i * 2.4, 34 - i * 2.1 + i * i * 0.062);
      T.trail(ctx, pts, P);
      T.seed(ctx, pts[pts.length - 2], pts[pts.length - 1], 'PEA', amb / 200, P);
    }, '궤적·꼬리', 'wide');
    [[100, '체력 100%'], [45, '체력 45%'], [18, '체력 18%']].forEach(([hp, cap], i) => mini(strip, 48, 24, (ctx, P) => {
      backdrop(ctx, 48, 24, P, 0);
      ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
      T.label(ctx, { x: 24, footY: T.lift + 19, text: ['새싹이 (나)', '도토리단', '솜털이'][i], hp, maxHp: 100, fs: 7.5, unitScale: 1, color: SEATC[i], team: TEAMC[i % 2] }, P);
    }, cap));
    for (const w of [4, -9, 0]) mini(strip, 96, 26, (ctx, P, amb) => { backdrop(ctx, 96, 26, P, 0); ctx.save(); ctx.translate(48, 5); T.wind(ctx, w, P, amb); ctx.restore(); }, '바람 ' + (w > 0 ? '+' : '') + w, 'wide');
  }

  // ── 화면 뼈대 ───────────────────────────────────────────────────────
  function seatsHtml() {
    const sig = units.map((u) => u.hp + (u.alive ? 'a' : 'x')).join() + phase;
    if (sig === seatSig) return;
    seatSig = sig;
    const html = units.map((u) => `<div class="seat${u.seat === 0 && phase === 'idle' ? ' turn' : ''}${u.alive && u.hp > 0 ? '' : ' out'}" style="--c:${u.color};--t:${TEAMC[u.seat % 2]}"><b>${u.name}</b><span>${KIND[u.kind].n} · ${u.alive ? u.hp : 0}/${u.maxHp}</span></div>`).join('');
    document.querySelectorAll('[data-seats]').forEach((el) => { el.innerHTML = html; });
    document.querySelectorAll('[data-fire]').forEach((b) => { b.disabled = phase !== 'idle'; b.textContent = foes().length ? '발사' : '처음으로'; });
  }
  function setMode(m) {
    S.mode = m; document.documentElement.dataset.mode = m;
    $('#mode').setAttribute('aria-pressed', String(m === 'dark')); $('#mode').textContent = m === 'dark' ? '낮(라이트)' : '밤(다크)';
    grounds = {}; repaint(); swatches();
  }
  function swatches() {
    const P = T.pal[S.mode];
    $('#sw').innerHTML = T.swatch.map(([k, n]) => { const v = Array.isArray(P[k]) ? `rgb(${P[k].join(',')})` : P[k]; return `<span style="background:linear-gradient(${v},${v}) top/100% 14px no-repeat">${n}</span>`; }).join('');
  }
  function init() {
    reset();
    const shells = $('#shells');
    for (const k of Object.keys(SHELL)) {
      const b = document.createElement('button');
      b.textContent = SHELL[k].n; b.setAttribute('aria-pressed', String(k === S.shell));
      b.onclick = () => { S.shell = k; shells.querySelectorAll('button').forEach((x) => x.setAttribute('aria-pressed', String(x === b))); };
      shells.append(b);
    }
    document.querySelectorAll('[data-fire]').forEach((b) => { b.onclick = fire; });
    $('#reset').onclick = reset;
    $('#mode').onclick = () => setMode(S.mode === 'dark' ? 'light' : 'dark');
    $('#rm').checked = S.rm;
    $('#rm').onchange = (e) => { S.rm = e.target.checked; parts.length = 0; };
    mq.addEventListener('change', (e) => { S.rm = e.matches; $('#rm').checked = S.rm; });
    $('#wind').oninput = (e) => { S.wind = Number(e.target.value); $('#windOut').textContent = (S.wind > 0 ? '+' : '') + S.wind; };
    $('#notes').innerHTML = T.notes.map((n) => `<li>${n}</li>`).join('');
    buildSheet(); buildStrip(); swatches();
    let last = 0;
    const cvD = $('#cvD'), cvM = $('#cvM');
    const frame = (ts) => {
      update(Math.min(50, last ? ts - last : 16)); last = ts;
      drawField(cvD); drawField(cvM); drawMinis(); seatsHtml();
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
    window.__artillery = { fire, reset, setMode, setShell: (k) => { S.shell = k; }, setRm: (v) => { S.rm = v; }, step: (ms) => { for (let t = 0; t < ms; t += 16) update(16); drawField(cvD); drawField(cvM); drawMinis(); seatsHtml(); }, state: () => ({ phase, units: units.map((u) => ({ seat: u.seat, hp: u.hp, alive: u.alive })), parts: parts.length }) };
  }
  init();
})();
