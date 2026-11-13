import type { TableCueCall } from '@/features/sound/tableSfx';
import type { OmokMatchEndedPayload, StonePlacedPayload } from '@/types/omok';
import type { OmokRoomState } from './omokStore';
import { OMOK_FX } from './omokTheme';

/** 남의 수는 조금 작게. */
export const OTHERS_STONE_GAIN = 0.6;
/** D-194 — 5목 연출의 박자와 시작 시각(게임판 `playFive`·CSS `--om-step`·`--om-t0` 과 같은 값). */
export const WIN_STEP_SEC = 0.14;
export const WIN_START_SEC = 0.35;
export const WIN_START_AFTER_SLAM_SEC = 0.7;
/** 상승음 마지막 음의 꼬리 — 진 쪽 소리는 이만큼 더 기다린다. */
export const FIVE_TAIL_SEC = 0.35;

/**
 * 5목 상승음(`playFive`)이 다 끝난 뒤의 시각 — 진 쪽 소리가 여기서 시작한다. 게임판과 같은 식으로 센다(강하게 둔 수로 끝났으면 늦게
 * 시작하고, 자유 룰의 6목 이상은 음이 더 많다). 고정 1.2초는 강하게 둔 5목에서 상승음과 겹쳤다(리뷰 지적).
 */
export function fiveOutcomeDelaySec(winLineLength: number, bySlam: boolean): number {
  const start = bySlam ? WIN_START_AFTER_SLAM_SEC : WIN_START_SEC;
  return start + Math.max(0, winLineLength - 1) * WIN_STEP_SEC + FIVE_TAIL_SEC;
}
/** 기권·시간패·나감의 낮은 소리 뒤에 승패 소리를 낸다. */
export const QUIT_OUTCOME_DELAY_SEC = 0.25;

/** 서버가 수를 거절한 사유 — 낮은 "툭". 락 경합 같은 인프라 거절은 조용하다. */
const REJECTED_MOVE = new Set(['OCCUPIED', 'FORBIDDEN_MOVE', 'OUT_OF_BOARD', 'NOT_YOUR_TURN']);

// 듣는 사람의 좌석. 오목은 비공개 상태가 없어 스토어가 내 좌석을 모른다 — 게임판이 방의 playerIds 로 정해 알려 준다(관전자 −1).
let listenerSeat = -1;
export function setOmokSfxSeat(seat: number) {
  listenerSeat = seat;
}
export function omokSfxSeat(): number {
  return listenerSeat;
}

/**
 * D-267 — 오목 공개 이벤트 → 효과음. 순수 함수다: 이벤트와 **적용 전후** 스토어 상태, 듣는 사람 좌석만 본다.
 *
 * <p>강하게 둔 수는 기존 "쿵"(`playSlam`, D-193)이 맡아 여기서는 조용하고, 5목 승리의 상승음도 기존 `playFive`(D-194)가 맡는다 —
 * 여기서는 진 쪽에만 그 뒤에 패배음을 낸다. 내 차례 알림음은 게임판의 `playChime`.
 */
export function omokCues(
  envelope: { type: string; seq?: number; payload: unknown },
  before: OmokRoomState,
  after: OmokRoomState,
  mySeat: number,
): TableCueCall[] {
  switch (envelope.type) {
    case 'STONE_PLACED': {
      if (after.moves.length <= before.moves.length) return []; // 이미 가진 수
      const p = envelope.payload as StonePlacedPayload;
      if (p.slam) return [];
      return [{ cue: 'stone.place', gain: p.seat === mySeat ? 1 : OTHERS_STONE_GAIN }];
    }
    case 'MATCH_ENDED': {
      if (before.result !== null || after.result === null) return [];
      const p = envelope.payload as OmokMatchEndedPayload;
      if (p.winnerSeats.length === 0) return [{ cue: 'match.draw' }];
      const five = p.reason === 'FIVE';
      const fiveTune = five && p.winLine.length >= 2 && OMOK_FX === 'rich';
      const cues: TableCueCall[] = [];
      if (!five) cues.push({ cue: 'match.resign' });
      const bySlam = after.slamMoves.has(after.moves.length) || after.pendingSlam;
      const delaySec = five ? (fiveTune ? fiveOutcomeDelaySec(p.winLine.length, bySlam) : 0) : QUIT_OUTCOME_DELAY_SEC;
      if (mySeat >= 0 && !p.winnerSeats.includes(mySeat)) {
        cues.push({ cue: 'match.lose', delaySec });
      } else if (!fiveTune) {
        // 이긴 쪽·관전자 — 5목이면 playFive 가 이미 울린다.
        cues.push({ cue: 'match.win', delaySec, gain: mySeat >= 0 ? 1 : 0.7 });
      }
      return cues;
    }
    default:
      return [];
  }
}

/** 비공개 큐의 `ERROR` 코드 → 효과음. */
export function omokErrorCues(code: string): TableCueCall[] {
  return REJECTED_MOVE.has(code) ? [{ cue: 'move.rejected' }] : [];
}
