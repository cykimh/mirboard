import type { Tier } from '@/api/users';

/**
 * D-266 — 계급표에 쓰는 숫자. 정본은 서버 `domain.game.scoring.Tier`(FLOORS·`withRank`) 한 곳이고, 여기는 그 거울이다 —
 * `tierTable.test.ts` 가 `Tier.java` 원문과 대조하므로 서버 숫자를 바꾸면 이 파일도 같이 고칠 것.
 */

/** 낮은 것부터 — 서버 enum 선언 순서와 같다. */
export const TIER_ORDER: readonly Tier[] = [
  'SKULL',
  'BULLET',
  'MISSILE',
  'BRONZE_STAR',
  'SILVER_STAR',
  'GOLD_STAR',
  'BRONZE_MEDAL',
  'SILVER_MEDAL',
  'GOLD_MEDAL',
  'BRONZE_BADGE',
  'SILVER_BADGE',
  'GOLD_BADGE',
  'BRONZE_CROWN',
  'SILVER_CROWN',
  'GOLD_CROWN',
  'STAR_CROWN',
];

/** 총알 ~ 금훈장이 시작하는 점수(서버 `FLOORS` 와 같은 순서). 해골은 첫 값 아래 전부. */
export const TIER_FLOORS: readonly number[] = [940, 1000, 1040, 1080, 1120, 1160, 1200, 1250, 1300, 1350, 1400];

/** 처음 점수 — 미사일의 시작과 같다. */
export const STARTING_RATING = 1000;

/** 왕관 넷 — 금훈장(1400점) 이상인 사람의 게임별 순위 범위(봇·게스트 제외). */
export const CROWN_RANKS: Readonly<Record<'STAR_CROWN' | 'GOLD_CROWN' | 'SILVER_CROWN' | 'BRONZE_CROWN', readonly [number, number]>> = {
  STAR_CROWN: [1, 1],
  GOLD_CROWN: [2, 3],
  SILVER_CROWN: [4, 10],
  BRONZE_CROWN: [11, 30],
};

function isCrown(tier: Tier): tier is keyof typeof CROWN_RANKS {
  return tier in CROWN_RANKS;
}

/** 그 계급이 되는 조건 한 줄(예: "1040점 이상", "금훈장 + 게임 순위 2~3위"). */
export function tierRequirement(tier: Tier): string {
  if (isCrown(tier)) {
    const [from, to] = CROWN_RANKS[tier];
    return `금훈장 + 게임 순위 ${from === to ? `${from}위` : `${from}~${to}위`}`;
  }
  const index = TIER_ORDER.indexOf(tier);
  if (index === 0) return `${TIER_FLOORS[0]}점 미만`;
  return `${TIER_FLOORS[index - 1]}점 이상`;
}
