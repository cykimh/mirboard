// D-252 — 씨앗포 "말랑 클레이" 스킨의 그림 코드. **브라우저에서** 돈다(Canvas 2D) — 생성기
// `scripts/generate-artillery-clay.mjs` 가 크로미움에 이 파일을 넣고 `CLAY_ART.build()` 를 불러 시트 한 장을 받는다.
//
// 그림은 전부 여기 도형으로 새로 빚은 것이다(화분 포대·씨앗·별 모양 폭발 — 다른 게임의 그림을 본뜨지 않았다).
// 좌표는 전장 칸(y 는 아래로), 포대 기준점은 발(화분 바닥 가운데)이고 몸은 오른쪽을 보고 그린다. 좌석 색이 들어갈 자리는
// 그림에서 비워 두고(음영·광택만 반투명으로) 흰 실루엣(tint)을 따로 굽는다 — 형식은 `docs/artillery-skins.md`.
(function () {
  'use strict';
  /** 시트 픽셀 / 전장 칸. 데스크톱 고해상도에서 포대(1.25배)가 칸당 3.6 장치 픽셀쯤이라 6 이면 넉넉하다. */
  const PPC = 6;
  const SHEET_W = 1024;
  const PAD = 2;
  const NAVY = '#22304a';
  const TAU = Math.PI * 2;
  const KINDS = ['SPROUT', 'ACORN', 'DANDELION'];

  // ── 도형 도우미 ─────────────────────────────────────────────────────
  const disc = (c, x, y, r) => { c.beginPath(); c.arc(x, y, r, 0, TAU); c.fill(); };
  const ell = (c, x, y, rx, ry, rot) => { c.beginPath(); c.ellipse(x, y, rx, ry, rot || 0, 0, TAU); c.fill(); };
  const line = (c, x1, y1, x2, y2) => { c.beginPath(); c.moveTo(x1, y1); c.lineTo(x2, y2); c.stroke(); };
  function rr(c, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    c.beginPath();
    c.moveTo(x + r, y); c.lineTo(x + w - r, y); c.quadraticCurveTo(x + w, y, x + w, y + r);
    c.lineTo(x + w, y + h - r); c.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    c.lineTo(x + r, y + h); c.quadraticCurveTo(x, y + h, x, y + h - r);
    c.lineTo(x, y + r); c.quadraticCurveTo(x, y, x + r, y); c.closePath();
  }
  function star(c, r, n, inner, rot) {
    c.beginPath();
    for (let i = 0; i < n * 2; i++) {
      const a = rot + (i * Math.PI) / n, q = i % 2 ? r * inner : r;
      c.lineTo(Math.cos(a) * q, Math.sin(a) * q);
    }
    c.closePath();
  }
  /** 점토 공의 그늘·광택만(바탕색은 따로) — 가장자리로 갈수록 어둡고 아래에 초승달 그늘, 왼쪽 위에 광택 둘. */
  function shade(c, x, y, r) {
    c.save();
    c.beginPath(); c.arc(x, y, r, 0, TAU); c.clip();
    const g = c.createRadialGradient(x - r * 0.3, y - r * 0.35, r * 0.2, x, y, r * 1.05);
    g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(0.7, 'rgba(0,0,0,.05)'); g.addColorStop(1, 'rgba(0,0,0,.26)');
    c.fillStyle = g; c.fillRect(x - r, y - r, r * 2, r * 2);
    c.fillStyle = 'rgba(0,0,0,.13)'; disc(c, x + r * 0.3, y + r * 0.8, r);
    c.restore();
    c.fillStyle = 'rgba(255,255,255,.7)'; ell(c, x - r * 0.36, y - r * 0.42, r * 0.3, r * 0.19, -0.6);
    c.fillStyle = 'rgba(255,255,255,.5)'; disc(c, x - r * 0.02, y - r * 0.64, r * 0.08);
  }
  function ball(c, x, y, r, col) { c.fillStyle = col; disc(c, x, y, r); shade(c, x, y, r); }
  /** 통통한 잎 — 밑동이 원점, {@code ang} 방향으로 뻗는다. */
  function fatLeaf(c, ang, len, wid, col, vein) {
    c.save(); c.rotate(ang);
    c.fillStyle = col; ell(c, len / 2, 0, len / 2, wid);
    c.save(); c.beginPath(); c.ellipse(len / 2, 0, len / 2, wid, 0, 0, TAU); c.clip();
    c.fillStyle = 'rgba(0,0,0,.16)'; ell(c, len / 2, wid * 0.75, len / 2, wid);
    c.strokeStyle = 'rgba(0,0,0,.14)'; c.lineWidth = 0.5; c.beginPath(); c.ellipse(len / 2, 0, len / 2, wid, 0, 0, TAU); c.stroke();
    c.restore();
    c.strokeStyle = vein; c.lineWidth = 0.32; c.lineCap = 'round'; line(c, len * 0.14, 0, len * 0.84, 0);
    c.fillStyle = 'rgba(255,255,255,.55)'; ell(c, len * 0.6, -wid * 0.42, len * 0.17, wid * 0.16);
    c.restore();
  }

  // ── 화분 몸 ─────────────────────────────────────────────────────────
  const potPath = (c) => { c.beginPath(); c.moveTo(-3.4, 0); c.quadraticCurveTo(-4.3, 0, -4.5, -1); c.lineTo(-5.4, -6.4); c.lineTo(5.4, -6.4); c.lineTo(4.5, -1); c.quadraticCurveTo(4.3, 0, 3.4, 0); c.closePath(); };
  const rimPath = (c) => rr(c, -6.3, -8.2, 12.6, 2.6, 1.3);
  const BODY = { w: 14, h: 10.5, ax: 7, ay: 9.5 };

  function potTint(c, kind) {
    c.fillStyle = '#fff';
    potPath(c); c.fill();
    if (kind !== 'ACORN') { rimPath(c); c.fill(); }
  }
  function potFlash(c) { c.fillStyle = '#fff'; potPath(c); c.fill(); rimPath(c); c.fill(); }

  function face(c, mood) {
    c.lineCap = 'round'; c.lineJoin = 'round';
    // 볼
    c.fillStyle = 'rgba(255,110,120,.42)'; ell(c, -3.7, -2.25, 0.95, 0.55); ell(c, 3.9, -2.25, 0.95, 0.55);
    const y = -3.75;
    for (const sx of [-2.15, 2.25]) {
      if (mood === 'open' || mood === 'focus') {
        const fx = mood === 'focus' ? 0.5 : 0.18;
        c.fillStyle = 'rgba(0,0,0,.16)'; disc(c, sx, y + 0.18, 1.82);
        c.fillStyle = '#fff'; disc(c, sx, y, 1.75);
        c.fillStyle = NAVY; disc(c, sx + fx, y + (mood === 'focus' ? -0.1 : 0.12), 0.98);
        c.fillStyle = '#fff'; disc(c, sx + fx - 0.34, y - 0.32, 0.33); disc(c, sx + fx + 0.3, y + 0.42, 0.15);
        if (mood === 'focus') {
          c.strokeStyle = NAVY; c.lineWidth = 0.62;
          line(c, sx - 1.3, y - 2.25 - (sx < 0 ? 0.55 : 0), sx + 1.3, y - 2.25 - (sx < 0 ? 0 : 0.55));
        }
      } else if (mood === 'blink') {
        c.strokeStyle = NAVY; c.lineWidth = 0.7;
        c.beginPath(); c.arc(sx, y - 0.2, 1.2, Math.PI * 0.15, Math.PI * 0.85); c.stroke();
      } else if (mood === 'shut') {
        // 꾹 감은 눈 > <
        c.strokeStyle = NAVY; c.lineWidth = 0.72;
        const d = sx < 0 ? 1 : -1;
        c.beginPath(); c.moveTo(sx - d * 0.95, y - 0.95); c.lineTo(sx + d * 0.75, y); c.lineTo(sx - d * 0.95, y + 0.95); c.stroke();
      } else {
        c.strokeStyle = NAVY; c.lineWidth = 0.72;
        line(c, sx - 1, y - 1, sx + 1, y + 1); line(c, sx + 1, y - 1, sx - 1, y + 1);
      }
    }
    c.strokeStyle = NAVY; c.fillStyle = NAVY; c.lineWidth = 0.55;
    if (mood === 'x') {
      ell(c, 0.05, -1.25, 0.8, 0.68);
      c.fillStyle = '#ff7a86'; ell(c, 0.05, -0.98, 0.45, 0.3);
    } else if (mood === 'dizzy') {
      c.beginPath(); c.moveTo(-1.2, -1.3); c.quadraticCurveTo(-0.6, -1.9, 0, -1.3); c.quadraticCurveTo(0.6, -0.7, 1.2, -1.3); c.stroke();
    } else if (mood === 'shut') {
      ell(c, 0.05, -1.45, 0.55, 0.5);
    } else if (mood === 'focus') {
      c.beginPath(); c.moveTo(-0.7, -1.55); c.quadraticCurveTo(0.2, -1.2, 1.05, -1.75); c.stroke();
    } else {
      c.beginPath(); c.arc(0.05, -2.0, 0.98, Math.PI * 0.18, Math.PI * 0.82); c.stroke();
    }
  }

  /** 화분 그림 — 좌석 색 자리(몸통·테)는 비우고 음영·광택만 얹는다. */
  function potArt(c, kind, mood) {
    // 흙(테 위로 살짝)
    c.fillStyle = '#6b4226'; ell(c, 0, -8.15, 4.5, 0.72);
    c.fillStyle = 'rgba(255,255,255,.14)'; ell(c, -1.2, -8.35, 1.8, 0.25);
    // 몸통
    c.save(); potPath(c); c.clip();
    let g = c.createLinearGradient(-5.4, 0, 5.4, 0);
    g.addColorStop(0, 'rgba(255,255,255,.12)'); g.addColorStop(0.42, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(0,0,0,.27)');
    c.fillStyle = g; c.fillRect(-7, -9, 14, 10);
    g = c.createLinearGradient(0, -6.4, 0, 0);
    g.addColorStop(0, 'rgba(0,0,0,.26)'); g.addColorStop(0.17, 'rgba(0,0,0,0)'); g.addColorStop(0.68, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(0,0,0,.24)');
    c.fillStyle = g; c.fillRect(-7, -9, 14, 10);
    c.fillStyle = 'rgba(255,255,255,.5)'; ell(c, -3.8, -3.4, 0.5, 2, 0.14);
    c.fillStyle = 'rgba(255,255,255,.32)'; disc(c, -3.35, -0.85, 0.3);
    c.strokeStyle = 'rgba(0,0,0,.2)'; c.lineWidth = 0.9; potPath(c); c.stroke();
    c.restore();
    // 테
    if (kind === 'ACORN') { c.fillStyle = '#8a5630'; rimPath(c); c.fill(); }
    c.save(); rimPath(c); c.clip();
    c.fillStyle = kind === 'ACORN' ? 'rgba(255,255,255,.12)' : 'rgba(255,255,255,.27)'; c.fillRect(-7, -9, 14, 4);
    g = c.createLinearGradient(0, -8.2, 0, -5.6);
    g.addColorStop(0.5, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(0,0,0,.2)');
    c.fillStyle = g; c.fillRect(-7, -9, 14, 4);
    c.strokeStyle = 'rgba(0,0,0,.18)'; c.lineWidth = 0.7; rimPath(c); c.stroke();
    c.restore();
    c.fillStyle = 'rgba(255,255,255,.66)'; rr(c, -5.25, -7.86, 4.7, 0.8, 0.4); c.fill();
    if (kind === 'ACORN') { c.fillStyle = 'rgba(0,0,0,.22)'; for (let i = 0; i < 5; i++) disc(c, -4.4 + i * 2.2, -6.45, 0.45); }
    if (kind === 'DANDELION') { c.fillStyle = '#fff'; for (let i = 0; i < 4; i++) disc(c, -3.6 + i * 2.4, -6.55, 0.5); }
    face(c, mood);
  }

  // ── 잎(기준점 = 테 위 가운데) ──────────────────────────────────────
  const LEAF = { w: 18, h: 10.5, ax: 9, ay: 7.5 };
  function leaves(c, kind, wilted) {
    const col = wilted ? '#a5b54c' : '#4fc95e', vein = wilted ? 'rgba(90,100,30,.5)' : 'rgba(30,120,60,.55)';
    const R = -0.75, L = -Math.PI + 0.75;
    if (kind === 'ACORN') fatLeaf(c, L + 0.15, 7, 2.9, col, vein);
    else if (kind === 'DANDELION') { fatLeaf(c, L - 0.3, 5.4, 1, col, vein); fatLeaf(c, L + 0.35, 7.4, 1.1, col, vein); fatLeaf(c, R + 0.35, 7.4, 1.1, col, vein); }
    else { fatLeaf(c, L, 6.2, 2, col, vein); fatLeaf(c, R, 6.2, 2, col, vein); }
  }

  // ── 포신(기준점 = 회전축, 오른쪽 0°) ───────────────────────────────
  const BARREL = { w: 16, h: 8, ax: 2, ay: 4 };
  /** 포구 — {@code tint} 면 좌석 색 자리만 흰색으로. */
  function muzzle(c, kind, x, tint) {
    if (kind === 'ACORN') {
      if (tint) return;
      ball(c, x + 1, 0, 2.5, '#e2a760');
      c.fillStyle = '#7a4a26'; c.beginPath(); c.arc(x + 0.2, 0, 2.7, Math.PI / 2, Math.PI * 1.5); c.closePath(); c.fill();
      c.fillStyle = 'rgba(0,0,0,.16)'; c.beginPath(); c.arc(x + 0.2, 0, 2.7, Math.PI / 2, Math.PI * 0.95); c.closePath(); c.fill();
      c.fillStyle = 'rgba(255,255,255,.28)'; for (const dy of [-1.4, 0, 1.4]) disc(c, x - 1.1, dy, 0.4);
      c.fillStyle = '#5a3418'; rr(c, x - 3, -0.45, 0.9, 0.9, 0.3); c.fill();
    } else if (kind === 'DANDELION') {
      if (tint) return;
      c.fillStyle = '#b9d2ea'; for (let i = 0; i < 8; i++) disc(c, x + 0.8 + Math.cos(i * 0.785) * 2.05, Math.sin(i * 0.785) * 2.05, 1.32);
      c.fillStyle = '#fff'; for (let i = 0; i < 8; i++) disc(c, x + 0.8 + Math.cos(i * 0.785) * 2, Math.sin(i * 0.785) * 2, 1.15);
      ball(c, x + 0.8, 0, 2, '#ffffff');
      ball(c, x + 0.8, 0, 0.95, '#ffd23a');
    } else {
      if (tint) { c.fillStyle = '#fff'; disc(c, x + 0.4, 0, 2.3); return; }
      shade(c, x + 0.4, 0, 2.3);
      c.fillStyle = '#3fb857'; ell(c, x - 1.4, -1.5, 1.3, 0.7, 0.8); ell(c, x - 1.4, 1.5, 1.3, 0.7, -0.8);
      c.fillStyle = 'rgba(255,255,255,.4)'; ell(c, x - 1.5, -1.7, 0.5, 0.2, 0.8);
    }
  }
  function barrel(c, kind, tint) {
    if (tint) {
      c.strokeStyle = '#fff'; c.lineWidth = 1.6; c.lineCap = 'butt'; line(c, 6.4, -1.75, 6.4, 1.75);
      muzzle(c, kind, 10, true);
      return;
    }
    c.lineCap = 'round';
    c.strokeStyle = '#1f8240'; c.lineWidth = 3.8; line(c, 0, 0, 8.6, 0);
    c.strokeStyle = '#2fa650'; c.lineWidth = 3.1; line(c, 0, 0, 8.6, 0);
    c.strokeStyle = 'rgba(0,0,0,.14)'; c.lineWidth = 1.2; line(c, 0.4, 0.95, 8.2, 0.95);
    c.strokeStyle = '#7fe08c'; c.lineWidth = 0.9; line(c, 0.5, -0.85, 7.6, -0.85);
    c.strokeStyle = 'rgba(0,0,0,.2)'; c.lineWidth = 0.45; c.lineCap = 'butt'; line(c, 3.1, -1.5, 3.1, 1.5);
    // 좌석 색 띠의 음영
    c.fillStyle = 'rgba(0,0,0,.2)'; c.fillRect(5.6, 0.5, 1.6, 1.25);
    c.fillStyle = 'rgba(255,255,255,.4)'; c.fillRect(5.6, -1.45, 1.6, 0.5);
    muzzle(c, kind, 10, false);
  }

  // ── 쓰러졌을 때 축 처진 포신(기준점 = 몸의 발) ─────────────────────
  const DROOP = { w: 11, h: 9, ax: 2, ay: 14.5 };
  function droop(c, kind, tint) {
    if (!tint) {
      c.lineCap = 'round';
      c.strokeStyle = '#1f8240'; c.lineWidth = 3.1; c.beginPath(); c.moveTo(0, -7.5); c.quadraticCurveTo(0.5, -13, 4.5, -11); c.stroke();
      c.strokeStyle = '#2fa650'; c.lineWidth = 2.5; c.beginPath(); c.moveTo(0, -7.5); c.quadraticCurveTo(0.5, -13, 4.5, -11); c.stroke();
    }
    c.save(); c.translate(4.5, -11); c.rotate(0.5); c.scale(0.8, 0.8); muzzle(c, kind, 1.5, tint); c.restore();
  }

  // ── 씨앗(가운데 기준) ──────────────────────────────────────────────
  const SEED = { w: 11, h: 11, ax: 5.5, ay: 5.5 };
  function seed(c, shell) {
    c.fillStyle = NAVY;
    if (shell === 'PUMPKIN') {
      disc(c, 0, 0, 3.95);
      ball(c, 0, 0, 3.4, '#ff8a2a');
      c.strokeStyle = 'rgba(150,62,0,.5)'; c.lineWidth = 0.5;
      c.beginPath(); c.ellipse(0, 0, 1.3, 3.2, 0, 0, TAU); c.stroke();
      c.beginPath(); c.ellipse(0, 0, 2.6, 3.3, 0, 0, TAU); c.stroke();
      c.fillStyle = NAVY; rr(c, -1, -4.9, 2, 2.2, 0.7); c.fill();
      c.fillStyle = '#3fb857'; rr(c, -0.7, -4.6, 1.4, 1.8, 0.5); c.fill();
    } else if (shell === 'VINE') {
      disc(c, 0, 0, 2.95);
      ball(c, 0, 0, 2.4, '#7ddc4a');
      c.strokeStyle = '#2a9a4a'; c.lineWidth = 0.7; c.lineCap = 'round';
      c.beginPath(); c.arc(0.2, 0.2, 1.1, 0, Math.PI * 1.5); c.stroke();
      c.strokeStyle = NAVY; c.lineWidth = 1.5; c.beginPath(); c.moveTo(1.7, -1.9); c.quadraticCurveTo(3.4, -3.4, 4.2, -2.2); c.stroke();
      c.strokeStyle = '#4fc95e'; c.lineWidth = 0.8; c.beginPath(); c.moveTo(1.7, -1.9); c.quadraticCurveTo(3.4, -3.4, 4.2, -2.2); c.stroke();
    } else if (shell === 'CHESTNUT') {
      c.fillStyle = NAVY; star(c, 5.2, 9, 0.7, 0); c.fill();
      c.fillStyle = '#5a3418'; star(c, 4.7, 9, 0.68, 0); c.fill();
      ball(c, 0, 0, 3.2, '#9a5a2c');
      c.fillStyle = '#f1cf9a'; ell(c, 0, 1.7, 2, 0.9);
    } else if (shell === 'SPORE') {
      disc(c, 0, 0, 2.6);
      c.fillStyle = '#fff'; for (let i = 0; i < 6; i++) disc(c, Math.cos(i * 1.047) * 1.3, Math.sin(i * 1.047) * 1.3, 0.92);
      c.fillStyle = 'rgba(120,150,190,.35)'; for (let i = 0; i < 6; i++) disc(c, Math.cos(i * 1.047) * 1.45 + 0.15, Math.sin(i * 1.047) * 1.45 + 0.25, 0.45);
      ball(c, 0, 0, 0.85, '#ffd23a');
    } else {
      disc(c, 0, 0, 2.95);
      ball(c, 0, 0, 2.4, '#b6e04a');
      c.fillStyle = 'rgba(60,120,20,.35)'; ell(c, 0.5, 0.4, 0.9, 0.5, 0.5);
    }
  }

  // ── 폭발·둔덕(시트 픽셀, 가운데 기준, 반경 BOOM_R 의 구멍에 맞춘 그림) ──
  const BOOM_R = 42;
  const BOOM = { w: 106, h: 106, ax: 53, ay: 53 };
  function boom(c, k) {
    const R = BOOM_R;
    if (k === 0) {
      c.fillStyle = '#ffe27a'; star(c, R * 0.78, 12, 0.62, 0.13); c.fill();
      c.fillStyle = '#fff'; star(c, R * 0.62, 12, 0.6, 0); c.fill();
    } else if (k <= 3) {
      const core = [0, 0.44, 0.3, 0.14][k], mid = [0, 0.82, 0.78, 0.66][k];
      c.fillStyle = '#e2491c'; star(c, R * 1.12, 12, 0.68, 0); c.fill();
      c.fillStyle = '#ff6a2a'; star(c, R * 1.04, 12, 0.66, 0); c.fill();
      c.save(); star(c, R * 1.04, 12, 0.66, 0); c.clip();
      c.fillStyle = 'rgba(255,255,255,.22)'; ell(c, -R * 0.3, -R * 0.42, R * 0.7, R * 0.42, -0.5);
      c.restore();
      c.fillStyle = '#ffb52e'; star(c, R * (mid + 0.05), 12, 0.63, 0.26); c.fill();
      c.fillStyle = '#ffd23a'; star(c, R * mid, 12, 0.62, 0.26); c.fill();
      if (core) { c.fillStyle = '#fff'; star(c, R * core, 8, 0.6, 0); c.fill(); }
      if (k === 3) {
        c.globalCompositeOperation = 'destination-out';
        for (let i = 0; i < 6; i++) disc(c, Math.cos(i * 1.047 + 0.4) * R * 0.5, Math.sin(i * 1.047 + 0.4) * R * 0.5, R * 0.1);
        disc(c, 0, 0, R * 0.16);
        c.globalCompositeOperation = 'source-over';
      }
    } else {
      // 점토 뭉치로 흩어진다
      const far = k === 4 ? 0.86 : 1.02, r1 = k === 4 ? 0.17 : 0.1, r2 = k === 4 ? 0.12 : 0.06;
      c.globalAlpha = k === 4 ? 0.95 : 0.7;
      for (let i = 0; i < 12; i++) ball(c, Math.cos(i * 0.5236) * R * far, Math.sin(i * 0.5236) * R * far, R * r1, i % 2 ? '#ff6a2a' : '#ff8a3a');
      for (let i = 0; i < 6; i++) ball(c, Math.cos(i * 1.047 + 0.3) * R * (far - 0.42), Math.sin(i * 1.047 + 0.3) * R * (far - 0.42), R * r2, '#ffd23a');
      c.globalAlpha = 1;
    }
  }
  const MOUND = { w: 106, h: 120, ax: 53, ay: 67 };
  function mound(c, k) {
    const R = BOOM_R;
    const sx = [1.2, 0.84, 1.05, 1][k], sy = [0.5, 1.22, 0.95, 1][k], size = [0.55, 0.95, 1, 1][k];
    const leaf = [0, 0.35, 0.8, 1][k];
    c.save();
    c.translate(0, R * 0.72); c.scale(sx * size, sy * size); c.translate(0, -R * 0.72);
    c.fillStyle = '#2a9a4a'; for (let i = 0; i < 9; i++) disc(c, Math.cos(i * 0.7) * R * 0.62, Math.sin(i * 0.7) * R * 0.62, R * 0.36);
    c.fillStyle = '#3fb857'; for (let i = 0; i < 9; i++) disc(c, Math.cos(i * 0.7) * R * 0.6 - R * 0.03, Math.sin(i * 0.7) * R * 0.6 - R * 0.04, R * 0.32);
    ball(c, 0, 0, R * 0.72, '#5fd15a');
    if (leaf > 0) {
      c.translate(0, -R * 0.68); c.scale(leaf, leaf);
      fatLeaf(c, -Math.PI / 2 - 0.6, R * 0.6, R * 0.2, '#7ddc4a', 'rgba(30,120,60,.5)');
      fatLeaf(c, -Math.PI / 2 + 0.6, R * 0.6, R * 0.2, '#7ddc4a', 'rgba(30,120,60,.5)');
    }
    c.restore();
  }

  // ── 조각 모으기 ─────────────────────────────────────────────────────
  /** @type {{key:string,w:number,h:number,ax:number,ay:number,tint:boolean,draw:(c:CanvasRenderingContext2D)=>void}[]} */
  const pieces = [];
  /** 칸 단위 조각 — 기준점이 원점이 되게 옮기고 PPC 배로 그린다. */
  const cells = (key, box, draw, tint) => pieces.push({ key, w: Math.round(box.w * PPC), h: Math.round(box.h * PPC), ax: box.ax * PPC, ay: box.ay * PPC, tint: !!tint, draw: (c) => { c.translate(box.ax * PPC, box.ay * PPC); c.scale(PPC, PPC); draw(c); } });
  const pixels = (key, box, draw) => pieces.push({ key, w: box.w, h: box.h, ax: box.ax, ay: box.ay, tint: false, draw: (c) => { c.translate(box.ax, box.ay); draw(c); } });

  const MOODS = { idle: ['open', 'blink'], aim: ['focus'], recoil: ['shut'], hit: ['x'], down: ['dizzy'] };
  const anims = {};
  const pt = (box) => [Math.round(box.ax * PPC), Math.round(box.ay * PPC)];

  for (const kind of KINDS) {
    cells(`t.pot.${kind}`, BODY, (c) => potTint(c, kind), true);
    cells(`t.barrel.${kind}`, BARREL, (c) => barrel(c, kind, true), true);
    cells(`t.droop.${kind}`, DROOP, (c) => droop(c, kind, true), true);
  }
  for (const kind of KINDS) {
    for (const [pose, moods] of Object.entries(MOODS)) {
      const frames = moods.map((m) => { const key = `pot.${kind}.${m}`; cells(key, BODY, (c) => potArt(c, kind, m)); return key; });
      anims[`unit.${kind}.${pose}`] = {
        frames, tint: frames.map(() => `t.pot.${kind}`), anchor: pt(BODY), mount: [BODY.ax * PPC, (BODY.ay - 5.5) * PPC],
        ...(pose === 'idle' ? { frameMs: 1600 } : {}), ...(pose === 'down' ? { barrel: false, loop: false } : {}),
      };
    }
    cells(`leaves.${kind}.0`, LEAF, (c) => leaves(c, kind, false));
    cells(`leaves.${kind}.1`, LEAF, (c) => leaves(c, kind, true));
    anims[`leaves.${kind}`] = { frames: [`leaves.${kind}.0`, `leaves.${kind}.1`], anchor: pt(LEAF) };
    cells(`barrel.${kind}`, BARREL, (c) => barrel(c, kind, false));
    anims[`barrel.${kind}`] = { frames: [`barrel.${kind}`], tint: [`t.barrel.${kind}`], anchor: pt(BARREL) };
    cells(`droop.${kind}`, DROOP, (c) => droop(c, kind, false));
    anims[`droop.${kind}`] = { frames: [`droop.${kind}`], tint: [`t.droop.${kind}`], anchor: pt(DROOP) };
  }
  cells('flash', BODY, (c) => potFlash(c), true);
  anims.flash = { frames: ['flash'], anchor: pt(BODY) };
  for (const shell of ['PEA', 'PUMPKIN', 'VINE', 'CHESTNUT', 'SPORE']) {
    cells(`seed.${shell}`, SEED, (c) => seed(c, shell));
    anims[`seed.${shell}`] = { frames: [`seed.${shell}`], anchor: pt(SEED) };
  }
  for (const [name, box, draw, n] of [['explosion', BOOM, boom, 6], ['mound', MOUND, mound, 4]]) {
    const frames = [];
    for (let k = 0; k < n; k++) { pixels(`${name}.${k}`, box, (c) => draw(c, k)); frames.push(`${name}.${k}`); }
    anims[name] = { frames, anchor: [box.ax, box.ay], radius: BOOM_R, loop: false };
  }

  /** 선반 묶기 — 좌석 색 실루엣을 먼저(한 덩어리로 모여야 색칠용 캔버스가 작다), 그다음 높이 순. */
  function build() {
    const order = [...pieces].sort((a, b) => Number(b.tint) - Number(a.tint) || b.h - a.h || (a.key < b.key ? -1 : 1));
    const rects = {};
    let x = 0, y = 0, rowH = 0, wasTint = true;
    for (const p of order) {
      if (x + p.w > SHEET_W || (wasTint && !p.tint)) { x = 0; y += rowH + PAD; rowH = 0; }
      wasTint = p.tint;
      rects[p.key] = [x, y, p.w, p.h];
      x += p.w + PAD; rowH = Math.max(rowH, p.h);
    }
    const H = y + rowH;
    const sheet = document.createElement('canvas');
    sheet.width = SHEET_W; sheet.height = H;
    const g = sheet.getContext('2d');
    for (const p of pieces) {
      const [rx, ry, w, h] = rects[p.key];
      g.save();
      g.beginPath(); g.rect(rx, ry, w, h); g.clip();
      g.translate(rx, ry);
      p.draw(g);
      g.restore();
      g.globalAlpha = 1; g.globalCompositeOperation = 'source-over';
    }
    const out = {};
    for (const [k, a] of Object.entries(anims)) {
      out[k] = { ...a, frames: a.frames.map((f) => rects[f]) };
      if (a.tint) out[k].tint = a.tint.map((f) => rects[f]);
    }
    const data = g.getImageData(0, 0, SHEET_W, H).data;
    let bin = '';
    for (let i = 0; i < data.length; i += 0x8000) bin += String.fromCharCode.apply(null, data.subarray(i, i + 0x8000));
    return { width: SHEET_W, height: H, pxPerCell: PPC, pieces: pieces.length, anims: out, rgba: btoa(bin) };
  }

  window.CLAY_ART = { build };
})();
