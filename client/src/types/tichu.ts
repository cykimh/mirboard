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

export interface TableView {
  currentTurnSeat: number;
  handCounts: Record<string, number>;
  currentTop: Hand | null;
  currentTopSeat: number;
  declarations: Record<string, TichuDeclaration>;
  roundScores: { A?: number; B?: number };
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
