import { describe, expect, it } from 'vitest';
import { indexCssSource, responsiveCssSource, skullkingCssSource } from './cssSources';

/**
 * `.sk-` 네임스페이스 규칙을 관례가 아니라 **기계로** 강제한다 (D-103).
 *
 * 스컬킹 게임판 CSS 가 공용 클래스(`.seat`, `.card-chip`, `.my-hand` …)를 재정의하면
 * 티츄 게임판이 조용히 번진다 — 리뷰로만 막기엔 재발이 쉬운 종류의 사고다.
 * 캐스케이드 순서(18 이 16 뒤·17 앞)와 "18 에 폭 미디어 0개"도 함께 고정한다.
 */

const css = skullkingCssSource;
/** 주석 제거본 — 헤더 주석이 "@media max-width" 를 **설명**하므로 원문으로 검사하면 오탐. */
const cssCode = css.replace(/\/\*[\s\S]*?\*\//g, '');
const indexCss = indexCssSource;

/** 주석을 제거한 뒤 선언 블록 앞의 선택자 그룹만 뽑는다. */
function selectorGroups(source: string): string[] {
  const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, '');
  const groups: string[] = [];
  const re = /(^|})\s*([^{}@]+?)\s*\{/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(withoutComments)) !== null) {
    const sel = m[2].trim();
    if (sel) groups.push(sel);
  }
  return groups;
}

