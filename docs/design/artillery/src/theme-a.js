// @name 시안 A — 수채 그림책 정원
// @lede 따뜻한 종이 위에 번진 물감과 세피아 잉크 선. 칠이 선에서 살짝 어긋나고, 흙 가장자리에 물감이 고인다. 폭발은 물감 얼룩과 꽃잎, 바람은 깃발 리본과 민들레 홀씨 — 조용하고 다정한 분위기.
/*CSS
:root{--bg:#f6efe0;--fg:#3f332d;--muted:#7d6c5e;--card:#fffaf0;--line:#d9cbb4;--accent:#b9573c;--btnfg:#fffaf0;--radius:14px 18px 12px 20px;--font:"Iowan Old Style","Palatino Linotype",Palatino,"Nanum Myeongjo","AppleMyungjo",Georgia,serif;--hfont:var(--font)}
[data-mode=dark]{--bg:#262338;--fg:#f1e9dc;--muted:#aaa2b8;--card:#312d47;--line:#4a4563;--accent:#eb9a7c;--btnfg:#262338}
CSS*/
(function () {
  'use strict';
  const SERIF = '"Iowan Old Style","Palatino Linotype",Palatino,"Nanum Myeongjo","AppleMyungjo",Georgia,serif';
  const HP = ['#7fb46a', '#e2b458', '#d9705e'];
  const P = () => THEME.pal[E.S.mode];
  const al = (ctx, a, fn) => { ctx.save(); ctx.globalAlpha *= a; fn(); ctx.restore(); };
  const line = (ctx, x1, y1, x2, y2) => { ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke(); };
  /** 수채 칠 — 칠은 선에서 살짝 어긋나게, 그 위에 잉크 선. */
  function wash(ctx, path, fill, a) {
    ctx.save(); ctx.translate(0.35, 0.25); ctx.globalAlpha *= a === undefined ? 0.82 : a; ctx.fillStyle = fill; path(); ctx.fill(); ctx.restore();
    ctx.strokeStyle = P().ink; ctx.lineWidth = 0.45; ctx.lineJoin = ctx.lineCap = 'round'; path(); ctx.stroke();
  }
  function leaf(ctx, ang, len, wid, fill) {
    ctx.save(); ctx.rotate(ang);
    wash(ctx, () => { ctx.beginPath(); ctx.moveTo(0, 0); ctx.quadraticCurveTo(len * 0.45, -wid, len, 0); ctx.quadraticCurveTo(len * 0.45, wid, 0, 0); }, fill, 0.85);
    ctx.lineWidth = 0.3; line(ctx, 0.5, 0, len * 0.8, 0);
    ctx.restore();
  }
  function blob(ctx, x, y, rx, ry, c) {
    ctx.save(); ctx.translate(x, y); ctx.scale(1, ry / rx);
    const g = ctx.createRadialGradient(0, 0, 0, 0, 0, rx);
    g.addColorStop(0, `rgba(${c[0]},${c[1]},${c[2]},${c[3]})`); g.addColorStop(1, `rgba(${c[0]},${c[1]},${c[2]},0)`);
    ctx.fillStyle = g; ctx.fillRect(-rx, -rx, rx * 2, rx * 2); ctx.restore();
  }
  let grain = null;
  function grainPattern(ctx) {
    if (!grain) {
      grain = document.createElement('canvas'); grain.width = grain.height = 64;
      const g = grain.getContext('2d'), img = g.createImageData(64, 64);
      for (let i = 0; i < 4096; i++) { const v = E.h(i, 91); img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = v > 0.5 ? 255 : 60; img.data[i * 4 + 3] = Math.abs(v - 0.5) * 16; }
      g.putImageData(img, 0, 0);
    }
    return ctx.createPattern(grain, 'repeat');
  }
  function muzzle(ctx, kind, color, x) {
    if (kind === 'ACORN') {
      wash(ctx, () => { ctx.beginPath(); ctx.ellipse(x + 0.9, 0, 2.3, 1.9, 0, 0, 7); }, '#d9a868');
      wash(ctx, () => { ctx.beginPath(); ctx.arc(x - 0.1, 0, 2.3, Math.PI / 2, Math.PI * 1.5); ctx.closePath(); }, '#8a5a36', 0.9);
    } else if (kind === 'DANDELION') {
      ctx.strokeStyle = P().ink; ctx.lineWidth = 0.3;
      al(ctx, 0.6, () => { for (let i = 0; i < 10; i++) line(ctx, x + 0.6, 0, x + 0.6 + Math.cos(i * 0.63) * 3.3, Math.sin(i * 0.63) * 3.3); });
      wash(ctx, () => { ctx.beginPath(); ctx.arc(x + 0.6, 0, 2.4, 0, 7); }, '#fffdf6', 0.85);
      ctx.fillStyle = color; E.disc(ctx, x + 0.6, 0, 0.8);
    } else {
      ctx.save(); ctx.translate(x - 1.6, 0); leaf(ctx, -2.3, 2.3, 0.8, '#7fb46a'); leaf(ctx, 2.3, 2.3, 0.8, '#7fb46a'); ctx.restore();
      wash(ctx, () => { ctx.beginPath(); ctx.moveTo(x - 1.8, 0); ctx.quadraticCurveTo(x - 0.4, -2.5, x + 2.7, 0); ctx.quadraticCurveTo(x - 0.4, 2.5, x - 1.8, 0); }, color, 0.8);
    }
  }
  function face(ctx, U) {
    const e = U.o.eyes, ink = P().ink, fx = e === 'focus' ? 0.5 : 0;
    ctx.fillStyle = ink; ctx.strokeStyle = ink; ctx.lineWidth = 0.4; ctx.lineCap = 'round';
    for (const sx of [-1.7, 1.9]) {
      const x = sx + fx, y = -3.4;
      if (e === 'open' || e === 'focus') E.disc(ctx, x, y, e === 'focus' ? 0.64 : 0.55);
      else if (e === 'shut') { ctx.beginPath(); ctx.arc(x, y + 0.2, 0.6, Math.PI * 1.1, Math.PI * 1.9); ctx.stroke(); }
      else { line(ctx, x - 0.5, y - 0.5, x + 0.5, y + 0.5); line(ctx, x + 0.5, y - 0.5, x - 0.5, y + 0.5); }
    }
    ctx.fillStyle = 'rgba(232,120,120,.38)';
    for (const sx of [-3.2, 3.4]) { ctx.beginPath(); ctx.ellipse(sx, -2.3, 0.9, 0.55, 0, 0, 7); ctx.fill(); }
    ctx.beginPath();
    if (U.pose === 'hit' || U.pose === 'down') ctx.arc(0.1 + fx, -1.9, 0.45, 0, 7); else ctx.arc(0.1 + fx, -2.5, 0.7, Math.PI * 0.15, Math.PI * 0.85);
    ctx.stroke();
  }

  window.THEME = {
    id: 'a', lift: 16, smooth: true, shake: 2.2,
    pal: {
      light: { skyTop: '#b9dcec', skyBot: '#fbf2dc', cloud: '#fffdf6', cloudEdge: '#bcd0d8', label: '#3f332d', ink: '#4a3b34', paper: '#fbf2dc', sun: '#f7d98a', far: '#9fb9c9', stem: '#6fa85e', wash1: [244, 170, 160, 0.22], wash2: [250, 220, 140, 0.26], grass: [143, 191, 122], grassD: [104, 158, 104], soil: [219, 166, 128], soilD: [176, 121, 92] },
      dark: { skyTop: '#27305a', skyBot: '#7a6690', cloud: '#e4def0', cloudEdge: '#8f88ad', label: '#f6eee0', ink: '#2a2233', paper: '#3a3555', sun: '#f3ecd0', far: '#5a5a8c', stem: '#5d9470', wash1: [150, 120, 200, 0.25], wash2: [240, 160, 150, 0.18], grass: [102, 150, 120], grassD: [70, 112, 100], soil: [168, 124, 118], soilD: [118, 84, 92] },
    },
    swatch: [['skyTop', '하늘'], ['skyBot', '종이'], ['grass', '풀'], ['grassD', '풀 그늘'], ['soil', '겉흙'], ['soilD', '속흙'], ['ink', '잉크 선'], ['cloud', '구름'], ['label', '글자']],
    notes: [
      '<b>대기</b> — 잎이 천천히 흔들리고 가끔 눈을 깜빡인다(1.04초 주기, 좌석마다 어긋남).',
      '<b>조준</b> — 눈동자가 포신 쪽으로 쏠리고 포신이 ±1.4° 떨린다. 차례 주인 뒤에 노란 물감 번짐.',
      '<b>발사 반동</b>(200ms) — 포신이 2칸 물러났다 돌아오고 화분이 살짝 눌린다. 포구에서 종이빛 연기 5개 + 좌석 색 꽃잎.',
      '<b>탄 궤적</b> — 잉크 점선(오래된 점일수록 흐림) + 옅은 종이빛 붓 자국. 씨앗은 돌면서 난다.',
      '<b>착탄</b>(0.6초) — 물감 얼룩 10개가 번지고 잉크 고리가 퍼진다. 흙 물방울이 튀고 꽃잎이 바람을 타고 떨어진다. 화면 흔들림은 약하게(2칸).',
      '<b>둔덕</b> — 초록 얼룩이 번지고 꼭대기에 잎 두 장이 돋는다.',
      '<b>피격</b>(400ms) — 화분이 종이빛으로 두 번 번쩍, 좌우로 떨고, 머리 위 잉크 놀람 표시. 피해 숫자는 물감 얼룩 위에서 떠오른다.',
      '<b>쓰러짐</b>(0.7초) — 잎이 시들어 누렇게 처지고 줄기가 고개를 숙인다. 화분이 앞으로 기운 뒤 흐려진다.',
      '<b>바람</b> — 위쪽 깃대의 리본 길이·방향·펄럭임 + 숫자. 하늘에 민들레 홀씨가 바람 세기만큼 흘러간다.',
      '<b>연출 줄이기</b> — 흔들림·입자·깜빡임·대기 움직임 끔, 번쩍임은 한 번의 옅은 덧칠로.',
    ],
    soil(x, y, d, e, under, p) {
      const n = E.vn(x, y, 22) * 0.6 + E.vn(x + 40, y + 11, 7) * 0.4;
      const gT = 3 + Math.round(E.vn(x, 7, 9) * 3);
      const c = d <= gT ? E.mix(p.grass, p.grassD, (d - 1) / gT) : E.mix(p.soil, p.soilD, Math.min(1, (d - gT) / 40) * 0.7 + n * 0.3);
      let k = (n - 0.5) * 26;
      if (e === 1) k -= 38; else if (e === 2) k -= 14;
      if (E.h(x, y) > 0.93) k += 14;
      return [c[0] + k, c[1] + k, c[2] + k];
    },
    above(x, y, dist, p) {
      const v = E.h(x, 3);
      return dist === 1 && v > 0.62 ? [p.grassD[0], p.grassD[1], p.grassD[2], 210] : dist === 2 && v > 0.9 ? [p.grassD[0], p.grassD[1], p.grassD[2], 150] : null;
    },
    sky(ctx, p, t, w) {
      const g = ctx.createLinearGradient(0, 0, 0, 360);
      g.addColorStop(0, p.skyTop); g.addColorStop(0.75, p.skyBot);
      ctx.fillStyle = g; ctx.fillRect(0, 0, 640, 360);
      blob(ctx, 150 + Math.sin(t / 9000) * 12, 95, 170, 80, p.wash1); blob(ctx, 470, 70, 200, 90, p.wash2); blob(ctx, 330, 255, 260, 70, p.wash1);
      ctx.fillStyle = p.sun; al(ctx, 0.5, () => E.disc(ctx, 528, 64, 24)); al(ctx, 0.22, () => E.disc(ctx, 528, 64, 33));
      ctx.fillStyle = p.far;
      for (const [x, y, rw] of [[70, 120, 46], [300, 84, 30], [606, 150, 52]]) {
        const dx = Math.sin(t / 7000 + x) * 3;
        al(ctx, 0.45, () => { ctx.beginPath(); ctx.moveTo(x - rw + dx, y); ctx.quadraticCurveTo(x + dx, y - rw * 0.25, x + rw + dx, y); ctx.quadraticCurveTo(x + dx, y + rw * 0.9, x - rw + dx, y); ctx.fill(); });
      }
      ctx.fillStyle = p.cloud;
      for (let i = 0; i < 5; i++) {
        const x = (((i * 151 + 40 + t * 0.004 * (1 + w * 0.25)) % 760) + 760) % 760 - 60, y = 36 + ((i * 27) % 110);
        al(ctx, 0.6, () => { E.disc(ctx, x, y, 11); E.disc(ctx, x + 12, y + 2, 9); E.disc(ctx, x - 11, y + 3, 8); });
      }
      ctx.strokeStyle = p.label; ctx.lineWidth = 0.3;
      al(ctx, 0.45, () => {
        for (let i = 0; i < 8; i++) {
          const x = (((i * 83 + t * 0.012 * w) % 660) + 660) % 660 - 10, y = 50 + ((i * 47) % 150) + Math.sin(t / 800 + i * 2) * 5;
          ctx.beginPath(); ctx.moveTo(x, y + 2.4); ctx.lineTo(x, y);
          for (let k = -2; k <= 2; k++) { ctx.moveTo(x, y); ctx.lineTo(x + k * 0.9, y - 1.7 + Math.abs(k) * 0.3); }
          ctx.stroke();
        }
      });
      ctx.fillStyle = grainPattern(ctx); ctx.fillRect(0, 0, 640, 360);
    },
    clouds(ctx, level, p, t) {
      const path = (dy, ph, sp) => { ctx.beginPath(); ctx.moveTo(0, 362); for (let x = 0; x <= 640; x += 4) ctx.lineTo(x, level + dy - Math.abs(Math.sin(x / 17 + ph + t / sp / 6)) * 5 - Math.sin(x / 53 + t / sp) * 2); ctx.lineTo(640, 362); ctx.closePath(); };
      ctx.fillStyle = p.cloudEdge; al(ctx, 0.55, () => { path(-7, 0, 1400); ctx.fill(); });
      ctx.fillStyle = p.cloud; al(ctx, 0.95, () => { path(-3, 2, 900); ctx.fill(); });
      ctx.strokeStyle = p.ink; ctx.lineWidth = 0.5; al(ctx, 0.3, () => { path(-3, 2, 900); ctx.stroke(); });
    },
    under(ctx, U, isTurn) {
      if (isTurn) blob(ctx, 0, -6, 17, 15, [250, 214, 120, 0.6]);
    },
    barrel(ctx, U) {
      const p = P();
      ctx.lineCap = 'round';
      ctx.strokeStyle = p.ink; ctx.lineWidth = 2.6; line(ctx, 0, 0, 9.2, 0);
      ctx.strokeStyle = p.stem; ctx.lineWidth = 1.8; line(ctx, 0, 0, 9.2, 0);
      ctx.strokeStyle = 'rgba(255,255,255,.35)'; ctx.lineWidth = 0.5; line(ctx, 1, -0.45, 8, -0.45);
      muzzle(ctx, U.kind, U.color, 10);
    },
    body(ctx, U) {
      const o = U.o, p = P(), w = o.wilt;
      const green = E.css(E.mix([127, 180, 106], [186, 160, 94], w));
      const pot = () => { ctx.beginPath(); ctx.moveTo(-3.9, 0); ctx.quadraticCurveTo(0, 0.5, 3.9, 0); ctx.lineTo(5.2, -6); ctx.lineTo(-5.2, -6); ctx.closePath(); };
      const rim = () => E.rr(ctx, -6, -7.7, 12, 2, 0.9);
      ctx.save();
      ctx.rotate(w * 0.3);
      ctx.scale(1 + o.sq * 0.08, 1 - o.sq * 0.1);
      ctx.save();
      ctx.translate(0, -7.2 - o.bob * 0.35 * (1 - w)); ctx.rotate(o.sway * 0.05);
      const R = -0.7 + 1.3 * w, L = -Math.PI + 0.7 - 1.3 * w;
      if (U.kind === 'ACORN') { leaf(ctx, L + 0.2, 6.6, 3.4, green); leaf(ctx, R - 0.2, 4, 1.8, green); }
      else if (U.kind === 'DANDELION') { leaf(ctx, L + 0.3, 7, 1.1, green); leaf(ctx, L - 0.35, 5, 1, green); leaf(ctx, R + 0.3, 7, 1.1, green); }
      else { leaf(ctx, L, 6, 2.2, green); leaf(ctx, R, 6, 2.2, green); }
      ctx.restore();
      if (U.pose === 'down') {
        const ex = 4 + 3 * w, ey = -15 + 7 * w;
        ctx.strokeStyle = p.ink; ctx.lineWidth = 2.2; ctx.lineCap = 'round';
        const stem = () => { ctx.beginPath(); ctx.moveTo(0, -7); ctx.quadraticCurveTo(1.5, -13, ex, ey); ctx.stroke(); };
        stem(); ctx.strokeStyle = green; ctx.lineWidth = 1.4; stem();
        ctx.save(); ctx.translate(ex, ey); ctx.rotate(0.9 * w); ctx.scale(0.8, 0.8); muzzle(ctx, U.kind, U.color, 1.5); ctx.restore();
      }
      ctx.fillStyle = '#fff8ea'; pot(); ctx.fill();
      wash(ctx, pot, U.color, 0.72);
      ctx.fillStyle = p.ink; al(ctx, 0.14, () => { ctx.beginPath(); ctx.moveTo(1.5, 0.2); ctx.lineTo(3.9, 0); ctx.lineTo(5.2, -6); ctx.lineTo(3, -6); ctx.closePath(); ctx.fill(); });
      ctx.fillStyle = '#fff8ea'; rim(); ctx.fill();
      wash(ctx, rim, U.color, 0.86);
      ctx.strokeStyle = 'rgba(255,255,255,.5)'; ctx.lineWidth = 0.45; line(ctx, -4.6, -7, -1.5, -7);
      face(ctx, U);
      if (o.flash) {
        ctx.fillStyle = '#fffaf0'; al(ctx, o.flash * 0.75, () => { pot(); ctx.fill(); rim(); ctx.fill(); });
        ctx.strokeStyle = '#c4523c'; ctx.lineWidth = 0.5;
        for (const a of [-2.2, -1.57, -0.94]) line(ctx, Math.cos(a) * 9, -4 + Math.sin(a) * 9, Math.cos(a) * 12, -4 + Math.sin(a) * 12);
      }
      ctx.restore();
    },
    turn(ctx, U) {
      const y = U.top - 1 + Math.sin(U.time / 260) * 1;
      wash(ctx, () => { ctx.beginPath(); ctx.moveTo(0, y + 3.6); ctx.quadraticCurveTo(-3.4, y + 0.4, -2.4, y - 1); ctx.quadraticCurveTo(0, y - 2, 2.4, y - 1); ctx.quadraticCurveTo(3.4, y + 0.4, 0, y + 3.6); }, U.color, 0.85);
    },
    trail(ctx, pts, p) {
      const n = pts.length / 2;
      if (n < 2) return;
      ctx.save(); ctx.lineCap = ctx.lineJoin = 'round';
      ctx.strokeStyle = p.paper; ctx.globalAlpha = 0.4; ctx.lineWidth = 1.8;
      ctx.beginPath(); ctx.moveTo(pts[0], pts[1]); for (let i = 1; i < n; i++) ctx.lineTo(pts[i * 2], pts[i * 2 + 1]); ctx.stroke();
      ctx.fillStyle = p.label;
      for (let i = 0; i < n; i++) { ctx.globalAlpha = 0.14 + 0.5 * (i / n); E.disc(ctx, pts[i * 2], pts[i * 2 + 1], 0.45 + 0.3 * (i / n)); }
      ctx.restore();
    },
    seed(ctx, x, y, shell, rot, p) {
      ctx.save(); ctx.translate(x, y); ctx.rotate(rot);
      if (shell === 'PUMPKIN') {
        wash(ctx, () => { ctx.beginPath(); ctx.ellipse(0, 0, 3.3, 2.7, 0, 0, 7); }, '#ee9a4a');
        ctx.lineWidth = 0.3; ctx.beginPath(); ctx.ellipse(0, 0, 1.3, 2.6, 0, 0, 7); ctx.stroke();
        ctx.fillStyle = '#6f9a4e'; ctx.fillRect(-0.5, -3.7, 1, 1.4);
      } else if (shell === 'VINE') {
        wash(ctx, () => { ctx.beginPath(); ctx.arc(0, 0, 2.3, 0, 7); }, '#5f9e58');
        ctx.strokeStyle = '#3d7a44'; ctx.lineWidth = 0.5; ctx.beginPath(); ctx.arc(2.2, -2, 1.5, Math.PI * 0.6, Math.PI * 2.1); ctx.stroke();
      } else if (shell === 'CHESTNUT') {
        ctx.strokeStyle = p.ink; ctx.lineWidth = 0.4;
        for (let i = 0; i < 9; i++) line(ctx, Math.cos(i * 0.7) * 3, Math.sin(i * 0.7) * 3, Math.cos(i * 0.7) * 4.4, Math.sin(i * 0.7) * 4.4);
        wash(ctx, () => { ctx.beginPath(); ctx.arc(0, 0, 3.3, 0, 7); }, '#8a5636', 0.9);
        ctx.fillStyle = '#e2c49a'; ctx.beginPath(); ctx.ellipse(0, 1.9, 2.2, 0.9, 0, 0, 7); ctx.fill();
      } else if (shell === 'SPORE') {
        ctx.strokeStyle = p.label; ctx.lineWidth = 0.3;
        for (let i = 0; i < 8; i++) line(ctx, 0, 0, Math.cos(i * 0.785) * 2.8, Math.sin(i * 0.785) * 2.8);
        wash(ctx, () => { ctx.beginPath(); ctx.arc(0, 0, 1.6, 0, 7); }, '#fffdf6', 0.9);
        ctx.fillStyle = '#8a6a3a'; E.disc(ctx, 0, 0, 0.6);
      } else {
        wash(ctx, () => { ctx.beginPath(); ctx.ellipse(0, 0, 2.6, 2.1, 0, 0, 7); }, '#a9c46a');
        ctx.fillStyle = 'rgba(255,255,255,.6)'; E.disc(ctx, -0.8, -0.7, 0.7);
      }
      ctx.restore();
    },
    boom(ctx, e, p) {
      const k = E.ease(e.t), a = Math.max(0, 1 - e.t);
      const cols = e.fill ? ['#9ccf85', '#6fae6a', '#cfe6a8'] : ['#f0c070', '#e58a6a', '#f7e3a6'];
      ctx.save(); ctx.translate(e.x, e.y);
      for (let i = 0; i < 10; i++) {
        const an = i * 2.39 + E.h(i, 7) * 1.2, dist = e.r * (0.15 + 0.6 * E.h(i, 3)) * k, rad = e.r * (0.28 + 0.3 * E.h(i, 5)) * (0.5 + 0.5 * k);
        ctx.globalAlpha = a * 0.5; ctx.fillStyle = cols[i % 3];
        E.disc(ctx, Math.cos(an) * dist, Math.sin(an) * dist * (e.fill ? 0.7 : 1), rad);
      }
      ctx.globalAlpha = a * 0.55; ctx.strokeStyle = p.ink; ctx.lineWidth = 0.6; ctx.beginPath(); ctx.arc(0, 0, e.r * (0.35 + 0.7 * k), 0, 7); ctx.stroke();
      ctx.fillStyle = p.ink;
      for (let i = 0; i < 12; i++) { const an = i * 0.52 + 0.3, d = e.r * (0.9 + 0.5 * E.h(i, 11)) * k; ctx.globalAlpha = a * 0.6; E.disc(ctx, Math.cos(an) * d, Math.sin(an) * d, 0.35 + 0.5 * E.h(i, 2)); }
      if (e.fill) {
        ctx.globalAlpha = Math.min(1, a * 2); ctx.translate(0, -e.r * 0.6 * k);
        leaf(ctx, -Math.PI / 2 - 0.6, e.r * 0.5 * k, e.r * 0.18, '#7fb46a'); leaf(ctx, -Math.PI / 2 + 0.6, e.r * 0.5 * k, e.r * 0.18, '#7fb46a');
      }
      ctx.restore();
    },
    particle(ctx, q, k, p) {
      ctx.save(); ctx.translate(q.x, q.y); ctx.rotate(q.rot);
      ctx.globalAlpha = Math.min(1, k * 1.6) * (q.kind === 'puff' ? 0.55 : 0.85); ctx.fillStyle = q.color;
      if (q.kind === 'petal') { ctx.beginPath(); ctx.ellipse(0, 0, q.size, q.size * 0.5, 0, 0, 7); ctx.fill(); }
      else if (q.kind === 'puff') E.disc(ctx, 0, 0, q.size * (1.6 - k));
      else { E.disc(ctx, 0, 0, q.size); ctx.globalAlpha *= 0.6; ctx.strokeStyle = p.ink; ctx.lineWidth = 0.25; ctx.stroke(); }
      ctx.restore();
    },
    fx: {
      muzzle(x, y, ang, color) {
        const r = (ang * Math.PI) / 180, c = Math.cos(r), s = -Math.sin(r);
        for (let i = 0; i < 5; i++) E.emit('puff', x + c * i, y + s * i, { vx: c * (0.5 + i * 0.25), vy: s * (0.5 + i * 0.25) - 0.1, life: 20 + i * 3, size: 1.2 + i * 0.3, color: '#fffaf0', drag: 0.92 });
        for (let i = 0; i < 4; i++) E.emit('petal', x, y, { vx: c * 1.5 + Math.random() - 0.5, vy: s * 1.5 + Math.random() - 0.5, g: 0.02, life: 50, size: 1.3, color, vr: 0.2, drag: 0.96, w: 1 });
      },
      impact(x, y, r, fill) {
        const n = Math.round(r * 1.2);
        for (let i = 0; i < n; i++) {
          const a = -Math.PI * (0.1 + 0.8 * Math.random()), v = 0.8 + Math.random() * 2.2;
          E.emit('drop', x + (Math.random() - 0.5) * r, y, { vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 0.07, life: 40 + Math.random() * 30, size: 0.6 + Math.random() * 1.1, color: fill ? '#7fb46a' : i % 3 ? '#c98f6c' : '#8fbf7a' });
        }
        for (let i = 0; i < 10; i++) E.emit('petal', x, y - 2, { vx: (Math.random() - 0.5) * 3, vy: -1 - Math.random() * 2, g: 0.015, life: 90, size: 1.4, color: i % 2 ? '#f6c1c1' : '#fffaf0', vr: (Math.random() - 0.5) * 0.4, drag: 0.95, w: 1 });
      },
      split(x, y) { for (let i = 0; i < 6; i++) E.emit('puff', x, y, { vx: (Math.random() - 0.5) * 1.5, vy: (Math.random() - 0.5) * 1.5, life: 22, size: 1, color: '#fffaf0', drag: 0.9 }); },
    },
    label(ctx, l, p) {
      const fs = l.fs, top = l.footY - THEME.lift * l.unitScale - fs * 0.6, bw = fs * 2.6, by = top + fs * 0.6;
      ctx.font = `600 ${fs}px ${SERIF}`;
      ctx.lineJoin = 'round'; ctx.strokeStyle = p.paper; ctx.lineWidth = fs * 0.3;
      al(ctx, 0.85, () => ctx.strokeText(l.text, l.x, top));
      ctx.fillStyle = p.label; ctx.fillText(l.text, l.x, top);
      const ratio = Math.max(0, Math.min(1, l.hp / l.maxHp));
      ctx.lineCap = 'round'; ctx.lineWidth = fs * 0.36;
      ctx.strokeStyle = p.paper; line(ctx, l.x - bw / 2, by, l.x + bw / 2, by);
      ctx.strokeStyle = p.label; al(ctx, 0.25, () => line(ctx, l.x - bw / 2, by, l.x + bw / 2, by));
      if (ratio > 0) { ctx.strokeStyle = E.hpColor(ratio, HP); line(ctx, l.x - bw / 2, by, l.x - bw / 2 + bw * ratio, by); }
      ctx.fillStyle = l.color; E.disc(ctx, l.x - bw / 2 - fs * 0.5, by, fs * 0.2);
    },
    dmg(ctx, x, y, amt, t, fs, p) {
      ctx.save();
      ctx.globalAlpha = t < 0.7 ? 1 : Math.max(0, 1 - (t - 0.7) / 0.3);
      const yy = y - E.ease(t) * 8;
      ctx.font = `700 ${fs}px ${SERIF}`; ctx.textAlign = 'center';
      ctx.fillStyle = 'rgba(229,138,106,.4)'; E.disc(ctx, x, yy - fs * 0.32, fs * 0.85);
      ctx.lineWidth = fs * 0.25; ctx.strokeStyle = p.paper; ctx.lineJoin = 'round'; ctx.strokeText('−' + amt, x, yy);
      ctx.fillStyle = E.S.mode === 'dark' ? '#ffb39c' : '#b3402b'; ctx.fillText('−' + amt, x, yy);
      ctx.restore();
    },
    wind(ctx, w, p, t) {
      const dir = Math.sign(w), len = Math.abs(w) * 4.2;
      ctx.save(); ctx.lineCap = ctx.lineJoin = 'round';
      ctx.strokeStyle = p.label; ctx.fillStyle = p.label; ctx.lineWidth = 0.7;
      line(ctx, 0, 1, 0, 14); E.disc(ctx, 0, 1, 0.9);
      if (w) {
        const yAt = (i) => 3 + Math.sin(i / 5 - t / 220) * 1.3 * (i / len);
        const path = () => {
          ctx.beginPath(); ctx.moveTo(0, 3);
          for (let i = 0; i <= len; i += 2) ctx.lineTo(dir * i, yAt(i));
          ctx.lineTo(dir * (len - 3), yAt(len) + 1.7);
          for (let i = len; i >= 0; i -= 2) ctx.lineTo(dir * i, yAt(i) + 3.4);
          ctx.closePath();
        };
        ctx.fillStyle = '#e58a6a'; al(ctx, 0.9, () => { path(); ctx.fill(); });
        ctx.lineWidth = 0.4; path(); ctx.stroke();
      }
      ctx.fillStyle = p.label; ctx.font = `700 7px ${SERIF}`; ctx.textAlign = 'center'; ctx.textBaseline = 'alphabetic';
      ctx.lineWidth = 2; ctx.strokeStyle = p.paper; al(ctx, 0.8, () => ctx.strokeText(String(Math.abs(w)), -(dir || 1) * 7, 12));
      ctx.fillText(String(Math.abs(w)), -(dir || 1) * 7, 12);
      ctx.restore();
    },
  };
})();
