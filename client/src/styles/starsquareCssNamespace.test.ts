import { describe, expect, it } from 'vitest';
import { indexCssSource, starsquareCssSource } from './cssSources';

/** `.sq-` 네임스페이스 규칙(오목 `omokCssNamespace.test` 와 같은 규칙)과 캐스케이드 순서를 고정한다(D-286). */
const cssCode = starsquareCssSource
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/@keyframes[^{]*\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}/g, '');

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

describe('27-starsquare-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(30);
  });

  it('모든 선택자가 .sq- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.sq-')));
    expect(offenders, `.sq- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('폭 미디어 쿼리가 0개다 (17 앞에 import 되므로 두면 덮인다)', () => {
    expect(cssCode).not.toMatch(/@media[^{]*(width|pointer|hover)/);
  });

  it('25 다음, 17-responsive 앞에 import 된다', () => {
    const tc = indexCssSource.indexOf("parts/25-tichu-lacquer.css'");
    const sq = indexCssSource.indexOf("parts/27-starsquare-table.css'");
    const responsive = indexCssSource.indexOf("parts/17-responsive.css'");
    expect(tc).toBeGreaterThan(-1);
    expect(sq).toBeGreaterThan(tc);
    expect(responsive).toBeGreaterThan(sq);
  });

  it('튜토리얼 포털이 쓰는 판 토큰을 .sq-tokens 가 선언한다', () => {
    const m = /(^|})\s*\.sq-tokens\s*\{([^}]*)\}/.exec(cssCode);
    expect(m).not.toBeNull();
    for (const t of ['--sq-cell', '--sq-cell-clue', '--sq-star', '--sq-ok', '--sq-constellation']) {
      expect(m![2]).toContain(t);
    }
  });
});
