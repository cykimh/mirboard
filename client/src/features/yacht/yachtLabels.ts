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

/** 주사위 눈 글리프(보조기기는 숫자를 읽는다). */
export const DIE_FACE = ['', '⚀', '⚁', '⚂', '⚃', '⚄', '⚅'];

/** 큰 칸 — 기록하면 짧은 축하 효과. */
export const BIG_CATEGORIES: ReadonlySet<Category> = new Set(['YACHT', 'LARGE_STRAIGHT']);
