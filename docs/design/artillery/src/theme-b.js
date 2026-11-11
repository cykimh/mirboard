// @name 시안 B — 말랑 클레이 장난감
// @lede 손으로 빚은 점토 장난감 상자. 선명한 원색, 통통한 덩어리, 반짝이는 광택, 큰 눈. 모든 움직임이 눌렸다 튕기고(스쿼시·스트레치), 폭발은 별 모양으로 "팡" 터진다 — 가장 밝고 장난스러운 분위기.
/*CSS
:root{--bg:#fff6e6;--fg:#22304a;--muted:#5d6b85;--card:#ffffff;--line:#22304a;--accent:#ff5a3c;--btnfg:#ffffff;--radius:999px;--font:"Arial Rounded MT Bold","Jua","Apple SD Gothic Neo","Malgun Gothic",system-ui,sans-serif;--hfont:var(--font)}
[data-mode=dark]{--bg:#1c2147;--fg:#ffffff;--muted:#b4bbe0;--card:#272d5e;--line:#8d95d6;--accent:#ff7a5c;--btnfg:#1c2147}
.stage{border-width:3px;box-shadow:0 6px 0 var(--line)}
button{border-width:2.5px;font-weight:700}
CSS*/
(function () {
  'use strict';
  const FONT = '"Arial Rounded MT Bold","Jua","Apple SD Gothic Neo","Malgun Gothic",system-ui,sans-serif';
  const HP = ['#4fd45a', '#ffc23a', '#ff5a4c'];
  const NAVY = '#22304a';
  const P = () => THEME.pal[E.S.mode];
  const al = (ctx, a, fn) => { ctx.save(); ctx.globalAlpha *= a; fn(); ctx.restore(); };
  const line = (ctx, x1, y1, x2, y2) => { ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke(); };
  const ell = (ctx, x, y, rx, ry, rot) => { ctx.beginPath(); ctx.ellipse(x, y, rx, ry, rot || 0, 0, 7); ctx.fill(); };
  /** 점토 공 — 색 + 아래 그늘 + 왼쪽 위 광택. */
  function ball(ctx, x, y, r, col) {
    ctx.fillStyle = col; E.disc(ctx, x, y, r);
    ctx.save(); ctx.beginPath(); ctx.arc(x, y, r, 0, 7); ctx.clip();
    ctx.fillStyle = 'rgba(0,0,0,.18)'; E.disc(ctx, x + r * 0.25, y + r * 0.75, r);
    ctx.restore();
    ctx.fillStyle = 'rgba(255,255,255,.65)'; ell(ctx, x - r * 0.35, y - r * 0.4, r * 0.3, r * 0.2, -0.6);
  }
  function fatLeaf(ctx, ang, len, wid, col) {
    ctx.save(); ctx.rotate(ang);
    ctx.fillStyle = col; ell(ctx, len / 2, 0, len / 2, wid);
    ctx.fillStyle = 'rgba(0,0,0,.14)'; ell(ctx, len / 2, wid * 0.35, len / 2 * 0.9, wid * 0.6);
    ctx.fillStyle = col; ell(ctx, len / 2, -wid * 0.15, len / 2 * 0.92, wid * 0.7);
    ctx.fillStyle = 'rgba(255,255,255,.5)'; ell(ctx, len * 0.6, -wid * 0.4, len * 0.18, wid * 0.16);
    ctx.restore();
  }
  function star(ctx, r, n, inner, rot) {
    ctx.beginPath();
    for (let i = 0; i < n * 2; i++) { const a = rot + (i * Math.PI) / n, rr = i % 2 ? r * inner : r; ctx.lineTo(Math.cos(a) * rr, Math.sin(a) * rr); }
    ctx.closePath();
  }
  function muzzle(ctx, kind, color, x) {
    if (kind === 'ACORN') {
      ball(ctx, x + 1, 0, 2.5, '#e2a760');
      ctx.fillStyle = '#7a4a26'; ctx.beginPath(); ctx.arc(x + 0.2, 0, 2.7, Math.PI / 2, Math.PI * 1.5); ctx.closePath(); ctx.fill();
      ctx.fillStyle = 'rgba(255,255,255,.25)'; for (const dy of [-1.4, 0, 1.4]) E.disc(ctx, x - 1.1, dy, 0.4);
    } else if (kind === 'DANDELION') {
      ctx.fillStyle = '#ffffff'; for (let i = 0; i < 8; i++) E.disc(ctx, x + 0.8 + Math.cos(i * 0.785) * 2, Math.sin(i * 0.785) * 2, 1.15);
      ball(ctx, x + 0.8, 0, 2, '#ffffff');
      ctx.fillStyle = '#ffd23a'; E.disc(ctx, x + 0.8, 0, 0.9);
    } else {
      ball(ctx, x + 0.4, 0, 2.3, color);
      ctx.fillStyle = '#3fb857'; ell(ctx, x - 1.4, -1.5, 1.3, 0.7, 0.8); ell(ctx, x - 1.4, 1.5, 1.3, 0.7, -0.8);
    }
  }
  function face(ctx, U) {
    const e = U.o.eyes, fx = e === 'focus' ? 0.45 : 0.15;
    ctx.lineCap = 'round';
    for (const sx of [-2, 2.2]) {
      const y = -3.5;
      if (e === 'open' || e === 'focus') {
        ctx.fillStyle = '#fff'; E.disc(ctx, sx, y, 1.6);
        ctx.fillStyle = NAVY; E.disc(ctx, sx + fx, y + (e === 'focus' ? -0.1 : 0.1), 0.85);
        ctx.fillStyle = '#fff'; E.disc(ctx, sx + fx - 0.3, y - 0.3, 0.28);
        if (e === 'focus') { ctx.strokeStyle = NAVY; ctx.lineWidth = 0.6; line(ctx, sx - 1.2, y - 2 - (sx < 0 ? 0.5 : 0), sx + 1.2, y - 2 - (sx < 0 ? 0 : 0.5)); }
      } else {
        ctx.strokeStyle = NAVY; ctx.lineWidth = 0.7;
        if (e === 'shut') { ctx.beginPath(); ctx.arc(sx, y + 0.5, 1.1, Math.PI * 1.15, Math.PI * 1.85); ctx.stroke(); }
        else { line(ctx, sx - 0.9, y - 0.9, sx + 0.9, y + 0.9); line(ctx, sx + 0.9, y - 0.9, sx - 0.9, y + 0.9); }
      }
    }
    ctx.strokeStyle = NAVY; ctx.fillStyle = NAVY; ctx.lineWidth = 0.5;
    if (U.pose === 'hit' || U.pose === 'down') ell(ctx, 0.1, -1.3, 0.7, 0.55);
    else { ctx.beginPath(); ctx.arc(0.1, -1.9, 0.9, Math.PI * 0.2, Math.PI * 0.8); ctx.stroke(); }
  }

  window.THEME = {
    id: 'b', lift: 20, smooth: false, shake: 4,
    pal: {
      light: { skyTop: '#4fbdf0', skyBot: '#c4ecff', cloud: '#ffffff', cloudEdge: '#9fd0ea', label: '#22304a', plate: '#ffffff', sun: '#ffd84a', sunRing: '#ffe88a', grass: [116, 212, 74], grassHi: [176, 238, 112], grassD: [70, 166, 58], soil: [176, 104, 60], soilD: [136, 76, 44], pebble: [210, 142, 94] },
      dark: { skyTop: '#232a66', skyBot: '#6a58b0', cloud: '#d7dcff', cloudEdge: '#8d8fd0', label: '#ffffff', plate: '#ffffff', sun: '#fff3b8', sunRing: '#8f86d8', grass: [88, 184, 96], grassHi: [146, 220, 126], grassD: [52, 136, 78], soil: [152, 94, 88], soilD: [110, 64, 68], pebble: [190, 132, 120] },
    },
    swatch: [['skyTop', '하늘 위'], ['skyBot', '하늘 아래'], ['grassHi', '풀 광택'], ['grass', '풀'], ['grassD', '풀 그늘'], ['soil', '흙'], ['soilD', '속흙'], ['pebble', '자갈'], ['sun', '해·달']],
    notes: [
      '<b>대기</b> — 화분이 숨 쉬듯 눌렸다 늘어나고(±3%) 큰 눈을 깜빡인다. 잎은 귀처럼 까딱.',
      '<b>조준</b> — 눈썹이 생기고 눈동자가 포신 쪽으로. 차례 주인 위에서 통통한 화살표가 통통 튄다.',
      '<b>발사 반동</b>(200ms) — 포신이 짧고 굵게 찌그러졌다 펴지고, 화분은 납작(가로 +22%)해졌다 튕겨 돌아온다. 포구에서 하얀 고리 2개 + 별 조각.',
      '<b>탄 궤적</b> — 구슬 줄: 머리 쪽이 크고 꼬리로 갈수록 작아지는 흰 구슬(테두리 남색). 씨앗은 광택 있는 공으로 빙글빙글.',
      '<b>착탄</b>(0.6초) — 12각 별이 튕기듯 커졌다(오버슈트) 쏙 줄어든다. 흰 고리 + 네모난 흙덩이·색종이가 튀어 오른다. 화면 흔들림 가장 큼(4칸).',
      '<b>둔덕</b> — 초록 점토 덩어리가 "뿅" 솟고 잎 두 장이 귀처럼 튀어나온다.',
      '<b>피격</b>(400ms) — 몸 전체가 하얗게 번쩍, 세로로 늘어났다 좌우로 덜덜. 눈은 X, 입은 동그랗게. 피해 숫자는 흰 테두리 굵은 글씨가 튀어 오른다.',
      '<b>쓰러짐</b>(0.7초) — 화분이 옆으로 벌렁 넘어가 한 번 통 튄 뒤 눈이 X 가 된다. 잎이 축 처진다.',
      '<b>바람</b> — 위쪽 바람개비가 바람 세기만큼 빨리 돌고, 방향 쪽으로 화살표 알약이 1~4개. 하늘에 구불구불 바람 줄이 흘러간다.',
      '<b>연출 줄이기</b> — 눌림·튕김·흔들림·입자 끔, 바람개비 정지(숫자·화살표만).',
    ],
    soil(x, y, d, e, under, p) {
      const gT = 5 + (Math.sin(x / 4.2) + Math.sin(x / 9.5 + 1) > 0.4 ? 2 : 0);
      if (d === 1) return p.grassHi;
      if (d < gT) return p.grass;
      if (d === gT) return p.grassD;
      if (d <= gT + 2 || under || e === 1) return p.soilD;
      const gx = Math.floor(x / 10), gy = Math.floor(y / 9);
      const px = gx * 10 + 2 + E.h(gx, gy) * 6, py = gy * 9 + 2 + E.h(gy, gx + 5) * 5, r = 1.2 + E.h(gx + 3, gy) * 1.5;
      const dd = (x - px) * (x - px) + (y - py) * (y - py) * 1.6;
      if (E.h(gx, gy + 9) > 0.35 && dd < r * r) return y < py ? p.pebble : E.mix(p.pebble, p.soilD, 0.35);
      return p.soil;
    },
    sky(ctx, p, t, w) {
      const g = ctx.createLinearGradient(0, 0, 0, 360);
      g.addColorStop(0, p.skyTop); g.addColorStop(1, p.skyBot);
      ctx.fillStyle = g; ctx.fillRect(0, 0, 640, 360);
      ctx.fillStyle = p.sunRing; al(ctx, 0.35, () => E.disc(ctx, 84, 62, 44)); al(ctx, 0.5, () => E.disc(ctx, 84, 62, 33));
      ctx.fillStyle = p.sun; E.disc(ctx, 84, 62, 23);
      ctx.fillStyle = 'rgba(255,255,255,.55)'; ell(ctx, 76, 53, 6, 3.5, -0.6);
      if (E.S.mode === 'dark') {
        ctx.fillStyle = '#fff';
        for (let i = 0; i < 26; i++) { ctx.save(); ctx.translate(E.h(i, 1) * 640, E.h(i, 2) * 190); al(ctx, 0.5 + 0.5 * Math.sin(t / 500 + i), () => { star(ctx, 1 + E.h(i, 3) * 1.4, 4, 0.4, 0); ctx.fill(); }); ctx.restore(); }
      }
      for (let i = 0; i < 4; i++) {
        const x = (((i * 190 + 120 + t * 0.006 * (0.6 + w * 0.3)) % 800) + 800) % 800 - 80, y = 40 + ((i * 53) % 90), s = 0.8 + (i % 2) * 0.4;
        const puff = (dx, dy) => { E.disc(ctx, x + dx, y + dy, 10 * s); E.disc(ctx, x + 13 * s + dx, y + 3 * s + dy, 8 * s); E.disc(ctx, x - 12 * s + dx, y + 4 * s + dy, 7 * s); E.rr(ctx, x - 19 * s + dx, y + 3 * s + dy, 40 * s, 8 * s, 4 * s); ctx.fill(); };
        ctx.fillStyle = p.cloudEdge; puff(0, 2.5); ctx.fillStyle = p.cloud; puff(0, 0);
      }
      if (w) {
        ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 1.3; ctx.lineCap = 'round';
        al(ctx, 0.7, () => {
          for (let i = 0; i < 6; i++) {
            const x = (((i * 131 + t * 0.03 * w) % 700) + 700) % 700 - 30, y = 110 + ((i * 37) % 120);
            ctx.beginPath(); ctx.moveTo(x - 9, y); ctx.quadraticCurveTo(x - 4, y - 4, x, y); ctx.quadraticCurveTo(x + 4, y + 4, x + 9, y); ctx.stroke();
          }
        });
      }
    },
    clouds(ctx, level, p, t) {
      const row = (dy, ph, col) => { ctx.fillStyle = col; for (let i = -1; i < 47; i++) E.disc(ctx, i * 14 + ph, level + dy + Math.sin(t / 600 + i * 1.3 + ph) * 1.2, 9.5); ctx.fillRect(0, level + dy + 3, 640, 30); };
      row(-1, 7, p.cloudEdge); row(3, 0, p.cloud);
    },
    under(ctx, U) {
      ctx.fillStyle = 'rgba(0,0,0,.2)'; ell(ctx, 0, 0.4, 6.4, 1.3);
    },
    barrel(ctx, U) {
      const b = U.o.back;
      ctx.scale(1 - b * 0.22, 1 + b * 0.3);
      ctx.lineCap = 'round';
      ctx.strokeStyle = '#2a9a4a'; ctx.lineWidth = 3.4; line(ctx, 0, 0, 8.6, 0);
      ctx.strokeStyle = '#6fd67e'; ctx.lineWidth = 1; line(ctx, 0.5, -0.8, 7.6, -0.8);
      ctx.strokeStyle = U.color; ctx.lineWidth = 1.5; ctx.lineCap = 'butt'; line(ctx, 6.4, -1.7, 6.4, 1.7);
      muzzle(ctx, U.kind, U.color, 10);
    },
    body(ctx, U) {
      const o = U.o, w = o.wilt;
      const breathe = U.pose === 'idle' ? o.bob * 0.03 : 0;
      const bounce = U.pose === 'down' && !E.S.rm ? Math.max(0, Math.sin(Math.min(1, Math.max(0, (U.el - 700) / 260)) * Math.PI)) * 1.2 : 0;
      const pot = () => { ctx.beginPath(); ctx.moveTo(-3.4, 0); ctx.quadraticCurveTo(-4.3, 0, -4.5, -1); ctx.lineTo(-5.4, -6.4); ctx.lineTo(5.4, -6.4); ctx.lineTo(4.5, -1); ctx.quadraticCurveTo(4.3, 0, 3.4, 0); ctx.closePath(); };
      const rim = () => E.rr(ctx, -6.3, -8.2, 12.6, 2.6, 1.3);
      ctx.save();
      ctx.translate(4.5 * w, -bounce); ctx.rotate(w * 1.45); ctx.translate(-4.5 * w, 0);
      ctx.scale(1 + o.sq * 0.22 - breathe, 1 - o.sq * 0.22 + breathe);
      ctx.save();
      ctx.translate(0, -7.6); ctx.rotate(o.sway * 0.06);
      const R = -0.75 + 1.5 * w, L = -Math.PI + 0.75 - 1.5 * w, green = w > 0.5 ? '#9fb24a' : '#4fc95e';
      if (U.kind === 'ACORN') { fatLeaf(ctx, L + 0.15, 7, 2.9, green); }
      else if (U.kind === 'DANDELION') { fatLeaf(ctx, L + 0.35, 7.4, 1.1, green); fatLeaf(ctx, L - 0.3, 5.4, 1, green); fatLeaf(ctx, R + 0.35, 7.4, 1.1, green); }
      else { fatLeaf(ctx, L, 6.2, 2, green); fatLeaf(ctx, R, 6.2, 2, green); }
      ctx.restore();
      if (U.pose === 'down') {
        ctx.strokeStyle = '#2a9a4a'; ctx.lineWidth = 2.8; ctx.lineCap = 'round';
        ctx.beginPath(); ctx.moveTo(0, -7.5); ctx.quadraticCurveTo(0.5, -13, 4.5, -12.5 + w * 1.5); ctx.stroke();
        ctx.save(); ctx.translate(4.5, -12.5 + w * 1.5); ctx.rotate(0.5); ctx.scale(0.8, 0.8); muzzle(ctx, U.kind, U.color, 1.5); ctx.restore();
      }
      ctx.fillStyle = U.color; pot(); ctx.fill();
      ctx.save(); pot(); ctx.clip();
      ctx.fillStyle = 'rgba(0,0,0,.18)'; ctx.fillRect(-6, -1.9, 12, 2); ctx.fillRect(2.6, -7, 4, 7);
      ctx.fillStyle = 'rgba(255,255,255,.5)'; ell(ctx, -3.7, -3.6, 0.5, 2, 0.14);
      ctx.restore();
      ctx.fillStyle = U.kind === 'ACORN' ? '#8a5630' : U.color; rim(); ctx.fill();
      ctx.fillStyle = U.kind === 'ACORN' ? 'rgba(255,255,255,.16)' : 'rgba(255,255,255,.3)'; rim(); ctx.fill();
      ctx.fillStyle = 'rgba(255,255,255,.6)'; E.rr(ctx, -5.2, -7.8, 4.4, 0.8, 0.4); ctx.fill();
      if (U.kind === 'ACORN') { ctx.fillStyle = 'rgba(0,0,0,.2)'; for (let i = 0; i < 5; i++) E.disc(ctx, -4.4 + i * 2.2, -6.5, 0.45); }
      if (U.kind === 'DANDELION') { ctx.fillStyle = '#fff'; for (let i = 0; i < 4; i++) E.disc(ctx, -3.6 + i * 2.4, -6.6, 0.5); }
      face(ctx, U);
      if (o.flash) { ctx.fillStyle = '#fff'; al(ctx, o.flash * 0.85, () => { pot(); ctx.fill(); rim(); ctx.fill(); }); }
      ctx.restore();
    },
    turn(ctx, U) {
      const y = U.top - 3 - Math.abs(Math.sin(U.time / 220)) * 3;
      const path = () => { ctx.beginPath(); ctx.moveTo(0, y + 5); ctx.lineTo(-4, y); ctx.lineTo(-1.6, y); ctx.lineTo(-1.6, y - 3.4); ctx.lineTo(1.6, y - 3.4); ctx.lineTo(1.6, y); ctx.lineTo(4, y); ctx.closePath(); };
      ctx.lineJoin = 'round'; ctx.strokeStyle = '#fff'; ctx.lineWidth = 2.2; path(); ctx.stroke();
      ctx.fillStyle = U.color; path(); ctx.fill();
      ctx.strokeStyle = U.color; ctx.lineWidth = 0.8; path(); ctx.stroke();
    },
    trail(ctx, pts) {
      const n = pts.length / 2;
      ctx.save();
      for (let i = 0; i < n; i++) {
        const k = i / n, r = 0.5 + 1.1 * k;
        ctx.globalAlpha = 0.35 + 0.65 * k;
        ctx.fillStyle = NAVY; E.disc(ctx, pts[i * 2], pts[i * 2 + 1], r + 0.35);
        ctx.fillStyle = '#fff'; E.disc(ctx, pts[i * 2], pts[i * 2 + 1], r);
      }
      ctx.restore();
    },
    seed(ctx, x, y, shell, rot) {
      ctx.save(); ctx.translate(x, y);
      ctx.fillStyle = NAVY;
      if (shell === 'PUMPKIN') {
        E.disc(ctx, 0, 0, 3.9); ctx.rotate(rot * 0.6);
        ball(ctx, 0, 0, 3.4, '#ff8a2a');
        ctx.strokeStyle = 'rgba(160,70,0,.5)'; ctx.lineWidth = 0.5; ctx.beginPath(); ctx.ellipse(0, 0, 1.3, 3.2, 0, 0, 7); ctx.stroke();
        ctx.fillStyle = '#3fb857'; E.rr(ctx, -0.7, -4.4, 1.4, 1.8, 0.5); ctx.fill();
      } else if (shell === 'VINE') {
        E.disc(ctx, 0, 0, 2.9); ctx.rotate(rot);
        ball(ctx, 0, 0, 2.4, '#7ddc4a');
        ctx.strokeStyle = '#2a9a4a'; ctx.lineWidth = 0.7; ctx.lineCap = 'round'; ctx.beginPath(); ctx.arc(0.2, 0.2, 1.1, 0, Math.PI * 1.5); ctx.stroke();
      } else if (shell === 'CHESTNUT') {
        ctx.rotate(rot * 0.5);
        ctx.fillStyle = '#5a3418'; star(ctx, 4.8, 9, 0.68, 0); ctx.fill();
        ball(ctx, 0, 0, 3.2, '#9a5a2c');
        ctx.fillStyle = '#f1cf9a'; ell(ctx, 0, 1.7, 2, 0.9);
      } else if (shell === 'SPORE') {
        ctx.rotate(rot);
        ctx.fillStyle = NAVY; E.disc(ctx, 0, 0, 2.5);
        ctx.fillStyle = '#fff'; for (let i = 0; i < 6; i++) E.disc(ctx, Math.cos(i * 1.047) * 1.3, Math.sin(i * 1.047) * 1.3, 0.9);
        ctx.fillStyle = '#ffd23a'; E.disc(ctx, 0, 0, 0.8);
      } else {
        E.disc(ctx, 0, 0, 2.9); ctx.rotate(rot);
        ball(ctx, 0, 0, 2.4, '#b6e04a');
      }
      ctx.restore();
    },
    boom(ctx, e) {
      const grow = E.back(Math.min(1, e.t / 0.45)), shrink = e.t > 0.6 ? 1 - E.ease((e.t - 0.6) / 0.4) : 1, s = grow * shrink;
      ctx.save(); ctx.translate(e.x, e.y);
      ctx.strokeStyle = '#fff'; ctx.lineWidth = Math.max(0.01, 2.4 * (1 - e.t)); ctx.beginPath(); ctx.arc(0, 0, e.r * (0.3 + 1.1 * E.ease(e.t)), 0, 7); ctx.stroke();
      if (e.fill) {
        ctx.scale(s, s);
        ctx.fillStyle = '#2a9a4a'; for (let i = 0; i < 9; i++) E.disc(ctx, Math.cos(i * 0.7) * e.r * 0.62, Math.sin(i * 0.7) * e.r * 0.62, e.r * 0.36);
        ball(ctx, 0, 0, e.r * 0.72, '#5fd15a');
        ctx.translate(0, -e.r * 0.7); fatLeaf(ctx, -Math.PI / 2 - 0.6, e.r * 0.6, e.r * 0.2, '#7ddc4a'); fatLeaf(ctx, -Math.PI / 2 + 0.6, e.r * 0.6, e.r * 0.2, '#7ddc4a');
      } else {
        ctx.rotate(e.t * 0.5);
        ctx.fillStyle = '#ff6a2a'; star(ctx, e.r * 1.05 * s, 12, 0.66, 0); ctx.fill();
        ctx.fillStyle = '#ffd23a'; star(ctx, e.r * 0.8 * s, 12, 0.62, 0.26); ctx.fill();
        ctx.fillStyle = '#fff'; star(ctx, e.r * 0.42 * s, 8, 0.6, 0); ctx.fill();
      }
      ctx.restore();
    },
    particle(ctx, q, k) {
      ctx.save(); ctx.translate(q.x, q.y); ctx.rotate(q.rot);
      const s = q.size * Math.min(1, k * 3);
      if (q.kind === 'ring') { ctx.globalAlpha = k; ctx.strokeStyle = '#fff'; ctx.lineWidth = 0.9; ctx.beginPath(); ctx.arc(0, 0, q.size * (2 - k), 0, 7); ctx.stroke(); }
      else if (q.kind === 'star') { ctx.fillStyle = q.color; star(ctx, s, 4, 0.42, 0); ctx.fill(); }
      else { ctx.fillStyle = q.color; E.rr(ctx, -s, -s, s * 2, s * 2, s * 0.6); ctx.fill(); ctx.fillStyle = 'rgba(255,255,255,.4)'; E.disc(ctx, -s * 0.3, -s * 0.3, s * 0.3); }
      ctx.restore();
    },
    fx: {
      muzzle(x, y, ang, color) {
        const r = (ang * Math.PI) / 180, c = Math.cos(r), s = -Math.sin(r);
        E.emit('ring', x, y, { vx: c * 0.5, vy: s * 0.5, life: 16, size: 2.2 }); E.emit('ring', x, y, { vx: c * 1.3, vy: s * 1.3, life: 22, size: 1.5 });
        for (let i = 0; i < 6; i++) E.emit('star', x, y, { vx: c * 2 + Math.random() * 2 - 1, vy: s * 2 + Math.random() * 2 - 1, g: 0.05, life: 28, size: 1.5, color: i % 2 ? '#ffd23a' : color, vr: 0.3 });
      },
      impact(x, y, r, fill) {
        const cols = fill ? ['#5fd15a', '#2a9a4a', '#b6e04a'] : ['#b0683c', '#884c2c', '#74d44a'];
        for (let i = 0; i < Math.round(r * 0.9); i++) {
          const a = -Math.PI * (0.12 + 0.76 * Math.random()), v = 1.4 + Math.random() * 2.6;
          E.emit('clod', x + (Math.random() - 0.5) * r, y, { vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 0.11, life: 46 + Math.random() * 24, size: 0.8 + Math.random() * 1.2, color: cols[i % 3], vr: (Math.random() - 0.5) * 0.5 });
        }
        for (let i = 0; i < 9; i++) E.emit('star', x, y, { vx: (Math.random() - 0.5) * 5, vy: -1.5 - Math.random() * 3, g: 0.09, life: 50, size: 1.7, color: ['#ffd23a', '#ff5a3c', '#3f7fd9', '#fff'][i % 4], vr: 0.35 });
      },
      split(x, y) { E.emit('ring', x, y, { life: 18, size: 2.5 }); for (let i = 0; i < 5; i++) E.emit('star', x, y, { vx: Math.random() * 3 - 1.5, vy: Math.random() * 3 - 1.5, life: 22, size: 1.2, color: '#fff', vr: 0.3 }); },
    },
    label(ctx, l) {
      const fs = l.fs, top = l.footY - THEME.lift * l.unitScale - fs * 0.6;
      ctx.font = `700 ${fs * 0.92}px ${FONT}`;
      const tw = ctx.measureText(l.text).width, pw = tw + fs * 1.5, ph = fs * 1.25, px = l.x - pw / 2, py = top - fs * 0.95;
      ctx.fillStyle = NAVY; E.rr(ctx, px - 0.5, py - 0.5, pw + 1, ph + 1.6, ph / 2); ctx.fill();
      ctx.fillStyle = '#fff'; E.rr(ctx, px, py, pw, ph, ph / 2); ctx.fill();
      ctx.fillStyle = l.color; E.disc(ctx, px + fs * 0.5, py + ph / 2, fs * 0.26);
      ctx.fillStyle = NAVY; ctx.fillText(l.text, l.x + fs * 0.3, top);
      const bw = fs * 2.8, bh = fs * 0.5, bx = l.x - bw / 2, by = top + fs * 0.45, ratio = Math.max(0, Math.min(1, l.hp / l.maxHp));
      ctx.fillStyle = NAVY; E.rr(ctx, bx - 0.6, by - 0.6, bw + 1.2, bh + 1.2, bh); ctx.fill();
      ctx.fillStyle = '#5a6788'; E.rr(ctx, bx, by, bw, bh, bh / 2); ctx.fill();
      if (ratio > 0.02) {
        ctx.fillStyle = E.hpColor(ratio, HP); E.rr(ctx, bx, by, Math.max(bh, bw * ratio), bh, bh / 2); ctx.fill();
        ctx.fillStyle = 'rgba(255,255,255,.45)'; E.rr(ctx, bx + bh * 0.3, by + bh * 0.15, Math.max(0.1, bw * ratio - bh * 0.6), bh * 0.28, bh * 0.14); ctx.fill();
      }
    },
    dmg(ctx, x, y, amt, t, fs) {
      ctx.save();
      const s = E.S.rm ? 1 : E.back(Math.min(1, t / 0.25));
      ctx.globalAlpha = t < 0.75 ? 1 : Math.max(0, 1 - (t - 0.75) / 0.25);
      ctx.translate(x, y - E.ease(t) * 6); ctx.scale(s, s);
      ctx.font = `700 ${fs * 1.25}px ${FONT}`; ctx.textAlign = 'center'; ctx.lineJoin = 'round';
      ctx.lineWidth = fs * 0.5; ctx.strokeStyle = NAVY; ctx.strokeText('-' + amt, 0, 0);
      ctx.lineWidth = fs * 0.28; ctx.strokeStyle = '#fff'; ctx.strokeText('-' + amt, 0, 0);
      ctx.fillStyle = '#ff4a2c'; ctx.fillText('-' + amt, 0, 0);
      ctx.restore();
    },
    wind(ctx, w, p, t) {
      const dir = Math.sign(w), n = Math.ceil(Math.abs(w) / 2.5);
      ctx.save();
      ctx.fillStyle = NAVY; E.rr(ctx, -27, 0.5, 54, 13, 6.5); ctx.fill();
      ctx.fillStyle = '#fff'; E.rr(ctx, -26, 1.5, 52, 11, 5.5); ctx.fill();
      ctx.save(); ctx.translate(0, 7); ctx.rotate((t * w * 0.0016) % (Math.PI * 2));
      const cols = ['#ff5a3c', '#ffd23a', '#3f7fd9', '#4fd45a'];
      for (let i = 0; i < 4; i++) { ctx.rotate(Math.PI / 2); ctx.fillStyle = cols[i]; ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(4.6, 0); ctx.quadraticCurveTo(4.6, -3.4, 0.6, -3.2); ctx.closePath(); ctx.fill(); }
      ctx.fillStyle = NAVY; E.disc(ctx, 0, 0, 0.9);
      ctx.restore();
      ctx.fillStyle = '#ff5a3c';
      for (let i = 0; i < n; i++) { const x = dir * (8.5 + i * 4.2); ctx.beginPath(); ctx.moveTo(x, 4); ctx.lineTo(x + dir * 3, 7); ctx.lineTo(x, 10); ctx.lineTo(x + dir * 0.9, 7); ctx.closePath(); ctx.fill(); }
      ctx.fillStyle = NAVY; ctx.font = `700 7.5px ${FONT}`; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
      ctx.fillText(String(Math.abs(w)), -(dir || 1) * 15, 9.7);
      ctx.restore();
    },
  };
})();
