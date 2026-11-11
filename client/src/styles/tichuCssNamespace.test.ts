import { describe, expect, it } from 'vitest';
import { indexCssSource, responsiveCssSource, tichuLacquerCssSource } from './cssSources';
import { ARENA_FX_DURATION } from '@/features/tichu/effectStore';

/**
 * 티츄 "옻칠 찻집" 테마 층(D-241)의 `.tc-` 네임스페이스 규칙을 기계로 강제한다(다른 게임 `*CssNamespace.test` 와 같은 규칙).
 * 티츄는 공용 게임판 part(03~16)를 쓰므로, 테마 층이 공용 클래스를 단독으로 재정의하면 테마를 되돌려도(`'classic'`) 모습이
 * 돌아오지 않는다 — 모든 선택자가 `.tc-` 를 끼고 있어야 "클래스 하나를 떼면 전부 꺼진다"가 성립한다.
 */
const noComments = tichuLacquerCssSource.replace(/\/\*[\s\S]*?\*\//g, '');
const cssCode = noComments.replace(/@keyframes[^{]*\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}/g, '');

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

function bodyOf(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[},])\\s*${escaped}\\s*(,[^{]*)?\\{([^}]*)\\}`).exec(cssCode)?.[3] ?? '';
}

describe('25-tichu-lacquer.css — 네임스페이스 격리', () => {
  const groups = selectorGroups(cssCode);

  it('선택자 그룹이 실제로 추출된다 (파서 자체 가드)', () => {
    expect(groups.length).toBeGreaterThan(80);
  });

  it('모든 선택자가 .tc- 클래스를 포함한다 — 테마 클래스를 떼면 전부 꺼진다', () => {
    const offenders = groups.filter((g) => g.split(',').some((one) => !one.includes('.tc-')));
    expect(offenders, `.tc- 없는 선택자: ${offenders.join(' | ')}`).toEqual([]);
  });

  it('다른 게임 접두를 건드리지 않는다', () => {
    expect(cssCode).not.toMatch(/\.(sk|oc|yt|om|cr|ar|gm)-/);
  });

  it('미디어 쿼리가 0개다 (17 앞에 import 되므로 두면 덮인다 — 폭·동작 줄이기는 17 에)', () => {
    expect(cssCode).not.toMatch(/@media/);
    expect(responsiveCssSource).toContain('.tc-fx-shard');
    expect(responsiveCssSource).toContain('.tc-fx-layer *');
  });

  it('키프레임 이름은 전부 tc- 로 시작하고, 쓰는 이름은 모두 정의돼 있다', () => {
    const defined = [...noComments.matchAll(/@keyframes\s+([\w-]+)/g)].map((m) => m[1]);
    expect(defined.length).toBeGreaterThan(15);
    expect(defined.filter((n) => !n.startsWith('tc-'))).toEqual([]);
    expect(new Set(defined).size).toBe(defined.length);
    const used = [...cssCode.matchAll(/animation(?:-name)?\s*:\s*(tc-[\w-]+)/g)].map((m) => m[1]);
    expect(used.filter((n) => !defined.includes(n))).toEqual([]);
  });

  it('index.css 에서 공용 게임판 part 뒤, 17 앞에 import 된다 (17 이 여전히 마지막)', () => {
    const order = [...indexCssSource.matchAll(/@import '\.\/parts\/(\d+)-[^']+'/g)].map((m) => Number(m[1]));
    expect(order.indexOf(25)).toBeGreaterThan(order.indexOf(16));
    expect(order.indexOf(25)).toBeLessThan(order.indexOf(17));
    expect(order[order.length - 1]).toBe(17);
  });
});

describe('25-tichu-lacquer.css — 테마 내용', () => {
  it('판 색은 토큰이고 라이트 값을 따로 둔다', () => {
    const dark = tokensOf('.tc-theme-lacquer');
    const light = tokensOf('html:not(.dark) .tc-theme-lacquer');
    for (const name of ['--tc-felt-mid', '--tc-lacquer', '--tc-lamp']) {
      expect(dark.get(name), name).toBeTruthy();
      expect(light.get(name), name).toBeTruthy();
      expect(light.get(name), name).not.toBe(dark.get(name));
    }
  });

  it('무늬 넷의 색이 서로 다르다', () => {
    const t = tokensOf('.tc-card');
    const colors = ['--tc-jade', '--tc-sword', '--tc-pagoda', '--tc-star'].map((n) => t.get(n));
    expect(colors.every(Boolean)).toBe(true);
    expect(new Set(colors).size).toBe(4);
  });

  // 08-hand 의 `.card-chip:hover:not(:disabled)`(0,3,0)가 카드 바탕을 회색으로 바꾼다 — 같은 상태를 더 높은 특이도로 다시 정한다.
  it('카드 hover 가 바탕을 바꾸지 않는다 — 전역 hover 규칙을 이기는 상태 규칙', () => {
    const base = /background:\s*([^;]+);/.exec(bodyOf('.card-chip.tc-card'))?.[1];
    const hover = /background:\s*([^;]+);/.exec(bodyOf('.card-chip.tc-card:hover:not(:disabled)'))?.[1];
    expect(base).toBeTruthy();
    expect(hover?.replace(/\s+/g, ' ')).toBe(base?.replace(/\s+/g, ' '));
  });

  it('차례 강조는 좌석의 transform 을 건드리지 않는다 — 고리(::after)만 맥박', () => {
    expect(bodyOf('.tc-theme-lacquer .seat.turn')).not.toMatch(/transform|animation/);
    expect(bodyOf('.tc-theme-lacquer .seat.turn::after')).toMatch(/animation:\s*tc-turn-pulse/);
  });

  it('연출 층은 누를 수 없고 컨테이너다 (좌석 방향 클래스가 cqw/cqh 로 거리를 정한다)', () => {
    const layer = bodyOf('.tc-fx-layer');
    expect(layer).toMatch(/pointer-events:\s*none/);
    expect(layer).toMatch(/container-type:\s*size/);
    expect(bodyOf('.tc-fx-screen')).toMatch(/pointer-events:\s*none/);
    for (const pos of ['s', 'n', 'e', 'w']) {
      expect(tokensOf(`.tc-to-${pos}`).get('--tc-tx'), pos).toBeTruthy();
      expect(tokensOf(`.tc-from-${pos}`).get('--tc-fx'), pos).toBe(tokensOf(`.tc-to-${pos}`).get('--tc-tx'));
    }
    // 다음 차례(+1)는 왼쪽(e) — 음수 x.
    expect(tokensOf('.tc-to-e').get('--tc-tx')).toMatch(/^-/);
  });

  /** `animation: 이름 길이 [타이밍] [지연]` 에서 길이 + 지연(ms). */
  function animationMs(selector: string): number {
    const decl = /animation:\s*[\w-]+\s+(\d+)ms[^;]*?(?:\s(\d+)ms)?\s*(?:both)?;/.exec(bodyOf(selector));
    return decl ? Number(decl[1]) + Number(decl[2] ?? 0) : NaN;
  }

  it('경기장 연출이 스토어 노출 시간 안에 끝난다', () => {
    expect(animationMs('.tc-fx-sweep')).toBeLessThanOrEqual(ARENA_FX_DURATION.TRICK_SWEEP);
    expect(animationMs('.tc-fx-points')).toBeLessThanOrEqual(ARENA_FX_DURATION.TRICK_SWEEP);
    expect(animationMs('.tc-fx-dragon')).toBeLessThanOrEqual(ARENA_FX_DURATION.DRAGON_GIVEN);
    expect(animationMs('.tc-fx-stamp .tc-seal')).toBeLessThanOrEqual(ARENA_FX_DURATION.DECLARE_STAMP);
    expect(animationMs('.tc-fx-wish')).toBeLessThanOrEqual(ARENA_FX_DURATION.WISH);
    // 딜: 좌석 4 × 3장, 장마다 45ms 엇갈림(ArenaFxLayer) + 한 장 비행.
    const deal = /animation:\s*tc-deal-fly\s+(\d+)ms/.exec(bodyOf('.tc-fx-deal-card'));
    expect(Number(deal?.[1]) + 11 * 45).toBeLessThanOrEqual(ARENA_FX_DURATION.DEAL);
  });
});
