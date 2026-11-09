import { describe, expect, it } from 'vitest';
import { indexCssSource, responsiveCssSource, yachtCssSource } from './cssSources';

/**
 * `.yt-` 네임스페이스 규칙을 기계로 강제한다(원카드 `onecardCssNamespace.test` 와 같은 규칙). 요트 게임판 CSS 가 공용 클래스를
 * 재정의하면 다른 게임판이 조용히 번진다. 캐스케이드 순서(20 이 19 뒤·17 앞)와 "20 에 미디어 0개"도 고정한다.
 */
const cssCode = yachtCssSource
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

function tokensOf(selector: string): Map<string, string> {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`(^|})\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(cssCode);
  const tokens = new Map<string, string>();
  if (!m) return tokens;
  for (const [, name, value] of m[2].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) tokens.set(name, value.trim());
  return tokens;
}

describe('20-yacht-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(40);
  });

  it('모든 선택자가 .yt- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.yt-')));
    expect(offenders, `.yt- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('미디어 쿼리가 0개다 (17 앞에 import 되므로 두면 덮인다 — 움직임 줄이기는 17 에)', () => {
    expect(cssCode).not.toMatch(/@media/);
    expect(responsiveCssSource).toContain('.yt-die-rolling');
  });

  it('index.css 에서 19 다음, 17 앞에 import 된다', () => {
    const i19 = indexCssSource.indexOf("'./parts/19-onecard-table.css'");
    const i20 = indexCssSource.indexOf("'./parts/20-yacht-table.css'");
    const i17 = indexCssSource.indexOf("'./parts/17-responsive.css'");
    expect(i19).toBeGreaterThan(-1);
    expect(i20).toBeGreaterThan(i19);
    expect(i17).toBeGreaterThan(i20);
  });

  it('튜토리얼 포털 토큰(.yt-tokens)이 게임판 토큰과 같은 값이다', () => {
    const table = tokensOf('.yt-table');
    const portal = tokensOf('.yt-tokens');
    expect(portal.size).toBeGreaterThan(0);
    for (const [name, value] of portal) expect(value, name).toBe(table.get(name));
  });
});
