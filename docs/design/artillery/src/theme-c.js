// @name 시안 C — 해 질 녘 실루엣
// @lede 노을을 등진 종이 오림 그림자극. 섬과 포대는 짙은 실루엣이고, 좌석 색은 화분의 등불 띠·눈·포구 불씨로만 빛난다. 씨앗은 빛 꼬리를 끄는 불씨, 폭발은 섬광과 빛 고리 — 가장 차분하고 극적인 분위기.
/*CSS
:root{--bg:#2a1c38;--fg:#ffeeda;--muted:#c9a9b4;--card:#35233f;--line:#5b3f5f;--accent:#ffb36b;--btnfg:#2a1c38;--radius:6px;--font:system-ui,-apple-system,"Apple SD Gothic Neo","Malgun Gothic",sans-serif;--hfont:var(--font)}
[data-mode=dark]{--bg:#070b1a;--fg:#e6eeff;--muted:#93a3c8;--card:#0e1530;--line:#27345e;--accent:#9fc2ff;--btnfg:#070b1a}
h1,h2{font-weight:600;letter-spacing:.02em}
CSS*/
(function () {
  'use strict';
  const FONT = 'system-ui,-apple-system,"Apple SD Gothic Neo","Malgun Gothic",sans-serif';
  const HP = ['#8dffa0', '#ffd772', '#ff7b6b'];
  const SEEDC = { PEA: [255, 236, 170], PUMPKIN: [255, 150, 60], VINE: [140, 255, 150], CHESTNUT: [255, 110, 70], SPORE: [190, 240, 255] };
  const P = () => THEME.pal[E.S.mode];
  const al = (ctx, a, fn) => { ctx.save(); ctx.globalAlpha *= a; fn(); ctx.restore(); };
  const line = (ctx, x1, y1, x2, y2) => { ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke(); };
  const rgb = (hex) => [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
  /** 빛 번짐 — 가운데가 밝고 가장자리로 사라지는 원. */
  function glow(ctx, x, y, r, c, a) {
    const g = ctx.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, E.css(c, a)); g.addColorStop(0.4, E.css(c, a * 0.45)); g.addColorStop(1, E.css(c, 0));
    ctx.fillStyle = g; ctx.fillRect(x - r, y - r, r * 2, r * 2);
  }
  function blade(ctx, ang, len, wid, col, rim) {
    ctx.save(); ctx.rotate(ang);
    ctx.fillStyle = col; ctx.beginPath(); ctx.moveTo(0, 0.6); ctx.quadraticCurveTo(len * 0.4, -wid, len, 0); ctx.quadraticCurveTo(len * 0.4, wid, 0, 0.6); ctx.fill();
    ctx.strokeStyle = rim; ctx.lineWidth = 0.35; ctx.beginPath(); ctx.moveTo(0.6, 0); ctx.quadraticCurveTo(len * 0.4, -wid, len, 0); ctx.stroke();
    ctx.restore();
  }
  function muzzle(ctx, kind, color, x, lit) {
    const p = P(), c = rgb(color);
    if (kind === 'DANDELION') {
      if (lit) glow(ctx, x + 0.8, 0, 5, [255, 255, 255], 0.3 * lit);
      ctx.strokeStyle = E.css(p.rim, 0.35 + 0.5 * lit); ctx.lineWidth = 0.3;
      for (let i = 0; i < 12; i++) line(ctx, x + 0.8, 0, x + 0.8 + Math.cos(i * 0.524) * 3, Math.sin(i * 0.524) * 3);
      ctx.fillStyle = p.sil; E.disc(ctx, x + 0.8, 0, 0.9);
    } else if (kind === 'ACORN') {
      ctx.fillStyle = p.sil; ctx.beginPath(); ctx.ellipse(x + 0.9, 0, 2.5, 2, 0, 0, 7); ctx.fill();
      ctx.beginPath(); ctx.arc(x, 0, 2.7, Math.PI / 2, Math.PI * 1.5); ctx.fill();
      ctx.strokeStyle = E.css(p.rim, 0.6); ctx.lineWidth = 0.35; ctx.beginPath(); ctx.arc(x + 0.9, 0, 2.3, -1.2, 0.2); ctx.stroke();
      if (lit) { glow(ctx, x + 2.4, 0, 3, c, 0.7 * lit); ctx.fillStyle = E.css(E.mix(c, [255, 255, 255], 0.5), lit); E.disc(ctx, x + 2.4, 0, 0.6); }
    } else {
      ctx.fillStyle = p.sil; ctx.beginPath(); ctx.moveTo(x - 2, 0); ctx.quadraticCurveTo(x - 0.4, -2.5, x + 2.6, 0); ctx.quadraticCurveTo(x - 0.4, 2.5, x - 2, 0); ctx.fill();
      ctx.strokeStyle = E.css(p.rim, 0.6); ctx.lineWidth = 0.35; ctx.beginPath(); ctx.moveTo(x - 1.6, -0.6); ctx.quadraticCurveTo(x - 0.2, -2.3, x + 2.4, -0.2); ctx.stroke();
      if (lit) { glow(ctx, x + 2.4, 0, 3, c, 0.75 * lit); ctx.fillStyle = E.css(E.mix(c, [255, 255, 255], 0.5), lit); E.disc(ctx, x + 2.4, 0, 0.6); }
    }
  }

  window.THEME = {
    id: 'c', lift: 17, smooth: false, shake: 3,
    pal: {
      light: { stops: ['#35275e', '#a04b7c', '#f08a5d', '#ffc977'], skyTop: '#7a3f7c', skyBot: '#f6a467', sil: '#1c1428', silHi: [62, 40, 70], rim: [255, 206, 140], c1: '#f7b58c', c2: '#c9708a', c3: '#5a3566', far: '#7d466f', label: '#fff3e0', plate: 'rgba(28,20,40,.72)', glow: [255, 217, 160], sun: '#fff1c4', sunXY: [330, 288, 40], stars: 14 },
      dark: { stops: ['#050816', '#0f1b40', '#22386a', '#4a6a96'], skyTop: '#0b1230', skyBot: '#34507e', sil: '#05070f', silHi: [18, 26, 52], rim: [150, 190, 255], c1: '#7f9cc8', c2: '#41598c', c3: '#16224a', far: '#1a2850', label: '#eaf2ff', plate: 'rgba(5,7,15,.72)', glow: [188, 212, 255], sun: '#f2f6ff', sunXY: [520, 70, 17], stars: 70 },
    },
    swatch: [['skyTop', '하늘'], ['skyBot', '노을'], ['sil', '실루엣'], ['silHi', '실루엣 윗면'], ['rim', '테두리 빛'], ['c1', '구름 1'], ['c2', '구름 2'], ['c3', '구름 3'], ['label', '글자']],
    notes: [
      '<b>대기</b> — 등불 띠가 천천히 숨 쉬듯 밝아졌다 어두워진다(잎은 거의 안 움직임). 포대 뒤에 좌석 색 빛 번짐.',
      '<b>조준</b> — 눈빛이 가늘어지고 포구 불씨가 밝아진다. 차례 주인에게 위에서 빛줄기가 내리고 작은 마름모 빛이 떠 있다.',
      '<b>발사 반동</b>(200ms) — 포구 섬광(흰빛 원)이 번쩍, 포신이 2칸 물러난다. 불티 8개가 포신 방향으로 흩어진다.',
      '<b>탄 궤적</b> — 빛 꼬리: 머리로 갈수록 굵고 밝아지는 선(가산 혼합) + 탄 색 빛 번짐. 탄마다 불빛 색이 다르다.',
      '<b>착탄</b>(0.6초) — 첫 90ms 흰 섬광 → 주황 빛 덩이가 퍼지며 사라지고 빛 고리가 지나간다. 실루엣 흙 조각이 빛을 등지고 튀고, 불티가 포물선으로 떨어진다. 화면 흔들림 중간(3칸).',
      '<b>둔덕</b> — 초록 빛이 번지고 실루엣 덩굴 다섯 가닥이 말리며 솟는다.',
      '<b>피격</b>(400ms) — 실루엣이 좌석 색 빛으로 뒤집혀 번쩍, 빛 번짐이 커졌다 줄어든다. 피해 숫자는 빛나는 글씨로 떠오른다.',
      '<b>쓰러짐</b>(0.7초) — 등불 띠가 깜빡이다 꺼지고(빛 번짐도 사라짐) 잎이 처진다. 꺼진 뒤 연기 한 줄.',
      '<b>바람</b> — 위쪽 빛 막대: 가운데 점에서 바람 쪽으로 뻗는 선(길이 = 세기) 위를 빛 알갱이가 달린다. 하늘에 가는 바람 줄과 반딧불 같은 꽃가루가 흘러간다.',
      '<b>연출 줄이기</b> — 섬광·흔들림·입자·맥박 끔. 번쩍임은 옅은 색 덧칠 한 번.',
    ],
    soil(x, y, d, e, under, p) {
      if (d === 1) return p.rim;
      if (d === 2) return E.mix(p.rim, p.silHi, 0.62);
      const c = E.mix(p.silHi, rgb(p.sil), Math.min(1, d / 26));
      const k = under ? 14 : E.h(x, y) > 0.985 ? 6 : 0;
      return [c[0] + k, c[1] + k, c[2] + k * 1.4];
    },
    above(x, y, dist, p) {
      const v = E.h(x, 1), s = rgb(p.sil);
      return (dist === 1 && v > 0.45) || (dist === 2 && v > 0.8) || (dist === 3 && v > 0.95) ? [s[0], s[1], s[2], 255] : null;
    },
    sky(ctx, p, t, w) {
      const g = ctx.createLinearGradient(0, 0, 0, 360);
      p.stops.forEach((c, i) => g.addColorStop([0, 0.42, 0.74, 1][i], c));
      ctx.fillStyle = g; ctx.fillRect(0, 0, 640, 360);
      ctx.fillStyle = p.label;
      for (let i = 0; i < p.stars; i++) al(ctx, (0.25 + 0.5 * E.h(i, 9)) * (0.6 + 0.4 * Math.sin(t / 700 + i * 1.7)), () => E.disc(ctx, E.h(i, 1) * 640, E.h(i, 2) * 150, 0.4 + E.h(i, 3) * 0.5));
      const [sx, sy, sr] = p.sunXY;
      glow(ctx, sx, sy, sr * 4.2, p.glow, 0.55);
      ctx.fillStyle = p.sun; E.disc(ctx, sx, sy, sr);
      ctx.fillStyle = p.far;
      [[80, 150, 60, 0.5], [250, 110, 34, 0.35], [575, 128, 48, 0.42], [420, 96, 22, 0.3]].forEach(([x, y, rw, a], i) => {
        const dx = Math.sin(t / 9000 + i * 2) * 4;
        al(ctx, a, () => { ctx.beginPath(); ctx.moveTo(x - rw + dx, y); ctx.lineTo(x - rw * 0.3 + dx, y - rw * 0.16); ctx.lineTo(x + rw * 0.4 + dx, y - rw * 0.1); ctx.lineTo(x + rw + dx, y); ctx.quadraticCurveTo(x + dx, y + rw, x - rw + dx, y); ctx.fill(); });
      });
      ctx.strokeStyle = E.css(p.glow, 1); ctx.lineWidth = 0.4; ctx.lineCap = 'round';
      if (w) for (let i = 0; i < 9; i++) {
        const x = (((i * 97 + t * 0.035 * w) % 720) + 720) % 720 - 40, y = 30 + ((i * 41) % 190), len = 10 + Math.abs(w) * 2.4;
        al(ctx, 0.22, () => line(ctx, x, y, x + len, y));
      }
      ctx.save(); ctx.globalCompositeOperation = 'lighter';
      for (let i = 0; i < 12; i++) {
        const x = (((i * 61 + t * (0.004 + 0.006 * w)) % 660) + 660) % 660 - 10, y = 120 + ((i * 53) % 170) + Math.sin(t / 900 + i * 2.1) * 7;
        glow(ctx, x, y, 2.6, p.glow, 0.35 + 0.3 * Math.sin(t / 400 + i));
      }
      ctx.restore();
    },
    clouds(ctx, level, p, t) {
      const band = (dy, col, sp, ph, amp) => { ctx.beginPath(); ctx.moveTo(0, 362); for (let x = 0; x <= 640; x += 8) ctx.lineTo(x, level + dy + Math.sin(x / 41 + ph + t / sp) * amp + Math.sin(x / 15 + ph * 2) * 1.2); ctx.lineTo(640, 362); ctx.closePath(); ctx.fillStyle = col; ctx.fill(); };
      al(ctx, 0.7, () => band(-9, p.c1, 2600, 0, 3)); al(ctx, 0.85, () => band(-5, p.c2, 1900, 2, 2.5));
      band(-1, p.c3, 1500, 4, 2);
      ctx.strokeStyle = E.css(p.rim, 0.5); ctx.lineWidth = 0.5; ctx.beginPath();
      for (let x = 0; x <= 640; x += 8) ctx.lineTo(x, level - 1 + Math.sin(x / 41 + 4 + t / 1500) * 2 + Math.sin(x / 15 + 8) * 1.2);
      ctx.stroke();
    },
    under(ctx, U, isTurn) {
      const lit = U.pose === 'down' ? 1 - U.o.wilt : 1, c = rgb(U.color);
      const pulse = U.pose === 'hit' ? 1 + U.o.flash * 0.6 : 1 + U.o.bob * 0.06;
      if (isTurn) {
        const g = ctx.createLinearGradient(0, -150, 0, 0);
        g.addColorStop(0, E.css(P().glow, 0)); g.addColorStop(1, E.css(P().glow, 0.2));
        ctx.fillStyle = g; ctx.beginPath(); ctx.moveTo(-4, -150); ctx.lineTo(4, -150); ctx.lineTo(11, 0); ctx.lineTo(-11, 0); ctx.fill();
      }
      if (lit > 0.02) glow(ctx, 0, -5, 17 * pulse, c, 0.42 * lit);
    },
    barrel(ctx, U) {
      const p = P(), b = U.o.back;
      ctx.lineCap = 'round';
      ctx.strokeStyle = p.sil; ctx.lineWidth = 2.1; line(ctx, 0, 0, 9, 0);
      ctx.strokeStyle = E.css(p.rim, 0.55); ctx.lineWidth = 0.35; line(ctx, 1, -0.95, 8, -0.95);
      muzzle(ctx, U.kind, U.color, 10, U.pose === 'aim' ? 1 : 0.7);
      if (b > 0.05) { ctx.save(); ctx.globalCompositeOperation = 'lighter'; glow(ctx, 13, 0, 3 + 7 * b, [255, 244, 220], 0.95 * b); ctx.restore(); }
    },
    body(ctx, U) {
      const o = U.o, p = P(), w = o.wilt, c = rgb(U.color), hi = E.mix(c, [255, 255, 255], 0.55);
      let lit = 1;
      if (U.pose === 'down') lit = E.S.rm ? 0 : U.el < 520 ? (Math.floor(U.el / 65) % 3 ? 0.9 : 0.2) * (1 - U.el / 800) : 0;
      else if (U.pose === 'idle') lit = 0.82 + 0.18 * o.bob;
      const rimC = E.css(p.rim, 0.65);
      const pot = () => { ctx.beginPath(); ctx.moveTo(-3.8, 0); ctx.lineTo(3.8, 0); ctx.lineTo(5.3, -6.2); ctx.lineTo(6.1, -6.2); ctx.lineTo(6.1, -7.8); ctx.lineTo(-6.1, -7.8); ctx.lineTo(-6.1, -6.2); ctx.lineTo(-5.3, -6.2); ctx.closePath(); };
      ctx.save();
      ctx.rotate(w * 0.28);
      ctx.scale(1 + o.sq * 0.06, 1 - o.sq * 0.07);
      ctx.save();
      ctx.translate(0, -7.6); ctx.rotate(o.sway * 0.025);
      const R = -0.8 + 1.5 * w, L = -Math.PI + 0.8 - 1.5 * w;
      if (U.kind === 'ACORN') { blade(ctx, L + 0.25, 7.2, 3.6, p.sil, rimC); blade(ctx, R - 0.1, 4.2, 2, p.sil, rimC); }
      else if (U.kind === 'DANDELION') { blade(ctx, L + 0.4, 8, 1, p.sil, rimC); blade(ctx, L - 0.25, 6, 0.9, p.sil, rimC); blade(ctx, R + 0.4, 8, 1, p.sil, rimC); blade(ctx, R - 0.3, 5, 0.9, p.sil, rimC); }
      else { blade(ctx, L, 6.6, 2.1, p.sil, rimC); blade(ctx, R, 6.6, 2.1, p.sil, rimC); }
      ctx.restore();
      if (U.pose === 'down') {
        ctx.strokeStyle = p.sil; ctx.lineWidth = 1.9; ctx.lineCap = 'round';
        ctx.beginPath(); ctx.moveTo(0, -7.5); ctx.quadraticCurveTo(1.5, -13.5, 4 + 3 * w, -15 + 7 * w); ctx.stroke();
        ctx.save(); ctx.translate(4 + 3 * w, -15 + 7 * w); ctx.rotate(0.9 * w); ctx.scale(0.8, 0.8); muzzle(ctx, U.kind, U.color, 1.5, 0); ctx.restore();
      }
      ctx.fillStyle = p.sil; pot(); ctx.fill();
      ctx.strokeStyle = rimC; ctx.lineWidth = 0.4; line(ctx, -6.1, -7.8, 6.1, -7.8); line(ctx, 6.1, -7.8, 6.1, -6.3);
      // 등불 띠 + 눈
      const e = o.eyes, fx = e === 'focus' ? 0.5 : 0;
      al(ctx, 0.3 * lit, () => { ctx.fillStyle = U.color; ctx.fillRect(-5, -3.4, 10, 2.6); });
      al(ctx, 0.12 + 0.88 * lit, () => {
        ctx.fillStyle = U.color; ctx.beginPath(); ctx.moveTo(-4.75, -2.7); ctx.lineTo(4.75, -2.7); ctx.lineTo(4.45, -1.5); ctx.lineTo(-4.45, -1.5); ctx.fill();
        ctx.fillStyle = E.css(hi);
        for (const sx of [-1.9, 2.1]) {
          if (e === 'x') { ctx.fillRect(sx - 0.7 + fx, -4.75, 1.4, 0.3); }
          else E.rr(ctx, sx - 0.6 + fx, e === 'open' ? -5.3 : -4.9, 1.2, e === 'open' ? 1.1 : 0.5, 0.25), ctx.fill();
        }
      });
      if (o.flash) { ctx.fillStyle = E.css(hi); al(ctx, o.flash * 0.9, () => { pot(); ctx.fill(); }); }
      ctx.restore();
    },
    turn(ctx, U) {
      const y = U.top - 2 + Math.sin(U.time / 300) * 1.2, c = rgb(U.color);
      ctx.save(); ctx.globalCompositeOperation = 'lighter'; glow(ctx, 0, y, 7, c, 0.8); ctx.restore();
      ctx.fillStyle = E.css(E.mix(c, [255, 255, 255], 0.7)); ctx.beginPath(); ctx.moveTo(0, y - 2.6); ctx.lineTo(1.9, y); ctx.lineTo(0, y + 2.6); ctx.lineTo(-1.9, y); ctx.fill();
    },
    trail(ctx, pts, p) {
      const n = pts.length / 2;
      if (n < 2) return;
      ctx.save(); ctx.globalCompositeOperation = 'lighter'; ctx.lineCap = 'round';
      for (let i = 1; i < n; i++) {
        const k = i / n;
        ctx.strokeStyle = E.css(p.glow, 0.08 + 0.75 * k * k); ctx.lineWidth = 0.3 + 1.5 * k;
        line(ctx, pts[i * 2 - 2], pts[i * 2 - 1], pts[i * 2], pts[i * 2 + 1]);
      }
      ctx.restore();
    },
    seed(ctx, x, y, shell, rot) {
      const c = SEEDC[shell] || SEEDC.PEA, big = shell === 'PUMPKIN' || shell === 'CHESTNUT' ? 1.5 : shell === 'SPORE' ? 0.75 : 1;
      ctx.save(); ctx.translate(x, y);
      ctx.globalCompositeOperation = 'lighter'; glow(ctx, 0, 0, 6.5 * big, c, 0.85);
      ctx.globalCompositeOperation = 'source-over';
      ctx.rotate(rot);
      ctx.strokeStyle = E.css(c); ctx.lineWidth = 0.45; ctx.lineCap = 'round';
      if (shell === 'CHESTNUT') for (let i = 0; i < 8; i++) line(ctx, Math.cos(i * 0.785) * 2, Math.sin(i * 0.785) * 2, Math.cos(i * 0.785) * 3.6, Math.sin(i * 0.785) * 3.6);
      if (shell === 'SPORE') { line(ctx, -2.6, 0, 2.6, 0); line(ctx, 0, -2.6, 0, 2.6); }
      if (shell === 'VINE') { ctx.beginPath(); ctx.arc(1.8, -1.4, 1.5, Math.PI * 0.5, Math.PI * 2); ctx.stroke(); }
      if (shell === 'PUMPKIN') { ctx.beginPath(); ctx.ellipse(0, 0, 3.1, 2.5, 0, 0, 7); ctx.stroke(); }
      ctx.fillStyle = '#fff'; E.disc(ctx, 0, 0, 1.15 * big);
      ctx.restore();
    },
    boom(ctx, e, p) {
      const k = E.ease(e.t), a = Math.max(0, 1 - e.t);
      ctx.save(); ctx.translate(e.x, e.y);
      if (e.fill) {
        ctx.globalCompositeOperation = 'lighter'; glow(ctx, 0, 0, e.r * (0.8 + 0.8 * k), [130, 255, 150], 0.7 * a);
        ctx.globalCompositeOperation = 'source-over';
        ctx.strokeStyle = p.sil; ctx.lineWidth = 1.2; ctx.lineCap = 'round';
        for (let i = 0; i < 5; i++) {
          const bx = (i - 2) * e.r * 0.32, hgt = e.r * (0.7 + 0.35 * E.h(i, 4)) * k, cur = (i % 2 ? 1 : -1) * e.r * 0.3;
          ctx.beginPath(); ctx.moveTo(bx, e.r * 0.4); ctx.quadraticCurveTo(bx + cur, e.r * 0.4 - hgt * 0.6, bx - cur * 0.4, e.r * 0.4 - hgt); ctx.stroke();
          ctx.beginPath(); ctx.arc(bx - cur * 0.4 + 1.2, e.r * 0.4 - hgt, 1.2, Math.PI, Math.PI * 2.6); ctx.stroke();
        }
      } else {
        ctx.globalCompositeOperation = 'lighter';
        if (e.t < 0.15 && !E.S.rm) { ctx.fillStyle = `rgba(255,255,255,${0.9 * (1 - e.t / 0.15)})`; E.disc(ctx, 0, 0, e.r * 1.15); }
        glow(ctx, 0, 0, e.r * (0.5 + 1.1 * k), [255, 170, 80], 0.95 * a);
        glow(ctx, 0, 0, e.r * (0.25 + 0.5 * k), [255, 250, 230], a);
        ctx.strokeStyle = E.css(p.rim, 0.9 * a); ctx.lineWidth = Math.max(0.01, 1.4 * a); ctx.beginPath(); ctx.arc(0, 0, e.r * (0.3 + 1.05 * k), 0, 7); ctx.stroke();
      }
      ctx.restore();
    },
    particle(ctx, q, k, p) {
      ctx.save(); ctx.translate(q.x, q.y);
      if (q.kind === 'spark') { ctx.globalCompositeOperation = 'lighter'; glow(ctx, 0, 0, q.size * 2.6, q.color, Math.min(1, k * 2)); ctx.fillStyle = `rgba(255,255,255,${Math.min(1, k * 2)})`; E.disc(ctx, 0, 0, q.size * 0.4); }
      else if (q.kind === 'smoke') { ctx.fillStyle = p.sil; ctx.globalAlpha = 0.35 * k; E.disc(ctx, 0, 0, q.size * (2 - k)); }
      else { ctx.rotate(q.rot); ctx.fillStyle = p.sil; ctx.globalAlpha = Math.min(1, k * 3); ctx.beginPath(); ctx.moveTo(-q.size, q.size * 0.6); ctx.lineTo(q.size * 0.2, -q.size); ctx.lineTo(q.size, q.size * 0.7); ctx.fill(); }
      ctx.restore();
    },
    fx: {
      muzzle(x, y, ang) {
        const r = (ang * Math.PI) / 180, c = Math.cos(r), s = -Math.sin(r);
        for (let i = 0; i < 8; i++) E.emit('spark', x, y, { vx: c * (1 + Math.random() * 2.4) + Math.random() - 0.5, vy: s * (1 + Math.random() * 2.4) + Math.random() - 0.5, g: 0.04, life: 16 + Math.random() * 14, size: 0.9, color: [255, 220, 150], drag: 0.94 });
      },
      impact(x, y, r, fill) {
        for (let i = 0; i < Math.round(r * 0.8); i++) {
          const a = -Math.PI * (0.1 + 0.8 * Math.random()), v = 1.2 + Math.random() * 2.6;
          E.emit('shard', x + (Math.random() - 0.5) * r, y, { vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 0.09, life: 44 + Math.random() * 26, size: 0.8 + Math.random() * 1.4, vr: (Math.random() - 0.5) * 0.5 });
        }
        for (let i = 0; i < 16; i++) {
          const a = -Math.PI * Math.random(), v = 1 + Math.random() * 3.2;
          E.emit('spark', x, y, { vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 0.06, life: 30 + Math.random() * 40, size: 0.8 + Math.random() * 0.5, color: fill ? [140, 255, 150] : [255, 190, 110], drag: 0.97, w: 0.5 });
        }
        E.emit('smoke', x, y - 2, { vy: -0.25, life: 80, size: 3, w: 1 }); E.emit('smoke', x + 3, y - 1, { vy: -0.18, life: 100, size: 2.4, w: 1 });
      },
      split(x, y) { for (let i = 0; i < 8; i++) E.emit('spark', x, y, { vx: Math.random() * 3 - 1.5, vy: Math.random() * 3 - 1.5, life: 22, size: 0.8, color: [190, 240, 255], drag: 0.92 }); },
    },
    label(ctx, l, p) {
      const fs = l.fs, top = l.footY - THEME.lift * l.unitScale - fs * 0.6;
      ctx.font = `500 ${fs * 0.9}px ${FONT}`;
      const tw = ctx.measureText(l.text).width, pw = Math.max(tw + fs * 0.9, fs * 3), ph = fs * 1.75;
      ctx.fillStyle = p.plate; E.rr(ctx, l.x - pw / 2, top - fs * 0.92, pw, ph, fs * 0.25); ctx.fill();
      ctx.fillStyle = p.label; ctx.fillText(l.text, l.x, top);
      const bw = pw - fs * 0.6, bx = l.x - bw / 2, by = top + fs * 0.42, bh = Math.max(0.9, fs * 0.16), ratio = Math.max(0, Math.min(1, l.hp / l.maxHp));
      ctx.fillStyle = 'rgba(255,255,255,.22)'; ctx.fillRect(bx, by, bw, bh);
      ctx.fillStyle = E.hpColor(ratio, HP); ctx.fillRect(bx, by, bw * ratio, bh);
      ctx.fillStyle = l.color; ctx.fillRect(l.x - pw / 2, top - fs * 0.92, Math.max(0.8, fs * 0.14), ph);
    },
    dmg(ctx, x, y, amt, t, fs, p) {
      ctx.save();
      ctx.globalAlpha = t < 0.7 ? 1 : Math.max(0, 1 - (t - 0.7) / 0.3);
      const yy = y - E.ease(t) * 9;
      ctx.save(); ctx.globalCompositeOperation = 'lighter'; glow(ctx, x, yy - fs * 0.3, fs * 1.3, [255, 150, 110], 0.55); ctx.restore();
      ctx.font = `600 ${fs * 1.1}px ${FONT}`; ctx.textAlign = 'center'; ctx.lineJoin = 'round';
      ctx.lineWidth = fs * 0.28; ctx.strokeStyle = p.sil; ctx.strokeText('−' + amt, x, yy);
      ctx.fillStyle = '#ffd9c2'; ctx.fillText('−' + amt, x, yy);
      ctx.restore();
    },
    wind(ctx, w, p, t) {
      const dir = Math.sign(w), len = Math.abs(w) * 3.6;
      ctx.save();
      ctx.fillStyle = p.plate; E.rr(ctx, -44, 1, 88, 12, 3); ctx.fill();
      ctx.strokeStyle = 'rgba(255,255,255,.2)'; ctx.lineWidth = 0.5;
      for (let i = -10; i <= 10; i += 2) line(ctx, i * 3.6, 9, i * 3.6, i % 10 ? 10.2 : 11.2);
      ctx.strokeStyle = E.css(p.glow); ctx.lineWidth = 1; ctx.lineCap = 'round';
      if (w) {
        line(ctx, 0, 7, dir * len, 7);
        ctx.beginPath(); ctx.moveTo(dir * (len - 2.4), 5); ctx.lineTo(dir * len, 7); ctx.lineTo(dir * (len - 2.4), 9); ctx.stroke();
        ctx.save(); ctx.globalCompositeOperation = 'lighter';
        for (let i = 0; i < 2; i++) { const u = ((t * 0.0006 * (1 + Math.abs(w) * 0.25) + i * 0.5) % 1) * len; glow(ctx, dir * u, 7, 3, p.glow, 0.9); }
        ctx.restore();
      }
      ctx.fillStyle = p.label; E.disc(ctx, 0, 7, 1.2);
      ctx.font = `600 6.5px ${FONT}`; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
      ctx.fillText(String(Math.abs(w)), -(dir || 1) * 6.5, 9.3);
      ctx.restore();
    },
  };
})();
