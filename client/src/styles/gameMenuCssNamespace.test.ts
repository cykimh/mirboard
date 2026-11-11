import { describe, expect, it } from 'vitest';
import { gameMenuCssSource, indexCssSource } from './cssSources';

/**
 * D-186 — 게임판 ⋮ 메뉴·한 줄 머리 줄 CSS(24) 규칙. 모든 선택자가 `.gm-` 를 갖고(공용·게임 클래스를 단독 재정의하지 않는다),
 * 게임판 CSS(18~23) 뒤·17 앞에 import 되며(머리 줄 줄바꿈을 이기고 폭 미디어에는 진다), 폭 미디어는 0개다.
 */
const cssCode = gameMenuCssSource.replace(/\/\*[\s\S]*?\*\//g, '');

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

describe('24-game-menu.css', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(10);
  });

  it('모든 선택자가 .gm- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.gm-')));
    expect(offenders, `.gm- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('폭 미디어 쿼리가 0개다', () => {
    expect(cssCode).not.toMatch(/@media/);
  });

  it('게임판 CSS 뒤, 17-responsive 앞에 import 된다', () => {
    const at = (name: string) => indexCssSource.indexOf(`@import './parts/${name}'`);
    expect(at('24-game-menu.css')).toBeGreaterThan(at('23-artillery-table.css'));
    expect(at('24-game-menu.css')).toBeLessThan(at('17-responsive.css'));
  });
});
