import { describe, expect, it } from 'vitest';
import { cloudrouteCssSource, indexCssSource } from './cssSources';

/**
 * `.cr-` 네임스페이스 규칙을 기계로 강제한다 (D-213, 원카드 `onecardCssNamespace.test` 와 같은 규칙). 구름항로 CSS 가 공용 클래스를
 * 재정의하면 다른 게임판이 조용히 번진다. 캐스케이드 순서(21 이 19 뒤·17 앞)와 "21 에 폭 미디어 0개"도 함께 고정한다.
 */

/** 주석과 @keyframes 블록 제거본 — 키프레임의 `from`/`to` 는 선택자가 아니다. */
const cssCode = cloudrouteCssSource
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

describe('22-cloudroute-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(40);
  });

  it('모든 선택자가 .cr- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.cr-')));
    expect(offenders, `.cr- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
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


/** 쉼표 그룹 안에 `selector` 가 (정확히) 들어 있는 규칙의 본문들. */
function bodiesOf(selector: string): string[] {
  const bodies: string[] = [];
  const re = /([^{}@]+?)\s*\{([^}]*)\}/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(cssCode)) !== null) {
    const group = m[1].split(',').map((one) => one.replace(/\s+/g, ' ').trim());
    if (group.includes(selector)) bodies.push(m[2]);
  }
  return bodies;
}

function declared(selector: string, prop: string): string | undefined {
  const decl = new RegExp(`(^|[;\\s])${prop}\\s*:\\s*([^;]+);`);
  for (const body of bodiesOf(selector)) {
    const m = decl.exec(body);
    if (m) return m[2].trim();
  }
  return undefined;
}

describe('22-cloudroute-table.css — 판과 손가락', () => {
  it('판은 7×7 정사각 격자다 — 폭 미디어 없이 화면 폭을 따라 줄어든다', () => {
    expect(declared('.cr-board', 'grid-template-columns')).toBe('repeat(7, minmax(0, 1fr))');
    expect(declared('.cr-board', 'aspect-ratio')).toBe('1 / 1');
    expect(declared('.cr-center', 'grid-row')).toBe('2 / 7');
  });

  it('결정 버튼과 단계 버튼은 44px 이상이다', () => {
    expect(declared('.cr-btn', 'min-height')).toBe('44px');
    expect(declared('.cr-level', 'min-height')).toBe('44px');
  });

  it.each(['.cr-tile', '.cr-btn', '.cr-level'])('%s 는 전역 hover 규칙을 이기는 hover 배경을 선언한다', (sel) => {
    expect(declared(`${sel}:hover:not(:disabled)`, 'background'), sel).toBeTruthy();
  });
});

describe('index.css — 캐스케이드 순서 (구름항로)', () => {
  const order = [...indexCssSource.matchAll(/@import '\.\/parts\/(\d+)-[^']+'/g)].map((m) => Number(m[1]));

  it('21 이 19 뒤, 17 앞에 온다', () => {
    expect(order.indexOf(21)).toBeGreaterThan(order.indexOf(19));
    expect(order.indexOf(21)).toBeLessThan(order.indexOf(17));
  });
});
