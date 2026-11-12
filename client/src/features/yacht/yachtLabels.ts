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
