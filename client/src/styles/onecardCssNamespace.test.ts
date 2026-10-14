import { describe, expect, it } from 'vitest';
import { indexCssSource, onecardCssSource } from './cssSources';

/**
 * `.oc-` 네임스페이스 규칙을 기계로 강제한다 (D-129, 스컬킹 `skullkingCssNamespace.test` 와 같은 규칙).
 *
 * 원카드 게임판 CSS 가 공용 클래스(`.card-chip`, `.my-hand` …)를 재정의하면 다른 게임판이 조용히 번진다.
 * 캐스케이드 순서(19 가 18 뒤·17 앞)와 "19 에 폭 미디어 0개"도 함께 고정한다.
 */

/** 주석과 @keyframes 블록 제거본 — 키프레임의 `from`/`to` 는 선택자가 아니다. */
const cssCode = onecardCssSource
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/@keyframes[^{]*\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}/g, '');

/** 선언 블록 앞의 선택자 그룹만 뽑는다. */
function selectorGroups(source: string): string[] {
  const groups: string[] = [];
  const re = /(^|})\s*([^{}@]+?)\s*\{/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(source)) !== null) {
    const sel = m[2].trim();
    if (sel) groups.push(sel);
  }
  return groups;
}

describe('19-onecard-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(40);
  });

  it('모든 선택자가 .oc- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.oc-')));
    expect(offenders, `.oc- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('공용 클래스를 단독으로 재정의하지 않는다', () => {
    const shared = [
      '.seat',
      '.card-chip',
      '.my-hand',
      '.action-bar',
      '.table-arena',
      '.hand-cards',
      '.match-end',
      '.status-tag',
    ];
    const offenders = groups.filter((g) =>
      g
        .split(',')
        .map((s) => s.trim())
        .some((one) => shared.includes(one)),
    );
    expect(offenders, `공용 클래스 단독 재정의: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('폭 미디어 쿼리가 0개다 (17 앞에 import 되므로 두면 덮인다)', () => {
    expect(cssCode).not.toMatch(/@media/);
  });
});

