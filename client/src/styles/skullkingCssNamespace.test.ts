import { describe, expect, it } from 'vitest';
import { indexCssSource, skullkingCssSource } from './cssSources';

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
