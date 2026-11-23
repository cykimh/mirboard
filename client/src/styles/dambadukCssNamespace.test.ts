import { describe, expect, it } from 'vitest';
import { dambadukCssSource, indexCssSource } from './cssSources';

/** `.db-` 네임스페이스 규칙(오목 `omokCssNamespace.test` 와 같은 규칙)과 캐스케이드 순서를 고정한다. */
const cssCode = dambadukCssSource
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

describe('26-dambaduk-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(30);
  });

  it('모든 선택자가 .db- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.db-')));
    expect(offenders, `.db- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('미디어 쿼리가 0개다 (17 앞에 import 되므로 두면 덮인다)', () => {
    expect(cssCode).not.toMatch(/@media/);
  });

  it('25 다음, 17-responsive 앞에 import 된다', () => {
    const lacquer = indexCssSource.indexOf("parts/25-tichu-lacquer.css'");
    const db = indexCssSource.indexOf("parts/26-dambaduk-table.css'");
    const responsive = indexCssSource.indexOf("parts/17-responsive.css'");
    expect(lacquer).toBeGreaterThan(-1);
    expect(db).toBeGreaterThan(lacquer);
    expect(responsive).toBeGreaterThan(db);
  });

  it('튜토리얼 포털이 쓰는 판 토큰을 .db-tokens 가 선언한다', () => {
    const m = /(^|})\s*\.db-tokens\s*\{([^}]*)\}/.exec(cssCode);
    expect(m).not.toBeNull();
    for (const t of ['--db-wood', '--db-line', '--db-black', '--db-white', '--db-blue', '--db-wall']) {
      expect(m![2]).toContain(t);
    }
  });

  it('글꼴을 따로 정하지 않는다(앱 기본 글꼴 하나 — 얏추 D-264 와 같은 규칙)', () => {
    expect(cssCode).not.toMatch(/font-family/);
  });
});