/** 선택자가 정확히 `selector` 인 첫 블록의 `--토큰: 값` 을 뽑는다. */
function tokensOf(selector: string): Map<string, string> {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`(^|})\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(cssCode);
  const tokens = new Map<string, string>();
  if (!m) return tokens;
  for (const [, name, value] of m[2].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    tokens.set(name, value.trim());
  }
  return tokens;
}

/**
 * 튜토리얼 다이얼로그는 body 포털이라 `.oc-table` 밖이다. 토큰이 안 풀리면 칩 글자색·조커 배경·연습 버튼 색이
 * 빠지는데 jsdom(css:false)은 이를 못 잡는다. 그래서 `.oc-tokens` 가 같은 값을 선언하는지 원문으로 고정한다.
 */
describe('19-onecard-table.css — .oc-tokens (튜토리얼 포털 토큰)', () => {
  const PORTAL_TOKENS = [
    '--oc-card-bg',
    '--oc-card-red',
    '--oc-card-black',
    '--oc-joker-black-bg',
    '--oc-joker-black-fg',
    '--oc-joker-color-bg',
    '--oc-joker-color-fg',
    '--oc-race-call',
    '--oc-race-catch',
  ];
  const table = tokensOf('.oc-table');
  const portal = tokensOf('.oc-tokens');

  it('칩과 연습 버튼이 쓰는 토큰을 모두 선언한다', () => {
    for (const t of PORTAL_TOKENS) expect(portal.get(t), t).toBeTruthy();
  });

  it('값이 게임판(.oc-table) 토큰과 같다', () => {
    expect(portal.size).toBeGreaterThan(0);
    for (const [name, value] of portal) {
      expect(value, name).toBe(table.get(name));
    }
  });

  it('레이아웃 속성은 갖지 않는다 — 토큰만 푸는 클래스다', () => {
    const block = /(^|})\s*\.oc-tokens\s*\{([^}]*)\}/.exec(cssCode)?.[2] ?? '';
    expect(block).not.toMatch(/(^|[;\s])(display|padding|min-height|gap)\s*:/);
  });
});

/** 쉼표 그룹 안에 `selector` 가 (정확히) 들어 있는 규칙의 본문들 — 공백은 접어서 비교한다. */
function bodiesOf(selector: string): string[] {
  const bodies: string[] = [];
  // 앞 규칙의 `}` 까지 본문으로 먹으므로 `(^|})` 앵커를 두지 않는다 — 주석을 걷어 낸 원문이라 규칙 사이엔 공백뿐이다.
  const re = /([^{}@]+?)\s*\{([^}]*)\}/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(cssCode)) !== null) {
    const group = m[1].split(',').map((one) => one.replace(/\s+/g, ' ').trim());
    if (group.includes(selector)) bodies.push(m[2]);
  }
  return bodies;
}

/** `selector` 규칙들 중 `prop` 을 정한 첫 값. 없으면 undefined. */
function declared(selector: string, prop: string): string | undefined {
  const decl = new RegExp(`(^|[;\\s])${prop}\\s*:\\s*([^;]+);`);
  for (const body of bodiesOf(selector)) {
    const m = decl.exec(body);
    if (m) return m[2].trim();
  }
  return undefined;
}

/**
 * 같은 특이도에선 뒤 규칙이 이긴다 — `selector` 를 포함한 규칙들 중 `prop` 의 **마지막** 값(D-132). `declared` 는 첫 값이라
 * `.oc-felt-left, .oc-felt-right { flex-direction: column }` 그룹 규칙을 읽어 `.oc-felt-left` 의 `column-reverse` 를 놓친다.
 */
function lastDeclared(selector: string, prop: string): string | undefined {
  const decl = new RegExp(`(^|[;\\s])${prop}\\s*:\\s*([^;]+);`);
  let last: string | undefined;
  for (const body of bodiesOf(selector)) {
    const m = decl.exec(body);
    if (m) last = m[2].replace(/\s+/g, ' ').trim();
  }
  return last;
}

/**
 * jsdom(css:false)은 캐스케이드를 계산하지 않는다 — 전역 규칙이 OC 규칙을 이기는지는 원문으로 고정한다.
 * `01-base.css` 의 `button:hover:not(:disabled)`(0,2,1)·`button:disabled { opacity: .5 }` 와 `08-hand.css` 의
 * `.card-chip:hover:not(:disabled)`(0,3,0)는 단일 클래스(0,1,0) 규칙을 이긴다. 그래서 hover·disabled 상태를 19 가 직접
 * 선언한다(스컬킹 C5 와 같은 문제).
 */
describe('19-onecard-table.css — 전역 hover·disabled 규칙을 이기는 상태 규칙', () => {
  const HOVER_BACKGROUNDS = [
    '.oc-play',
    '.oc-draw',
    '.oc-race-call',
    '.oc-race-catch',
    '.oc-suit-opt',
    '.oc-card-joker-black',
    '.oc-card-joker-color',
  ];

  it.each(HOVER_BACKGROUNDS)('%s 는 hover 에서도 기본 배경을 지킨다', (selector) => {
    const base = declared(selector, 'background');
    const hover = declared(`${selector}:hover:not(:disabled)`, 'background');

    expect(base, `${selector} 의 기본 배경`).toBeTruthy();
    expect(hover, `${selector}:hover:not(:disabled) 규칙`).toBe(base);
  });

  it.each(['.oc-play:disabled', '.oc-draw:disabled'])(
    '%s 는 불투명으로 고정한다 — 전역 button:disabled 의 opacity .5 를 이긴다',
    (selector) => {
      expect(declared(selector, 'opacity'), `${selector} 의 opacity`).toBe('1');
    },
  );

  it('차례이면서 1장 남은 좌석은 차례 링과 1장 링을 함께 그린다', () => {
    const shadow = declared('.oc-seat-turn.oc-seat-last', 'box-shadow');

    expect(shadow, '.oc-seat-turn.oc-seat-last 규칙').toBeTruthy();
    expect(shadow).toContain('var(--oc-accent)');
    expect(shadow).toContain('var(--oc-race-catch)');
  });
});

/**
 * `RaceButton` 의 레이어와 튜토리얼 연습 칸이 같은 `clamp(var(--oc-race-half-w), …)` 로 가장자리를 보정한다(`raceLeftCss`).
 * 변수는 버튼을 담는 쪽이 정의해야 하고, 없으면 `clamp()` 가 계산 시점에 무효가 되어 `left` 가 auto 로 떨어진다.
 */
describe('19-onecard-table.css — 버튼 반폭(--oc-race-half-w)', () => {
  it('경쟁 레이어와 연습 칸이 같은 값으로 정의한다', () => {
    const layer = declared('.oc-race-layer', '--oc-race-half-w');
    const practice = declared('.oc-practice-area', '--oc-race-half-w');

    expect(layer, '.oc-race-layer').toBe('64px');
    expect(practice, '.oc-practice-area').toBe(layer);
  });

  it('버튼 최소 폭(.oc-race-btn min-width)의 절반 이상이다 — clamp 한 중심에서 버튼이 칸 안에 머문다', () => {
    const half = Number.parseFloat(declared('.oc-race-layer', '--oc-race-half-w') ?? '0');
    const minWidth = Number.parseFloat(declared('.oc-race-btn', 'min-width') ?? '0');

    expect(minWidth).toBeGreaterThan(0);
    expect(half).toBeGreaterThanOrEqual(minWidth / 2);
  });
});

/** D-132 — 테이블 좌석 배치와 버튼 간격. jsdom(css:false)은 레이아웃을 재지 않으므로 원문으로 고정한다. */
describe('19-onecard-table.css — 테이블 배치 (D-132)', () => {
  // 보이는 간격 = 버튼 줄 gap 8px + 먹기의 시작 여백 24px = 32px(손가락 너비). 예전엔 여백이 32px 라 실제 간격이 40px 였다(최종 리뷰 I1).
  it('내기와 먹기 사이가 손가락 너비(32px) 떨어진다 — 붙어 있으면 먹기를 잘못 누른다', () => {
    expect(declared('.oc-actions', 'gap')).toBe('8px');
    expect(declared('.oc-play + .oc-draw', 'margin-inline-start')).toBe('24px');
  });

  // 간격을 넓힌 만큼 버튼 좌우 안쪽 여백을 18 → 12px 로 줄여 버튼 줄 폭이 변경 전으로 돌아온다 — 360·384px 안드로이드에서
  // "카드를 고르세요"·"내 차례 아님" 상태에도 한 줄이다(감기면 차례마다 sticky 버튼 줄 높이가 바뀌고 손패를 덮는다, 최종 리뷰 I1).
  it('간격을 넓힌 만큼 버튼 좌우 안쪽 여백을 줄여 좁은 폰에서도 버튼 줄이 한 줄이다', () => {
    expect(declared('.oc-play', 'padding')).toBe('10px 12px');
    expect(declared('.oc-draw', 'padding')).toBe('10px 12px');
    expect(declared('.oc-play', 'min-height')).toBe('44px');
  });

  it('테이블 색은 토큰이고 라이트 테마 값을 따로 둔다', () => {
    const dark = tokensOf('.oc-table').get('--oc-felt-bg');
    const light = tokensOf('html:not(.dark) .oc-table').get('--oc-felt-bg');

    expect(dark, '.oc-table --oc-felt-bg').toBeTruthy();
    expect(light, 'html:not(.dark) .oc-table --oc-felt-bg').toBeTruthy();
    expect(light).not.toBe(dark);
    expect(declared('.oc-felt', 'background')).toBe('var(--oc-felt-bg)');
  });

  it('옆줄 좌석 폭은 화면 폭을 따른다 — 폭 미디어 없이 clamp 로', () => {
    expect(declared('.oc-felt-left .oc-seat', 'width')).toBe('clamp(64px, 22vw, 150px)');
    expect(declared('.oc-felt-right .oc-seat', 'width')).toBe('clamp(64px, 22vw, 150px)');
  });

  it('옆줄 사이 가운데 칸이 좁아지면 더미와 맨 위 카드가 줄을 바꾼다 — 넘쳐 옆 좌석을 덮지 않는다', () => {
    expect(declared('.oc-felt .oc-piles', 'flex-wrap')).toBe('wrap');
  });

  // 좌석 DOM 의 data-side 는 표식일 뿐 — 자리를 잡는 것은 이 CSS 다. 여기를 반시계로 뒤집으면 컴포넌트 테스트는 모두 통과한다.
  it('시계 방향 자리 — 왼쪽 옆줄은 왼쪽 칸에서 아래→위, 오른쪽 옆줄은 오른쪽 칸에서 위→아래', () => {
    // D-139 — 맨 위 상태 줄(왼쪽 남은 시간·오른쪽 방향)을 위해 status 행이 하나 더 있다.
    expect(lastDeclared('.oc-felt', 'grid-template-areas')).toBe(
      "'status status status' 'top top top' 'left center right' 'bottom bottom bottom'",
    );
    expect(lastDeclared('.oc-felt-status', 'grid-area')).toBe('status');
    expect(lastDeclared('.oc-felt-status', 'justify-content')).toBe('space-between');
    expect(lastDeclared('.oc-felt-left', 'grid-area')).toBe('left');
    expect(lastDeclared('.oc-felt-left', 'flex-direction')).toBe('column-reverse');
    expect(lastDeclared('.oc-felt-right', 'grid-area')).toBe('right');
    expect(lastDeclared('.oc-felt-right', 'flex-direction')).toBe('column');
  });

  // 390px 옆줄 좌석 내용 ≈65px 에 아바타 28px 면 한 줄 이름 칸은 ≈31px(두 글자)다. 모바일은 title 툴팁이 뜨지 않는다.
  it('좁은 좌석에서는 이름이 아바타 아래 줄로 내려간다 — 한 줄에 아바타 + 이름 44px 가 안 들어가면', () => {
    expect(lastDeclared('.oc-seat-top', 'flex-wrap')).toBe('wrap');
    expect(lastDeclared('.oc-seat-name', 'flex')).toBe('1 1 44px');
  });

  // 390px 가운데 칸 ≈138px — 더미 뒷면 62px + 문구 + 맨 위 카드 48px 가 한 줄에 안 들어가 천이 ≈80px 높아졌다.
  it('가운데 칸이 좁으면 더미 뒷면·문구·간격이 폭을 따라 줄어 더미와 맨 위 카드가 한 줄에 든다', () => {
    expect(lastDeclared('.oc-felt .oc-pile-back', 'width')).toBe('clamp(40px, 11vw, 62px)');
    expect(lastDeclared('.oc-felt .oc-pile-count', 'max-width')).toBe('clamp(50px, 14vw, 120px)');
    expect(lastDeclared('.oc-felt .oc-piles', 'gap')).toBe('8px clamp(6px, 2vw, 24px)');
  });

  // .oc-badge 는 nowrap 이라 "guest-ab12cd34 차례" ≈137px 가 가운데 칸(≈126px)을 넘어 옆줄 좌석에 걸쳤다. 헤더 배지는 그대로.
  it('가운데 칸 배지는 칸 안에서 줄을 바꾼다 — 긴 이름의 "차례" 배지가 옆줄 좌석으로 넘치지 않는다', () => {
    expect(lastDeclared('.oc-felt .oc-center-info .oc-badge', 'white-space')).toBe('normal');
    expect(lastDeclared('.oc-felt .oc-center-info .oc-badge', 'max-width')).toBe('100%');
  });
});

describe('index.css — 캐스케이드 순서', () => {
  const order = [...indexCssSource.matchAll(/@import '\.\/parts\/(\d+)-[^']+'/g)].map((m) =>
    Number(m[1]),
  );

  it('19 가 18 뒤, 17 앞에 온다', () => {
    expect(order.indexOf(19)).toBeGreaterThan(order.indexOf(18));
    expect(order.indexOf(19)).toBeLessThan(order.indexOf(17));
  });

  it('17-responsive 가 여전히 마지막 part 다 (D-88 불변식)', () => {
    expect(order[order.length - 1]).toBe(17);
  });
});
