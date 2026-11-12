/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다(errorText.test 와 같은 패턴).
import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { CROWN_RANKS, STARTING_RATING, TIER_FLOORS, TIER_ORDER, tierRequirement } from './tierTable';

/** D-266 — 계급표 숫자는 서버 `Tier.java` 의 거울이다. 저장소 안에서만(클라 단독 체크아웃이면 건너뜀) 원문과 대조한다. */
describe('tierTable — 서버 Tier.java 와 대조 (D-266)', () => {
  // 리터럴 `new URL('…', import.meta.url)` 은 Vite 가 자산 주소로 바꾼다 — 인자로 받아 넘긴다.
  const here = (relative: string) => new URL(relative, import.meta.url);
  const tierJava = here('../../../../server/src/main/java/com/mirboard/domain/game/scoring/Tier.java');
  const exists = (existsSync as unknown as (p: URL) => boolean)(tierJava);
  const source = () => (readFileSync as unknown as (p: URL, enc: string) => string)(tierJava, 'utf-8');

  it.skipIf(!exists)('계급 순서가 enum 선언 순서와 같다', () => {
    const body = source().match(/public enum Tier \{([^;]*);/)?.[1] ?? '';
    const names = body
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    expect(names).toEqual([...TIER_ORDER]);
  });

  it.skipIf(!exists)('점수 구간(FLOORS)이 같다', () => {
    const src = source();
    const goldBadgeMin = Number(src.match(/GOLD_BADGE_MIN\s*=\s*(\d+)/)?.[1]);
    const floorsBody = (src.match(/FLOORS\s*=\s*\{([^}]*)\}/)?.[1] ?? '').replace(/\/\/[^\n]*/g, '');
    const floors = floorsBody
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map((s) => (s === 'GOLD_BADGE_MIN' ? goldBadgeMin : Number(s)));
    expect(floors).toEqual([...TIER_FLOORS]);
    expect(TIER_FLOORS).toContain(STARTING_RATING);
    // 총알 ~ 금훈장 = FLOORS 한 칸씩.
    expect(TIER_ORDER[TIER_FLOORS.length]).toBe('GOLD_BADGE');
  });

  it.skipIf(!exists)('왕관 순위(withRank)가 같다', () => {
    const src = source();
    const last = Number(src.match(/LAST_CROWN_RANK\s*=\s*(\d+)/)?.[1]);
    expect(src).toMatch(/rank == 1\) return STAR_CROWN/);
    const gold = Number(src.match(/rank <= (\d+)\) return GOLD_CROWN/)?.[1]);
    const silver = Number(src.match(/rank <= (\d+)\) return SILVER_CROWN/)?.[1]);
    expect(src).toMatch(/rank <= LAST_CROWN_RANK\) return BRONZE_CROWN/);
    expect(CROWN_RANKS).toEqual({
      STAR_CROWN: [1, 1],
      GOLD_CROWN: [2, gold],
      SILVER_CROWN: [gold + 1, silver],
      BRONZE_CROWN: [silver + 1, last],
    });
  });
});

describe('tierRequirement (D-266)', () => {
  it('해골은 미만, 점수 계급은 이상, 왕관은 순위', () => {
    expect(tierRequirement('SKULL')).toBe('940점 미만');
    expect(tierRequirement('BULLET')).toBe('940점 이상');
    expect(tierRequirement('MISSILE')).toBe('1000점 이상');
    expect(tierRequirement('GOLD_MEDAL')).toBe('1250점 이상');
    expect(tierRequirement('GOLD_BADGE')).toBe('1400점 이상');
    expect(tierRequirement('STAR_CROWN')).toBe('금훈장 + 게임 순위 1위');
    expect(tierRequirement('GOLD_CROWN')).toBe('금훈장 + 게임 순위 2~3위');
    expect(tierRequirement('SILVER_CROWN')).toBe('금훈장 + 게임 순위 4~10위');
    expect(tierRequirement('BRONZE_CROWN')).toBe('금훈장 + 게임 순위 11~30위');
  });
});
