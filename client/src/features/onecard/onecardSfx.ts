import type { TableCueCall } from '@/features/sound/tableSfx';
import type {
  CardPlayedPayload,
  CardsDrawnPayload,
  MatchEndedPayload,
  RaceResolvedPayload,
} from '@/types/onecard';
import { isAttack, isSuitChange } from './onecardRules';
import type { OneCardRoomState } from './onecardStore';
import { specialEffectOf } from './useOneCardFx';

/** 남의 동작은 조금 작게. */
export const OTHERS_GAIN = 0.8;

/** 공격 누적 → 소리 세기 1~3 (2·A 한 장 / 여러 장 겹침 / 조커급). */
export function attackSfxLevel(attackStack: number): number {
  if (attackStack >= 8) return 3;
  return attackStack >= 4 ? 2 : 1;
}

/**
 * D-267 — 원카드 공개 이벤트 → 효과음. 순수 함수다: 이벤트와 **적용 전후** 스토어 상태만 본다.
 *
 * <p>실제 이벤트만 소리를 낸다 — 순번 없는 이벤트는 내지 않고, 같은 이벤트가 다시 와도(스토어가 `lastPlay`·`lastDraw` 를 그대로
 * 둔다) 내지 않는다. 내 차례 알림음은 게임판의 `playChime` 이 맡는다(여기서 `TURN_CHANGED` 는 조용하다). 경쟁 창은 짧은 신호만 —
 * "원카드!" 목소리는 따로 얹힌다(D-265).
 */
export function onecardCues(
  envelope: { type: string; seq?: number; payload: unknown },
  before: OneCardRoomState,
  after: OneCardRoomState,
): TableCueCall[] {
  if (envelope.seq === undefined) return [];
  const gainOf = (seat: number) => (seat === after.mySeat ? 1 : OTHERS_GAIN);

  switch (envelope.type) {
    case 'CARD_PLAYED': {
      if (after.lastPlay === before.lastPlay) return [];
      const p = envelope.payload as CardPlayedPayload;
      const gain = gainOf(p.seat);
      if (isAttack(p.card)) return [{ cue: 'card.attack', level: attackSfxLevel(p.attackStack), gain }];
      if (isSuitChange(p.card)) return [{ cue: 'card.suit', gain }];
      const alive = before.seats.filter((s) => s.eliminated === null).length;
      const effect = specialEffectOf(p.card, alive);
      if (effect) return [{ cue: `card.${effect}`, gain }];
      return [{ cue: 'card.play', gain }];
    }
    case 'CARDS_DRAWN': {
      if (after.lastDraw === before.lastDraw) return [];
      const p = envelope.payload as CardsDrawnPayload;
      return [{ cue: 'card.draw', count: p.count, gain: gainOf(p.seat) }];
    }
    case 'PILE_RESHUFFLED':
      return [{ cue: 'deck.shuffle' }];
    case 'RACE_OPENED':
      return [{ cue: 'race.open' }];
    case 'RACE_RESOLVED': {
      const { outcome } = envelope.payload as RaceResolvedPayload;
      if (outcome === 'CALLED') return [{ cue: 'race.called' }];
      if (outcome === 'CAUGHT') return [{ cue: 'race.caught' }];
      return []; // 만료·취소는 조용히
    }
    case 'PLAYER_ELIMINATED':
      return [{ cue: 'seat.out' }];
    case 'MATCH_ENDED': {
      if (before.result !== null) return [];
      const p = envelope.payload as MatchEndedPayload;
      // 관전자는 누군가의 우승을 작게 듣는다.
      if (after.mySeat < 0) return [{ cue: 'match.win', gain: OTHERS_GAIN }];
      const mine = p.standings.find((s) => s.seat === after.mySeat);
      return [{ cue: mine?.rank === 1 ? 'match.win' : 'match.lose' }];
    }
    default:
      return [];
  }
}
