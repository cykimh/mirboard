/* eslint-disable */
// D-94 — CSS 회귀 기준선 캡처. 브라우저 콘솔에 붙여넣고 `__digest()` 를 호출한다.
// 자동 테스트가 아니다: jsdom 은 실제 캐스케이드/미디어쿼리를 재현하지 못하므로
// 라이브 브라우저에서만 의미가 있다. 사용법은 README.md 참조.

const CSS_BASELINE_SELECTORS = [
  '.game-table-layout', '.game-table', '.game-table-header', '.header-status', '.header-controls',
  '.table-arena', '.seat', '.seat-s', '.seat-w', '.seat-e', '.seat-n', '.seat-team', '.seat-chips',
  '.seat-cardstack', '.seat-cardstack-fan', '.seat-cardback', '.seat-cardstack-count',
  '.table-center-trick', '.trick-meta', '.hand-type', '.trick-player',
  '.scoreboard', '.scoreboard-team', '.turn-countdown',
  '.arena-seat-actions', '.seat-action-group', '.seat-action-btn',
  '.seat-action-btn.play', '.seat-action-btn.pass',
  '.my-hand', '.hand-cards', '.card-chip', '.card-simple', '.cs-rank', '.cs-suit',
  '.action-bar', '.arena-pass', '.pass-slots', '.pass-slot', '.combo-hint',
  '.room-chat-panel', '.room-chat-list', '.room-chat-report', '.room-chat-row',
  '.match-ended', '.score-history', '.chip-standings', '.error', '.declared', '.status-tag',
];

const CSS_BASELINE_PROPS = [
  'display', 'position', 'width', 'height', 'minWidth', 'minHeight', 'maxWidth',
  'margin', 'padding', 'top', 'right', 'bottom', 'left', 'flexDirection', 'flexWrap',
  'justifyContent', 'alignItems', 'gap', 'transform', 'fontSize', 'fontWeight', 'lineHeight',
  'color', 'backgroundColor', 'borderRadius', 'borderWidth', 'borderColor', 'opacity',
  'zIndex', 'overflow', 'boxShadow', 'textAlign',
];

window.__cssSnap = function () {
  const out = {};
  for (const sel of CSS_BASELINE_SELECTORS) {
    const el = document.querySelector(sel);
    if (!el) {
      out[sel] = null;
      continue;
    }
    const cs = getComputedStyle(el);
    const rec = {};
    for (const p of CSS_BASELINE_PROPS) rec[p] = cs[p];
    const r = el.getBoundingClientRect();
    rec.__rect = [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)];
    out[sel] = rec;
  }
  return out;
};

window.__hash = function (s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0).toString(36);
};

/** 셀렉터 → 해시. 어긋난 셀렉터만 __cssSnap() 으로 전문 비교한다. */
window.__digest = function () {
  const snap = window.__cssSnap();
  const out = {};
  for (const [sel, rec] of Object.entries(snap)) {
    out[sel] = rec === null ? null : window.__hash(JSON.stringify(rec));
  }
  return out;
};
