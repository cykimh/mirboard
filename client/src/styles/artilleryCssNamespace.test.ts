import { describe, expect, it } from 'vitest';
import { artilleryCssSource, indexCssSource } from './cssSources';

/**
 * `.ar-` 네임스페이스 규칙을 기계로 강제한다(요트 `yachtCssNamespace.test` 와 같은 규칙). 씨앗포 게임판 CSS 가 공용 클래스를
 * 재정의하면 다른 게임판이 조용히 번진다. 캐스케이드 순서(22 가 21 뒤·17 앞)와 "22 에 미디어 0개"도 고정한다.
 */
const cssCode = artilleryCssSource
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

describe('23-artillery-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(40);
  });

  it('모든 선택자가 .ar- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.ar-')));
    expect(offenders, `.ar- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('미디어 쿼리가 0개다 (17 앞에 import 되므로 두면 덮인다)', () => {
    expect(cssCode).not.toMatch(/@media/);
  });

  it('index.css 에서 21(구름항로) 다음, 17 앞에 import 된다', () => {
    const i21 = indexCssSource.indexOf("'./parts/22-cloudroute-table.css'");
    const i22 = indexCssSource.indexOf("'./parts/23-artillery-table.css'");
    const i17 = indexCssSource.indexOf("'./parts/17-responsive.css'");
    expect(i21).toBeGreaterThan(-1);
    expect(i22).toBeGreaterThan(i21);
    expect(i17).toBeGreaterThan(i22);
  });

  it('Canvas 가 읽는 하늘·구름 토큰이 라이트·다크 둘 다 있다', () => {
    const light = tokensOf('.ar-table');
    for (const name of ['--ar-sky-top', '--ar-sky-bottom', '--ar-cloud', '--ar-cloud-edge', '--ar-label']) {
      expect(light.has(name), name).toBe(true);
    }
    expect(cssCode).toContain('html.dark .ar-table');
  });
});
