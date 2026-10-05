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
