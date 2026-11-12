import type { Category } from '@/types/yacht';

/** 칸 이름 — 일반 명사만(상표식 칸 이름 금지, `docs/rules-yacht.md` §3.2). */
export const CATEGORY_LABEL: Record<Category, string> = {
  ONES: '1',
  TWOS: '2',
  THREES: '3',
  FOURS: '4',
  FIVES: '5',
  SIXES: '6',
  CHOICE: '초이스',
  FOUR_OF_A_KIND: '포 카드',
  FULL_HOUSE: '풀 하우스',
  SMALL_STRAIGHT: '스몰 스트레이트',
  LARGE_STRAIGHT: '라지 스트레이트',
  // D-207 — 게임 이름은 얏추로 바뀌었지만 이 칸(다섯 개 같은 눈)은 족보 이름 "요트" 그대로다.
  YACHT: '요트',
};

/** 칸 설명(점수표 줄 title·튜토리얼). */
export const CATEGORY_HINT: Record<Category, string> = {
  ONES: '1 눈의 합',
  TWOS: '2 눈의 합',
  THREES: '3 눈의 합',
  FOURS: '4 눈의 합',
  FIVES: '5 눈의 합',
  SIXES: '6 눈의 합',
  CHOICE: '다섯 개의 합',
  FOUR_OF_A_KIND: '같은 눈 4개 이상 — 다섯 개의 합',
  FULL_HOUSE: '3개 + 2개(5개 같음도 됨) — 다섯 개의 합',
  SMALL_STRAIGHT: '연속 4개 — 15점',
  LARGE_STRAIGHT: '연속 5개 — 30점',
  YACHT: '5개 모두 같음 — 50점',
};

/** 위쪽 6칸 — 칸 이름 옆에 그 눈의 작은 주사위를 그린다(D-203). */
export const UPPER_FACE: Partial<Record<Category, number>> = {
  ONES: 1,
  TWOS: 2,
  THREES: 3,
  FOURS: 4,
  FIVES: 5,
  SIXES: 6,
};

/** 큰 칸 — 기록하면 짧은 축하 효과(상태 줄 배지 — 연출을 꺼도 남는다). */
export const BIG_CATEGORIES: ReadonlySet<Category> = new Set(['YACHT', 'LARGE_STRAIGHT', 'FULL_HOUSE']);
/** D-204 — 중간 크기 축하(주사위 다섯 개가 놓이는 현수막). 요트는 따로 큰 축하. */
export const MID_CATEGORIES: ReadonlySet<Category> = new Set(['LARGE_STRAIGHT', 'FULL_HOUSE']);

/**
 * D-204 — 축하 현수막에 그릴 주사위 순서. 화면이 기억한 마지막 굴림이 그 족보가 맞을 때만 돌려준다(점수 계산이 아니라 그림 확인 —
 * 시간 초과 자동 기록처럼 기억한 굴림이 낡았을 수 있다). 스트레이트는 오름차순, 풀 하우스는 3개 묶음 먼저.
 */
export function celebrationDice(category: Category, dice: readonly number[] | null | undefined): number[] | null {
  if (!dice || dice.length !== 5) return null;
  const sorted = [...dice].sort((a, b) => a - b);
  const counts = new Map<number, number>();
  for (const d of sorted) counts.set(d, (counts.get(d) ?? 0) + 1);
  if (category === 'YACHT') return counts.size === 1 ? sorted : null;
  if (category === 'LARGE_STRAIGHT') {
    return counts.size === 5 && sorted[4] - sorted[0] === 4 ? sorted : null;
  }
  if (category === 'FULL_HOUSE') {
    if (counts.size === 1) return sorted;
    if (counts.size !== 2 || ![...counts.values()].includes(3)) return null;
    return [...sorted].sort((a, b) => (counts.get(b) ?? 0) - (counts.get(a) ?? 0) || a - b);
  }
  return null;
}

/** D-208 — 굴림이 족보로 멈췄을 때의 효과 세기. */
export type MadeTier = 'light' | 'mid' | 'big';

/** D-208 — 멈춘 순간 알리는 족보와 세기. 순서가 우선순위다(겹치면 앞의 것 하나만). */
export const MADE_HANDS: ReadonlyArray<readonly [Category, MadeTier]> = [
  ['YACHT', 'big'],
  ['LARGE_STRAIGHT', 'mid'],
  ['FOUR_OF_A_KIND', 'mid'],
  ['FULL_HOUSE', 'light'],
  ['SMALL_STRAIGHT', 'light'],
];

export interface MadeHand {
  category: Category;
  tier: MadeTier;
  /** 족보를 이루는 주사위 자리(0~4, 오름차순) — 금빛 고리를 두를 곳. */
  indices: number[];
}

/**
 * D-208 — 그 족보를 이루는 주사위 자리. 점수 계산이 아니라 <b>어느 주사위를 빛낼지</b>만 고른다(족보인지는 서버 후보 점수가 정한다 —
 * `madeHand`). 주사위가 그 족보 모양이 아니면 null. 포 카드는 같은 눈 전부(넷 또는 다섯), 스몰 스트레이트는 이어진 네 눈을 하나씩.
 */
export function madeDice(category: Category, dice: readonly number[] | null | undefined): number[] | null {
  if (!dice || dice.length !== 5) return null;
  const all = [0, 1, 2, 3, 4];
  const counts = new Map<number, number>();
  for (const d of dice) counts.set(d, (counts.get(d) ?? 0) + 1);
  switch (category) {
    case 'YACHT':
      return counts.size === 1 ? all : null;
    case 'FOUR_OF_A_KIND': {
      const face = [...counts].find(([, n]) => n >= 4)?.[0];
      return face === undefined ? null : all.filter((i) => dice[i] === face);
    }
    case 'FULL_HOUSE':
      return counts.size === 1 || (counts.size === 2 && [...counts.values()].includes(3)) ? all : null;
    case 'LARGE_STRAIGHT':
      return counts.size === 5 && Math.max(...dice) - Math.min(...dice) === 4 ? all : null;
    case 'SMALL_STRAIGHT': {
      const start = [1, 2, 3].find((s) => [s, s + 1, s + 2, s + 3].every((f) => counts.has(f)));
      if (start === undefined) return null;
      return [start, start + 1, start + 2, start + 3].map((f) => dice.indexOf(f)).sort((a, b) => a - b);
    }
    default:
      return null;
  }
}

/**
 * D-208 — 방금 멈춘 굴림이 알릴 만한 족보인가. 서버가 준 후보 점수(`candidates` — 빈 칸만 들어 있다)에서 0점이 아닌 가장 높은 족보
 * 하나를 고른다. 이미 채운 칸은 후보에 없으므로 알리지 않는다.
 */
export function madeHand(
  dice: readonly number[] | null | undefined,
  candidates: Partial<Record<Category, number>>,
): MadeHand | null {
  for (const [category, tier] of MADE_HANDS) {
    if ((candidates[category] ?? 0) <= 0) continue;
    const indices = madeDice(category, dice);
    if (indices) return { category, tier, indices };
  }
  return null;
}
