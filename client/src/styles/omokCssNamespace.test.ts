import { describe, expect, it } from 'vitest';
import { indexCssSource, omokCssSource } from './cssSources';

/** `.om-` 네임스페이스 규칙(원카드 `onecardCssNamespace.test` 와 같은 규칙)과 캐스케이드 순서를 고정한다. */
const cssCode = omokCssSource
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

describe('21-omok-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(30);
  });

  it('모든 선택자가 .om- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.om-')));
    expect(offenders, `.om- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('미디어 쿼리가 0개다 (17 앞에 import 되므로 두면 덮인다)', () => {
    expect(cssCode).not.toMatch(/@media/);
  });

  it('19 다음, 17-responsive 앞에 import 된다', () => {
    const oc = indexCssSource.indexOf("parts/19-onecard-table.css'");
    const om = indexCssSource.indexOf("parts/21-omok-table.css'");
    const responsive = indexCssSource.indexOf("parts/17-responsive.css'");
    expect(oc).toBeGreaterThan(-1);
    expect(om).toBeGreaterThan(oc);
    expect(responsive).toBeGreaterThan(om);
  });

  it('튜토리얼 포털이 쓰는 판 토큰을 .om-tokens 가 선언한다', () => {
    const m = /(^|})\s*\.om-tokens\s*\{([^}]*)\}/.exec(cssCode);
    expect(m).not.toBeNull();
    for (const t of ['--om-wood', '--om-line', '--om-black', '--om-white', '--om-forbidden-mine']) {
      expect(m![2]).toContain(t);
    }
  });
});
