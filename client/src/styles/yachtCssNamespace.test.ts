import { describe, expect, it } from 'vitest';
import { indexCssSource, responsiveCssSource, yachtCssSource } from './cssSources';
import { ROLL_ANIMATION_MS, ROLL_DIE_MS, ROLL_STAGGER_MS } from '@/features/yacht/DiceTray';

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

  it('D-203 — 굴림 연출이 ROLL_ANIMATION_MS 안에 끝난다(주사위 5개: 4 × 엇갈림 + 한 개 길이)', () => {
    const rolling = /\.yt-theme-salon \.yt-die-rolling \.yt-die-face\s*\{([^}]*)\}/.exec(cssCode)?.[1] ?? '';
    expect(rolling).toContain(`yt-salon-throw ${ROLL_DIE_MS}ms`);
    expect(rolling).toContain(`* ${ROLL_STAGGER_MS}ms`);
    expect(4 * ROLL_STAGGER_MS + ROLL_DIE_MS).toBeLessThanOrEqual(ROLL_ANIMATION_MS);
    // 정육면체 회전·바닥 그림자도 같은 길이·같은 엇갈림이다.
    for (const part of ['.yt-cube', '.yt-die-shadow']) {
      const body = new RegExp(`\\.yt-theme-salon \\.yt-die-rolling \\${part}\\s*\\{([^}]*)\\}`).exec(cssCode)?.[1] ?? '';
      expect(body, part).toContain(`${ROLL_DIE_MS}ms`);
      expect(body, part).toContain(`* ${ROLL_STAGGER_MS}ms`);
    }
  });

  it('D-203 — 살롱 연출은 움직임 줄이기에서 꺼진다(17 에 같은 명시도로)', () => {
    for (const needle of [
      '.yt-theme-salon .yt-die-rolling .yt-cube',
      '.yt-theme-salon .yt-felt-party',
      '.yt-theme-salon .yt-fx',
      '.yt-burst',
    ]) {
      expect(responsiveCssSource, needle).toContain(needle);
    }
  });

  it('D-264 — 글꼴을 따로 정하지 않는다(굴리기·점수표·현수막·좌석 모두 앱 기본 글꼴). 숫자 줄맞춤은 남는다', () => {
    expect(yachtCssSource).not.toMatch(/font-family/);
    expect(yachtCssSource).not.toMatch(/serif|Palatino|Myeongjo|Myungjo|Batang|Georgia|--yt-s-serif/i);
    expect(cssCode).not.toMatch(/@font-face|@import/);
    expect(cssCode).toContain('font-variant-numeric: tabular-nums');
    // 축약 속성으로 글꼴을 바꾸는 곳도 없다(버튼의 `font: inherit` 만).
    const shorthands = [...cssCode.matchAll(/[\s;{]font\s*:\s*([^;]+);/g)].map((m) => m[1].trim());
    expect(shorthands.every((v) => v === 'inherit')).toBe(true);
  });

  it('D-264 — 흔들어 굴리기의 떨림·컵 흔들림은 움직임 줄이기에서 멈추고, 컵은 가만히 남는다', () => {
    expect(cssCode).toContain('.yt-theme-salon .yt-cup.yt-cup-shake');
    const block = /D-264[\s\S]*?@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/.exec(responsiveCssSource)?.[1] ?? '';
    expect(block).toContain('.yt-dice-shaking-live .yt-die:not(.yt-die-held):not(.yt-die-empty) .yt-die-face');
    expect(block).toMatch(/\.yt-theme-salon \.yt-cup\.yt-cup-shake \{\s*display: block;/);
    expect(block).toContain('animation: none');
  });

  it('D-268 — [굴리기]는 길게 눌러도 글자 선택·길게 누르기 메뉴·스크롤 취소가 없고, [흔들기] 버튼 규칙은 남아 있지 않다', () => {
    const main = /\n\.yt-roll-main \{([^}]*)\}/.exec(cssCode)?.[1] ?? '';
    expect(main).toContain('touch-action: none');
    expect(main).toContain('user-select: none');
    expect(main).toContain('-webkit-user-select: none');
    expect(main).toContain('-webkit-touch-callout: none');
    expect(cssCode).toContain('.yt-roll-left');
    expect(cssCode).not.toMatch(/\.yt-shake[\s,.:{]/);
    expect(responsiveCssSource).not.toMatch(/\.yt-shake[\s,.:{]/);
  });

  it('D-268 — 남의 흔들기(명패의 작은 컵·작은 주사위 떨림)도 움직임 줄이기에서 멈춘다', () => {
    expect(cssCode).toContain('.yt-seat-cup-live');
    const block = /D-264[\s\S]*?@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/.exec(responsiveCssSource)?.[1] ?? '';
    expect(block).toMatch(/\.yt-seat-cup-live,\s*\.yt-seat-dice-shaking-live \.yt-mini-die[^{]*\{\s*animation: none;/);
  });

  it('튜토리얼 포털 토큰(.yt-tokens)이 게임판 토큰과 같은 값이다', () => {
    const table = tokensOf('.yt-table');
    const portal = tokensOf('.yt-tokens');
    expect(portal.size).toBeGreaterThan(0);
    for (const [name, value] of portal) expect(value, name).toBe(table.get(name));
  });

  it('D-270 — 살롱의 흔드는 중·굴리는 중 버튼 모습은 hover·active 규칙(명시도 0,4,0)보다 세다', () => {
    // 이겨야 하는 규칙들 — 전부 클래스·의사 클래스 넷(0,4,0)이고 일부는 17 에 있어 뒤에 온다. 순서가 아니라 명시도로 이겨야 한다.
    expect(cssCode).toContain('.yt-theme-salon .yt-roll:hover:not(:disabled)');
    expect(cssCode).toContain('.yt-theme-salon .yt-roll:not(:disabled):active');
    expect(responsiveCssSource).toContain('.yt-theme-salon .yt-roll:not(:disabled):hover');
    const rule =
      /\.yt-theme-salon \.yt-roll\.yt-roll-main\.yt-roll-shaking:not\(:disabled\),\s*\.yt-theme-salon \.yt-roll\.yt-roll-main\.yt-roll-pending:not\(:disabled\) \{([^}]*)\}/.exec(
        cssCode,
      )?.[1] ?? '';
    expect(rule).toContain('filter:');
    expect(rule).toContain('background:');
    expect(rule).toContain('transform:');
    // 약한 선택자(0,3,0)로 같은 모습을 정하는 규칙은 남아 있지 않다.
    expect(cssCode).not.toMatch(/\.yt-theme-salon \.yt-roll\.yt-roll-(shaking|pending)[\s,{]/);
  });
});