describe('18-skullking-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(css);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(30);
  });

  it('모든 선택자가 .sk- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => !g.includes('.sk-'));
    expect(offenders, `.sk- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('공용 클래스를 단독으로 재정의하지 않는다', () => {
    // `.seat {`, `.card-chip {` 처럼 .sk- 수식 없이 공용 클래스만 쓰는 그룹을 잡는다.
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

/** 주석 제거본에서 선택자가 정확히 `selector` 인 첫 블록의 `--토큰: 값` 을 뽑는다. */
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
 * D-121 — 튜토리얼 다이얼로그는 body 포털이라 `.sk-table` 밖이다. 토큰이 안 풀리면 칩 배경이
 * `var(--sk-suit-*)` → transparent 가 되는데 jsdom(css:false)은 이를 못 잡는다. 그래서
 * `.sk-tokens` 가 칩 토큰을 **같은 값으로** 선언하는지 원문으로 고정한다.
 */
describe('18-skullking-table.css — .sk-tokens (D-121 포털 토큰)', () => {
  const CHIP_TOKENS = [
    '--sk-suit-green',
    '--sk-suit-purple',
    '--sk-suit-yellow',
    '--sk-suit-black',
    '--sk-special-bg',
    '--sk-special-fg',
  ];
  const table = tokensOf('.sk-table');
  const portal = tokensOf('.sk-tokens');

  it('칩이 쓰는 토큰을 모두 선언한다', () => {
    for (const t of CHIP_TOKENS) expect(portal.get(t), t).toBeTruthy();
  });

  it('값이 게임판(.sk-table) 토큰과 같다 — 복제본이 어긋나면 다이얼로그 칩 색만 달라진다', () => {
    expect(portal.size).toBeGreaterThan(0);
    for (const [name, value] of portal) {
      expect(value, name).toBe(table.get(name));
    }
  });

  it('레이아웃 속성은 갖지 않는다 — 토큰만 푸는 클래스다', () => {
    const block = /(^|})\s*\.sk-tokens\s*\{([^}]*)\}/.exec(cssCode)?.[2] ?? '';
    expect(block).not.toMatch(/(^|[;\s])(display|padding|min-height|gap)\s*:/);
  });
});

/** D-132 — 테이블 좌석 배치. jsdom(css:false)은 레이아웃·미디어 쿼리를 재지 않으므로 원문으로 고정한다. */
describe('18-skullking-table.css — 테이블 배치 (D-132)', () => {
  /** 쉼표 그룹 안에 `selector` 가 (정확히) 들어 있는 규칙들 중 `prop` 을 정한 첫 값 — 공백은 접어서 비교한다. */
  function declaredIn(selector: string, prop: string): string | undefined {
    const re = /([^{}@]+?)\s*\{([^}]*)\}/g;
    const decl = new RegExp(`(^|[;\\s])${prop}\\s*:\\s*([^;]+);`);
    let m: RegExpExecArray | null;
    while ((m = re.exec(cssCode)) !== null) {
      const group = m[1].split(',').map((one) => one.replace(/\s+/g, ' ').trim());
      const d = group.includes(selector) ? decl.exec(m[2]) : null;
      if (d) return d[2].trim();
    }
    return undefined;
  }

  /**
   * 같은 특이도에선 뒤 규칙이 이긴다 — `selector` 를 포함한 규칙들 중 `prop` 의 **마지막** 값. `declaredIn` 은 첫 값이라
   * `.sk-felt-left, .sk-felt-right { flex-direction: column }` 그룹 규칙을 읽어 `.sk-felt-left` 의 `column-reverse` 를 놓친다.
   */
  function lastDeclaredIn(selector: string, prop: string): string | undefined {
    const re = /([^{}@]+?)\s*\{([^}]*)\}/g;
    const decl = new RegExp(`(^|[;\\s])${prop}\\s*:\\s*([^;]+);`);
    let last: string | undefined;
    let m: RegExpExecArray | null;
    while ((m = re.exec(cssCode)) !== null) {
      const group = m[1].split(',').map((one) => one.replace(/\s+/g, ' ').trim());
      const d = group.includes(selector) ? decl.exec(m[2]) : null;
      if (d) last = d[2].replace(/\s+/g, ' ').trim();
    }
    return last;
  }

  it('테이블 색은 토큰이고 라이트 테마 값을 따로 둔다', () => {
    const dark = tokensOf('.sk-table').get('--sk-felt-bg');
    const light = tokensOf('html:not(.dark) .sk-table').get('--sk-felt-bg');

    expect(dark, '.sk-table --sk-felt-bg').toBeTruthy();
    expect(light, 'html:not(.dark) .sk-table --sk-felt-bg').toBeTruthy();
    expect(light).not.toBe(dark);
    expect(declaredIn('.sk-felt', 'background')).toBe('var(--sk-felt-bg)');
  });

  it('옆줄 좌석 폭은 화면 폭을 따른다 — 폭 미디어 없이 clamp 로', () => {
    expect(declaredIn('.sk-felt-left .sk-seat', 'width')).toBe('clamp(64px, 22vw, 150px)');
    expect(declaredIn('.sk-felt-right .sk-seat', 'width')).toBe('clamp(64px, 22vw, 150px)');
  });

  // 390px 가운데 칸 ≈138px 에서는 트릭 레일이 한 줄에 1장씩 쌓여 8인 천이 ≈800~1000px 가 됐다 — 좁은 화면만 가운데를 옆줄
  // 아래 전체 폭으로 내린다. 좌·우·위 자리(시계 방향)는 그대로다.
  it('17-responsive 맨 끝 블록이 ≤480px 에서 옆줄 손패 장수를 숨기고 가운데 칸을 옆줄 아래 전체 폭으로 내린다 — .sk- 선택자만', () => {
    const code = responsiveCssSource.replace(/\/\*[\s\S]*?\*\//g, '');
    const last = code.slice(code.lastIndexOf('@media')).replace(/\s+/g, ' ').trim();

    expect(last).toBe(
      '@media (max-width: 480px) { .sk-seat-side .sk-stat-hand { display: none; } ' +
        ".sk-felt { grid-template-areas: 'top top top' 'left . right' 'center center center' 'bottom bottom bottom'; " +
        'grid-template-rows: auto auto auto auto; } }',
    );
  });

  // 좌석 DOM 의 data-side 는 표식일 뿐 — 자리를 잡는 것은 이 CSS 다. 여기를 반시계로 뒤집으면 컴포넌트 테스트는 모두 통과한다.
  it('시계 방향 자리 — 왼쪽 옆줄은 왼쪽 칸에서 아래→위, 오른쪽 옆줄은 오른쪽 칸에서 위→아래', () => {
    expect(lastDeclaredIn('.sk-felt', 'grid-template-areas')).toBe(
      "'top top top' 'left center right' 'bottom bottom bottom'",
    );
    expect(lastDeclaredIn('.sk-felt-left', 'grid-area')).toBe('left');
    expect(lastDeclaredIn('.sk-felt-left', 'flex-direction')).toBe('column-reverse');
    expect(lastDeclaredIn('.sk-felt-right', 'grid-area')).toBe('right');
    expect(lastDeclaredIn('.sk-felt-right', 'flex-direction')).toBe('column');
  });

  // 390px 옆줄 좌석 내용 ≈65px 에 아바타 28px 면 한 줄 이름 칸은 ≈31px(두 글자)다. 모바일은 title 툴팁이 뜨지 않는다.
  it('좁은 좌석에서는 이름이 아바타 아래 줄로 내려간다 — 한 줄에 아바타 + 이름 44px 가 안 들어가면', () => {
    expect(lastDeclaredIn('.sk-seat-top', 'flex-wrap')).toBe('wrap');
    expect(lastDeclaredIn('.sk-seat-name', 'flex')).toBe('1 1 44px');
  });

  // 8인 윗줄 3명 × 100px + 간격이 375·360px 천 내용을 넘으면 2 + 1 로 감겨 위쪽 반원 순서가 흐트러진다 — 한 줄에서 줄인다.
  it('윗줄·아래 줄은 감기지 않는다 — 좌석이 한 줄에서 줄어든다', () => {
    expect(lastDeclaredIn('.sk-felt-top', 'flex-wrap') ?? 'nowrap').toBe('nowrap');
    expect(lastDeclaredIn('.sk-felt-bottom', 'flex-wrap') ?? 'nowrap').toBe('nowrap');
    expect(lastDeclaredIn('.sk-seat', 'min-width')).toBe('0');
  });

  // 좌석 배경이 반투명이라 키 글자 뒤가 밝은 천(#dde7f1)이다 — opacity .6 이면 4.36:1 로 AA(4.5) 미달.
  it('라이트 테마 천 위 좌석 통계 키는 AA 대비가 나오게 덜 흐리게 한다', () => {
    expect(lastDeclaredIn('html:not(.dark) .sk-felt .sk-stat-key', 'opacity')).toBe('0.7');
  });
});

describe('index.css — 캐스케이드 순서', () => {
  const order = [...indexCss.matchAll(/@import '\.\/parts\/(\d+)-[^']+'/g)].map(
    (m) => Number(m[1]),
  );

  it('18 이 16 뒤에 온다', () => {
    expect(order.indexOf(18)).toBeGreaterThan(order.indexOf(16));
  });

  it('18 이 17 앞에 온다', () => {
    expect(order.indexOf(18)).toBeLessThan(order.indexOf(17));
  });

  it('17-responsive 가 여전히 마지막 part 다 (D-88 불변식)', () => {
    expect(order[order.length - 1]).toBe(17);
  });
});
