import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeAudioContext, audioLog } from '@/features/sound/testAudio';
import type { TableCue } from '@/features/sound/tableSfx';
import { RIVAL_SOLVED_GAIN, starSquareCues, starSquareLocalCues, type LocalBoardState } from './starSquareSfx';
import type { StarSquareRoomState } from './starSquareStore';

/** 별자리 수판 효과음(D-286) — 이벤트·내 입력 → 소리 표, 그리고 새 소리가 D-271(둥근 소리만)을 지키는지. */
const STAR_CUES: TableCue[] = [
  'star.place',
  'star.erase',
  'star.undo',
  'star.line',
  'star.complete',
  'star.solved',
  'star.closing',
  'star.open',
];

const CLOCK = { since: 0, remainingMs: 1000 };
const SEATS: StarSquareRoomState['seats'] = [];

function local(board: number[], history: number[][] = [], over: Partial<LocalBoardState> = {}): LocalBoardState {
  return { board, boardPuzzle: 0, history, size: 3, clock: CLOCK, seats: SEATS, phase: 'PLAYING', ...over };
}

const room = (over: Partial<StarSquareRoomState>) =>
  ({ mySeat: 0, phase: 'PLAYING', puzzleIndex: 0, closing: false, mySolved: false, result: null, ...over }) as StarSquareRoomState;

describe('starSquareLocalCues — 내 입력', () => {
  const start = [2, 0, 0, 0, 0, 1, 0, 0, 0];

  it('숫자 넣기는 star.place, 지우기는 star.erase, 되돌리기는 star.undo', () => {
    const placed = [2, 7, 0, 0, 0, 1, 0, 0, 0];
    expect(starSquareLocalCues(local(start), local(placed, [start]))).toEqual([{ cue: 'star.place' }]);
    expect(starSquareLocalCues(local(placed, [start]), local(start, [start, placed]))).toEqual([{ cue: 'star.erase' }]);
    expect(starSquareLocalCues(local(placed, [start]), local(start, []))).toEqual([{ cue: 'star.undo' }]);
  });

  it('맞은 줄이 새로 생기면 star.line 이 따라 울린다', () => {
    const before = [2, 7, 0, 0, 0, 1, 0, 0, 0];
    const after = [2, 7, 6, 0, 0, 1, 0, 0, 0];
    const cues = starSquareLocalCues(local(before), local(after, [before]));
    expect(cues.map((c) => c.cue)).toEqual(['star.place', 'star.line']);
    expect(cues[1].level).toBe(1);
    expect(cues[1].delaySec).toBeGreaterThan(0);
  });

  it('판이 완성처럼 보이면 줄 소리 대신 star.complete', () => {
    const before = [2, 7, 6, 9, 5, 1, 4, 3, 0];
    const after = [2, 7, 6, 9, 5, 1, 4, 3, 8];
    expect(starSquareLocalCues(local(before), local(after, [before])).map((c) => c.cue)).toEqual(['star.place', 'star.complete']);
  });

  it('스냅샷·퍼즐 시작처럼 시계·좌석·퍼즐이 같이 바뀐 변화는 조용하다', () => {
    const after = [2, 7, 0, 0, 0, 1, 0, 0, 0];
    expect(starSquareLocalCues(local(start), local(after, [], { clock: { ...CLOCK } }))).toEqual([]);
    expect(starSquareLocalCues(local(start), local(after, [], { seats: [] }))).toEqual([]);
    expect(starSquareLocalCues(local(start), local(after, [], { boardPuzzle: 1 }))).toEqual([]);
    expect(starSquareLocalCues(local(start), local(after, [], { phase: 'RESULT' }))).toEqual([]);
    const same = local(start);
    expect(starSquareLocalCues(same, { ...same })).toEqual([]);
  });
});

describe('starSquareCues — 공개 이벤트', () => {
  it('완성은 별똥별 — 내 것은 또렷하게, 남의 것은 작게', () => {
    const s = room({});
    expect(starSquareCues({ type: 'SOLVED', payload: { seat: 0 } }, s, s)).toEqual([{ cue: 'star.solved', gain: 1 }]);
    expect(starSquareCues({ type: 'SOLVED', payload: { seat: 1 } }, s, s)).toEqual([{ cue: 'star.solved', gain: RIVAL_SOLVED_GAIN }]);
  });

  it('마감 종은 한 번만, 이미 다 푼 사람에게는 울리지 않는다', () => {
    const e = { type: 'CLOSING', payload: {} };
    expect(starSquareCues(e, room({}), room({ closing: true })).map((c) => c.cue)).toEqual(['star.closing']);
    expect(starSquareCues(e, room({ closing: true }), room({ closing: true }))).toEqual([]);
    expect(starSquareCues(e, room({}), room({ closing: true, mySolved: true }))).toEqual([]);
  });

  it('퍼즐이 열리면 star.open — 같은 퍼즐의 늦은 프레임은 조용히', () => {
    const e = { type: 'PUZZLE_STARTED', payload: {} };
    expect(starSquareCues(e, room({ phase: 'INTRO' }), room({})).map((c) => c.cue)).toEqual(['star.open']);
    expect(starSquareCues(e, room({ phase: 'RESULT' }), room({ puzzleIndex: 1 })).map((c) => c.cue)).toEqual(['star.open']);
    expect(starSquareCues(e, room({}), room({}))).toEqual([]);
  });

  it('판 끝 — 내 순위로 이김·짐, 관전자는 조용히', () => {
    const result = { reason: 'FINISHED' as const, placements: [{ seat: 0, rank: 1, points: 5, firsts: 1, deserted: false }, { seat: 1, rank: 2, points: 3, firsts: 0, deserted: false }] };
    const e = { type: 'MATCH_ENDED', payload: {} };
    expect(starSquareCues(e, room({}), room({ result }))).toEqual([{ cue: 'match.win' }]);
    expect(starSquareCues(e, room({ mySeat: 1 }), room({ mySeat: 1, result }))).toEqual([{ cue: 'match.lose' }]);
    expect(starSquareCues(e, room({ mySeat: -1 }), room({ mySeat: -1, result }))).toEqual([]);
  });
});

describe('별자리 수판 소리 — D-271', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000);
    audioLog.reset();
    window.localStorage.removeItem('mirboard.sfxMuted');
    vi.stubGlobal('AudioContext', FakeAudioContext);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('둥근 소리(sine·triangle)만, 잡음 없이, 짧고 작게', async () => {
    vi.resetModules();
    const sfx = await import('@/features/sound/tableSfx');
    for (const cue of STAR_CUES) {
      sfx.resetTableSfx();
      expect(sfx.playTableSfx(cue, { level: 3 }), cue).toBe(true);
    }
    expect(audioLog.noises).toHaveLength(0);
    for (const t of audioLog.tones) {
      expect(['sine', 'triangle']).toContain(t.type);
      expect(t.stop - 10).toBeLessThan(1.1);
      expect(t.peak).toBeGreaterThan(0);
      expect(t.peak).toBeLessThan(0.3);
    }
  });
});
