import { describe, expect, it } from 'vitest';
import { indexCssSource, responsiveCssSource, wordtempCssSource, yachtCssSource } from './cssSources';

/** `.wt-` 네임스페이스 규칙(다른 게임판 part 와 같은 규칙)과 캐스케이드 순서를 고정한다(D-319). */
const cssCode = wordtempCssSource
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

describe('29-wordtemp-table.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(60);
  });

  it('모든 선택자가 .wt- 클래스를 포함한다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.wt-')));
    expect(offenders, `.wt- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('미디어 쿼리가 0개다 (17 앞에 import 되므로 폭 규칙을 두면 덮이고, 동작 줄이기는 17 의 짝 규칙을 따른다)', () => {
    expect(cssCode).not.toMatch(/@media/);
  });

  it('글꼴을 지정하지 않는다 — 앱 기본 글꼴 하나', () => {
    expect(cssCode).not.toMatch(/font-family/);
  });

  it('움직임(animation)은 .wt-anim 아래에만 건다 — 연출 설정이 꺼지면 돌지 않는다', () => {
    const re = /(^|})\s*([^{}@]+?)\s*\{([^{}]*)\}/g;
    const offenders: string[] = [];
    let m: RegExpExecArray | null;
    while ((m = re.exec(cssCode)) !== null) {
      if (/(^|;|\s)animation(-name)?\s*:/.test(m[3]) && !m[2].split(',').every((one) => one.includes('.wt-anim'))) {
        offenders.push(m[2].trim());
      }
    }
    expect(offenders, offenders.join(' | ')).toEqual([]);
  });

  it('연출 층은 눌림을 받지 않는다', () => {
    const m = /\.wt-fx-boil,\s*\.wt-fx-cheer\s*\{([^}]*)\}/.exec(cssCode);
    expect(m).not.toBeNull();
    expect(m![1]).toContain('pointer-events: none');
  });

  it('얏추(.yt-)와 접두가 겹치지 않는다', () => {
    expect(wordtempCssSource).not.toMatch(/\.yt-/);
    expect(yachtCssSource).not.toMatch(/\.wt-/);
  });

  it('28 다음, 17-responsive 앞에 import 된다', () => {
    const surface = indexCssSource.indexOf("parts/28-game-surface.css'");
    const wt = indexCssSource.indexOf("parts/29-wordtemp-table.css'");
    const responsive = indexCssSource.indexOf("parts/17-responsive.css'");
    expect(surface).toBeGreaterThan(-1);
    expect(wt).toBeGreaterThan(surface);
    expect(responsive).toBeGreaterThan(wt);
  });

  it('튜토리얼 포털이 쓰는 온도계 토큰을 .wt-tokens 가 선언한다(라이트·다크)', () => {
    const light = /(^|})\s*\.wt-tokens\s*\{([^}]*)\}/.exec(cssCode);
    const dark = /\.dark \.wt-tokens,\s*\.wt-tokens\.dark\s*\{([^}]*)\}/.exec(cssCode);
    expect(light).not.toBeNull();
    expect(dark).not.toBeNull();
    for (const t of ['--wt-cold', '--wt-warm', '--wt-hot', '--wt-boil', '--wt-tube', '--wt-tube-line', '--wt-row', '--wt-ink']) {
      expect(light![2], t).toContain(`${t}:`);
      expect(dark![1], t).toContain(`${t}:`);
    }
  });

  it('17 의 .wt- 블록 — 동작 줄이기는 :root:not(.motion-forced) 아래, 바로 뒤에 motion-off 짝(D-291)', () => {
    const at = responsiveCssSource.indexOf('/* D-319 — 낱말 온도');
    const sk = responsiveCssSource.indexOf('/* D-132 — 스컬킹 테이블');
    expect(at).toBeGreaterThan(-1);
    expect(sk).toBeGreaterThan(at); // 스컬킹 맨 끝 두 블록 앞
    const block = responsiveCssSource.slice(at, sk);
    expect(block).toContain(':root:not(.motion-forced) .wt-table *');
    expect(block).toContain(':root:not(.motion-forced) .wt-fx-boil');
    expect(block).toContain(':root.motion-off .wt-table *');
    expect(block).toContain(':root.motion-off .wt-fx-cheer');
    expect(block).toMatch(/motion-off:begin[\s\S]*motion-off:end/);
  });
});
