import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { TableCueCall } from '@/features/sound/tableSfx';
import { omokRoomSink } from './omokRoomSink';
import {
  fiveOutcomeDelaySec,
  OTHERS_STONE_GAIN,
  QUIT_OUTCOME_DELAY_SEC,
  setOmokSfxSeat,
} from './omokSfx';
import type { OmokEndReason, OmokMove, OmokTableView } from '@/types/omok';

/**
 * D-267 — 오목 일반 동작 효과음. sink 에 이벤트를 넣고 어떤 소리를 청했는지 본다(합성·재생 규칙은 `tableSfx.test`).
 * 강하게 두기 "쿵"·5목 상승음·내 차례 알림음은 게임판이 이미 낸다 — 여기서는 겹치지 않는지만 본다.
 */
const played: TableCueCall[] = [];
vi.mock('@/features/sound/tableSfx', () => ({
  playCues: (cues: TableCueCall[]) => {
    played.push(...cues);
  },
}));

const TABLE: OmokTableView = {
  mode: 'SOLO',
  rule: 'RENJU',
  seatColors: ['BLACK', 'WHITE'],
  moves: [],
  turnSeat: 0,
  forbidden: [],
  timeouts: [0, 0],
  result: null,
};

const snapshot = (over: Partial<OmokTableView> = {}) => ({
  roomId: 'r1',
  phase: 'PLAYING',
  eventSeq: 1,
  tableView: { ...TABLE, ...over },
  privateHand: null,
  turnRemainingMs: null,
});

let seq = 1;
const send = (type: string, payload: unknown) => omokRoomSink.applyEvent({ type, seq: ++seq, payload });
const stone = (moveNumber: number, seat: number, over: Record<string, unknown> = {}) => ({
  seat,
  color: seat === 0 ? 'BLACK' : 'WHITE',
  moveNumber,
  x: moveNumber,
  y: 3,
  timedOut: false,
  ...over,
});
const ended = (reason: OmokEndReason, winnerSeats: number[], over: Record<string, unknown> = {}) => ({
  winnerColor: winnerSeats.length === 0 ? null : winnerSeats[0] === 0 ? 'BLACK' : 'WHITE',
  winnerSeats,
  reason,
  causeSeat: 0,
  winLine: [],
  totalMoves: 9,
  ...over,
});
const LINE = [0, 1, 2, 3, 4].map((x) => ({ x, y: 7 }));
const errorEnvelope = (code: string) => ({ eventId: 'e', type: 'ERROR', ts: 0, payload: { code, message: 'detail' } });

beforeEach(() => {
  played.length = 0;
  seq = 1;
  setOmokSfxSeat(0);
  omokRoomSink.reset('r1');
  omokRoomSink.applySnapshot(snapshot());
});

describe('오목 효과음 — 이벤트마다 맞는 소리', () => {
  it('착수는 "딱" — 내 수는 그대로, 남의 수는 작게', () => {
    send('STONE_PLACED', stone(1, 0));
    send('STONE_PLACED', stone(2, 1));
    expect(played).toEqual([
      { cue: 'stone.place', gain: 1 },
      { cue: 'stone.place', gain: OTHERS_STONE_GAIN },
    ]);
  });

  it('강하게 둔 수는 여기서 조용하다 — 기존 "쿵"(playSlam)만 울린다', () => {
    send('STONE_PLACED', stone(1, 1, { slam: true }));
    expect(played).toEqual([]);
  });

  it('5목 — 이긴 쪽은 기존 상승음(playFive)만, 진 쪽은 빛줄기 뒤에 패배음', () => {
    send('MATCH_ENDED', ended('FIVE', [0], { winLine: LINE }));
    expect(played).toEqual([]);

    omokRoomSink.reset('r1');
    omokRoomSink.applySnapshot(snapshot());
    send('MATCH_ENDED', ended('FIVE', [1], { winLine: LINE }));
    expect(played).toEqual([{ cue: 'match.lose', delaySec: fiveOutcomeDelaySec(LINE.length, false) }]);
  });

  it('진 쪽 소리는 상승음이 다 끝난 뒤 — 강하게 둔 수로 끝나면 더 늦고, 줄이 길면 더 늦다', () => {
    // 강하게 둔 5목: 0.7 + 4×0.14 + 꼬리 — 마지막 상승음(1.26초)보다 늦다(예전 고정 1.2초는 겹쳤다).
    expect(fiveOutcomeDelaySec(5, true)).toBeCloseTo(0.7 + 4 * 0.14 + 0.35);
    expect(fiveOutcomeDelaySec(5, true)).toBeGreaterThan(0.7 + 4 * 0.14);
    expect(fiveOutcomeDelaySec(6, false)).toBeGreaterThan(fiveOutcomeDelaySec(5, false));
  });

  it.each(['RESIGN', 'TIMEOUT', 'DESERTED'] as const)('%s — 낮은 소리 뒤에 승리음/패배음', (reason) => {
    send('MATCH_ENDED', ended(reason, [0]));
    expect(played).toEqual([
      { cue: 'match.resign' },
      { cue: 'match.win', delaySec: QUIT_OUTCOME_DELAY_SEC, gain: 1 },
    ]);

    played.length = 0;
    omokRoomSink.reset('r1');
    omokRoomSink.applySnapshot(snapshot());
    send('MATCH_ENDED', ended(reason, [1]));
    expect(played).toEqual([{ cue: 'match.resign' }, { cue: 'match.lose', delaySec: QUIT_OUTCOME_DELAY_SEC }]);
  });

  it.each(['BOARD_FULL', 'NO_LEGAL_MOVE'] as const)('%s 무승부는 무승부음 하나', (reason) => {
    send('MATCH_ENDED', ended(reason, []));
    expect(played).toEqual([{ cue: 'match.draw' }]);
  });

  it('2:2 — 같은 편이 이기면 나도 이긴 쪽이다', () => {
    setOmokSfxSeat(2);
    send('MATCH_ENDED', ended('RESIGN', [0, 2]));
    expect(played.map((c) => c.cue)).toEqual(['match.resign', 'match.win']);
  });

  it.each(['OCCUPIED', 'FORBIDDEN_MOVE', 'OUT_OF_BOARD', 'NOT_YOUR_TURN'])('거절 %s 는 낮은 "툭"', (code) => {
    omokRoomSink.applyPrivateEvent(errorEnvelope(code));
    expect(played).toEqual([{ cue: 'move.rejected' }]);
  });

  it.each(['BUSY', 'RATE_LIMITED', 'INTERNAL_ERROR', 'MATCH_OVER'])('인프라·종료 거절 %s 는 조용하다', (code) => {
    omokRoomSink.applyPrivateEvent(errorEnvelope(code));
    expect(played).toEqual([]);
  });
});

