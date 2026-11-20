import type { TableCueCall } from '@/features/sound/tableSfx';
import type { DambadukMatchEndedPayload, SeatOutPayload, StoneMovedPayload, StonePlacedPayload } from '@/types/dambaduk';
import type { DambadukRoomState } from './dambadukStore';

/** 남의 수는 조금 작게. */
export const OTHERS_GAIN = 0.6;
/** 담이 쌓이는 소리는 돌이 닿은 뒤(CSS 돌 미끄러짐 `--db-slide` 와 같은 값, 초). */
export const WALL_AFTER_SLIDE_SEC = 0.22;

/** 서버가 수를 거절한 사유 — 낮은 "툭". 락 경합 같은 인프라 거절은 조용하다. */
const REJECTED_MOVE = new Set([
  'NOT_YOUR_TURN',
  'WRONG_PHASE',
  'OUT_OF_BOARD',
  'NO_STONE_LEFT',
  'OCCUPIED',
  'NOT_YOUR_STONE',
  'BAD_PATH',
  'JUMP_UNAVAILABLE',
  'PATH_BLOCKED',
  'WALL_IN_PATH',
  'JUMP_WITHOUT_WALL',
  'WALL_SIDE_TAKEN',
]);

// 듣는 사람의 좌석. 담바둑은 비공개 상태가 없어 스토어가 내 좌석을 모른다 — 게임판이 방의 playerIds 로 정해 알려 준다(관전자 −1).
let listenerSeat = -1;
export function setDambadukSfxSeat(seat: number) {
  listenerSeat = seat;
}
export function dambadukSfxSeat(): number {
  return listenerSeat;
}

/**
 * D-267 — 담바둑 공개 이벤트 → 효과음. 순수 함수다: 이벤트와 **적용 전후** 스토어 상태, 듣는 사람 좌석만 본다. 내 차례 알림음은
 * 게임판의 `playChime`.
 */
export function dambadukCues(
  envelope: { type: string; seq?: number; payload: unknown },
  before: DambadukRoomState,
  after: DambadukRoomState,
  mySeat: number,
): TableCueCall[] {
  switch (envelope.type) {
    case 'STONE_PLACED': {
      if (after.fxSeq === before.fxSeq) return [];
      const p = envelope.payload as StonePlacedPayload;
      return [{ cue: 'stone.place', gain: p.seat === mySeat ? 1 : OTHERS_GAIN }];
    }
    case 'STONE_MOVED': {
      if (after.fxSeq === before.fxSeq) return [];
      const p = envelope.payload as StoneMovedPayload;
      const gain = p.seat === mySeat ? 1 : OTHERS_GAIN;
      return [
        { cue: 'stone.slide', gain },
        { cue: 'wall.build', gain, delaySec: WALL_AFTER_SLIDE_SEC },
      ];
    }
    case 'SEAT_OUT': {
      if (after.exits.length === before.exits.length) return [];
      const p = envelope.payload as SeatOutPayload;
      return [{ cue: 'seat.out', gain: p.seat === mySeat ? 1 : 0.8 }];
    }
    case 'MATCH_ENDED': {
      if (before.result !== null || after.result === null) return [];
      const p = envelope.payload as DambadukMatchEndedPayload;
      if (p.draw) return [{ cue: 'match.draw' }];
      if (mySeat >= 0 && !p.winners.includes(mySeat)) return [{ cue: 'match.lose', delaySec: 0.3 }];
      return [{ cue: 'match.win', delaySec: 0.3, gain: mySeat >= 0 ? 1 : 0.7 }];
    }
    default:
      return [];
  }
}

/** 비공개 큐의 `ERROR` 코드 → 효과음. */
export function dambadukErrorCues(code: string): TableCueCall[] {
  return REJECTED_MOVE.has(code) ? [{ cue: 'move.rejected' }] : [];
}
