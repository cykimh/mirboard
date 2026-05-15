// 서버 `domain.game.tichu.*` 의 JSON 직렬화 형식과 일치.

export type Suit = 'JADE' | 'SWORD' | 'PAGODA' | 'STAR';
export type Special = 'MAHJONG' | 'DOG' | 'PHOENIX' | 'DRAGON';

export interface Card {
  suit: Suit | null;
  rank: number;
  special: Special | null;
}

export type HandType =
  | 'SINGLE'
  | 'PAIR'
  | 'TRIPLE'
  | 'FULL_HOUSE'
  | 'STRAIGHT'
  | 'CONSECUTIVE_PAIRS'
  | 'BOMB'
  | 'STRAIGHT_FLUSH_BOMB';

export interface Hand {
  type: HandType;
  cards: Card[];
  rank: number;
  length: number;
  phoenixSingle?: boolean;
}

export type TichuDeclaration = 'NONE' | 'TICHU' | 'GRAND_TICHU';

export type GamePhase = 'DEALING' | 'PASSING' | 'PLAYING' | 'ROUND_END';

export interface TableView {
  phase: GamePhase;
  dealingCardCount: number;
  readySeats: number[];
  passingSubmittedSeats: number[];
  currentTurnSeat: number;
  handCounts: Record<string, number>;
  currentTop: Hand | null;
  currentTopSeat: number;
  declarations: Record<string, TichuDeclaration>;
  roundScores: { A?: number; B?: number };
  matchScores: { A?: number; B?: number };
  roundNumber: number;
  finishingOrder: number[];
  activeWishRank: number | null;
}

export interface PrivateHand {
  seat: number;
  cards: Card[];
}

export type TichuActionType =
  | 'PLAY_CARD'
  | 'PASS_TRICK'
  | 'DECLARE_TICHU'
  | 'DECLARE_GRAND_TICHU'
  | 'READY'
  | 'PASS_CARDS'
  | 'MAKE_WISH'
  | 'GIVE_DRAGON_TRICK';

export interface ResyncResponse {
  roomId: string;
  phase: string;
  eventSeq: number;
  tableView: TableView;
  privateHand: PrivateHand;
}

/** 카드 비교/표시용 안정 키. */
export function cardKey(c: Card): string {
  return c.special !== null ? `S-${c.special}` : `N-${c.suit}-${c.rank}`;
}

export function cardLabel(c: Card): string {
  if (c.special) {
    switch (c.special) {
      case 'MAHJONG':
        return '1';
      case 'DOG':
        return '🐕';
      case 'PHOENIX':
        return '🔥';
      case 'DRAGON':
        return '🐉';
    }
  }
  if (c.rank === 11) return 'J';
  if (c.rank === 12) return 'Q';
  if (c.rank === 13) return 'K';
  if (c.rank === 14) return 'A';
  return String(c.rank);
}

/**
 * Phase 8F — 카드 → 정적 이미지 URL 매핑. 자산은 `client/public/cards/` 에 있을
 * 때 `/cards/...` 로 접근 (Vite 가 public 을 root 로 매핑). 자산 없으면 CardChip
 * 가 onError 로 텍스트 글리프 fallback.
 *
 * 명명 규칙은 `docs/assets/card-prompts.md` 참고.
 */
export function cardAssetSrc(c: Card): string {
  if (c.special) {
    return `/cards/${c.special.toLowerCase()}.svg`;
  }
  if (!c.suit) return '/cards/back.svg';  // 안전망 — suit 없는 일반 카드는 없음.
  const rankVisual =
    c.rank === 11 ? 'J' : c.rank === 12 ? 'Q' : c.rank === 13 ? 'K' : c.rank === 14 ? 'A' : String(c.rank);
  return `/cards/${c.suit.toLowerCase()}-${rankVisual}.svg`;
}