describe('오목 효과음 — 실제 이벤트에서만', () => {
  const moves: OmokMove[] = [
    { seat: 0, x: 7, y: 7, timedOut: false },
    { seat: 1, x: 8, y: 7, timedOut: false },
  ];

  it('resync 스냅샷·첫 상태는 소리를 내지 않는다(돌이 놓여 있어도, 끝난 판이어도)', () => {
    omokRoomSink.applySnapshot(snapshot({ moves }));
    omokRoomSink.applySnapshot(
      snapshot({
        moves,
        result: { winnerColor: 'WHITE', winnerSeats: [1], reason: 'RESIGN', causeSeat: 0, winLine: [] },
      }),
    );
    expect(played).toEqual([]);
  });

  it('이미 가진 수가 다시 오면 조용하다', () => {
    send('STONE_PLACED', stone(1, 0));
    send('STONE_PLACED', stone(1, 0));
    expect(played).toHaveLength(1);
  });

  it('빈 수가 있는 이벤트(스냅샷으로 돌린다)·차례 바뀜·연결 표시는 조용하다', () => {
    expect(send('STONE_PLACED', stone(3, 0))).toBe('unhandled');
    send('TURN_CHANGED', { seat: 0, color: 'BLACK', forbidden: [] });
    omokRoomSink.applyEvent({ type: 'PLAYER_DISCONNECTED', payload: { seat: 1 } });
    expect(played).toEqual([]);
  });

  it('끝난 판에 늦게 온 이벤트는 조용하다', () => {
    send('MATCH_ENDED', ended('RESIGN', [0]));
    played.length = 0;
    send('STONE_PLACED', stone(1, 1));
    send('MATCH_ENDED', ended('RESIGN', [0]));
    expect(played).toEqual([]);
  });
});

describe('오목 효과음 — 관전자', () => {
  beforeEach(() => setOmokSfxSeat(-1));

  it('관전자도 돌 소리를 듣는다(모두 남의 수 크기)', () => {
    send('STONE_PLACED', stone(1, 0));
    expect(played).toEqual([{ cue: 'stone.place', gain: OTHERS_STONE_GAIN }]);
  });

  it('관전자는 패배음이 없다 — 기권이면 낮은 소리 + 작은 승리음, 5목이면 기존 상승음만', () => {
    send('MATCH_ENDED', ended('RESIGN', [1]));
    expect(played).toEqual([
      { cue: 'match.resign' },
      { cue: 'match.win', delaySec: QUIT_OUTCOME_DELAY_SEC, gain: 0.7 },
    ]);

    played.length = 0;
    omokRoomSink.reset('r1');
    omokRoomSink.applySnapshot(snapshot());
    send('MATCH_ENDED', ended('FIVE', [1], { winLine: LINE }));
    expect(played).toEqual([]);
  });
});
