import { act } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { BUSY_RETRY_MAX, BUSY_RETRY_MS, canEditBoard, useStarSquareStore } from './starSquareStore';
import { starSquareRoomSink } from './starSquareRoomSink';
import { LO_SHU, LO_SHU_CLUES, seat, seedStore, view } from './starSquareTestFixtures';

const st = () => useStarSquareStore.getState();
const ev = (type: string, payload: unknown, seq = 2) => act(() => void starSquareRoomSink.applyEvent({ type, seq, payload }));
const err = (code: string) =>
  act(() => starSquareRoomSink.applyPrivateEvent({ eventId: 'e', type: 'ERROR', ts: 0, payload: { code, message: code } }));

/** `docs/rules-starsquare.md` §7·§11·§13 — 로컬 우선 판, 판 전체 SYNC·버전, 공개 이벤트 리듀서. */
describe('starSquareStore', () => {
  beforeEach(() => seedStore());

  it('스냅샷 — 공개 상태와 내 판(비공개 뷰)', () => {
    expect(st().phase).toBe('PLAYING');
    expect(st().board).toEqual(LO_SHU_CLUES);
    expect(st().selected).toBe(1); // 첫 빈 칸
    expect(canEditBoard(st())).toBe(true);
    // 관전자(비공개 뷰 없음)는 단서 판만 읽는다
    seedStore({}, null, -1);
    expect(st().board).toEqual(LO_SHU_CLUES);
    expect(canEditBoard(st())).toBe(false);
  });

  it('넣기·옮기기·지우기·되돌리기 — 단서 칸은 못 고른다', () => {
    act(() => {
      st().select(1);
      st().place(7);
      st().select(2);
      st().place(7); // 이미 쓴 7 → 이 칸으로 옮긴다
    });
    expect(st().board.slice(0, 3)).toEqual([2, 0, 7]);
    expect(st().sync.dirty).toBe(true);
    act(() => st().undo());
    expect(st().board.slice(0, 3)).toEqual([2, 7, 0]);
    act(() => {
      st().select(0); // 단서 — 고르지 못하고 그대로
    });
    expect(st().selected).toBe(2);
    act(() => {
      st().select(1);
      st().place(0);
    });
    expect(st().board[1]).toBe(0);
  });

  it('두 자리 수 — 둘째 자리는 첫 자리 입력을 물리고 한 번으로 친다', () => {
    seedStore({ size: 4, magicSum: 34, clues: Array(16).fill(0).map((_, i) => (i === 0 ? 16 : 0)) }, {
      puzzleIndex: 0,
      grid: Array(16).fill(0).map((_, i) => (i === 0 ? 16 : 0)),
      version: 0,
      solved: false,
    });
    let changed = false;
    act(() => {
      changed = st().place(1);
      st().place(12, changed);
    });
    expect(st().board[1]).toBe(12);
    expect(st().history).toHaveLength(1);
    // 리뷰 3 — 되돌리기는 첫 자리(1) 판이 아니라 그 전 판으로
    act(() => st().undo());
    expect(st().board[1]).toBe(0);
    expect(st().history).toHaveLength(0);
  });

  it('두 자리 수 — 그 칸에 이미 그 수가 있었으면 입력이 없던 것과 같다', () => {
    const grid = Array(16).fill(0).map((_, i) => (i === 0 ? 16 : i === 1 ? 12 : 0));
    seedStore({ size: 4, magicSum: 34, clues: Array(16).fill(0).map((_, i) => (i === 0 ? 16 : 0)) }, {
      puzzleIndex: 0,
      grid,
      version: 2,
      solved: false,
    });
    let changed = false;
    act(() => {
      changed = st().place(1);
      st().place(12, changed);
    });
    expect(st().board).toEqual(grid);
    expect(st().history).toHaveLength(0);
  });

  it('보낼 판 — 버전은 보낸 것·서버 것 중 큰 쪽 + 1, 보내면 dirty 가 내려간다', () => {
    act(() => {
      st().place(7);
    });
    const a = st().takeSync(1_000, false);
    expect(a).toEqual({ '@action': 'SYNC', puzzle: 0, version: 1, grid: [2, 7, 0, 0, 0, 1, 0, 0, 0] });
    expect(st().sync.dirty).toBe(false);
    expect(st().takeSync(1_001, false)).toBeNull(); // 바뀐 것이 없다
    act(() => {
      st().place(6);
    });
    expect(st().takeSync(1_002, false)?.version).toBe(2);
  });

  it('BUSY — 잠깐 뒤 다시(상한 넘으면 문구), 낡은 버전 — 다시 받아 버전 맞춤, 조용한 사유는 문구 없음', () => {
    act(() => {
      st().place(7);
    });
    st().takeSync(1_000, false);
    act(() => st().onSyncRejected('BUSY', 1_000));
    expect(st().sync.dirty).toBe(true);
    expect(st().takeSync(1_000 + BUSY_RETRY_MS - 1, false)).toBeNull();
    expect(st().takeSync(1_000 + BUSY_RETRY_MS, false)?.version).toBe(2);
    // 첫 BUSY 가 이미 한 번 — 상한까지는 문구 없이 다시
    for (let i = 0; i < BUSY_RETRY_MAX - 1; i++) act(() => st().onSyncRejected('BUSY', 2_000));
    expect(st().errorMessage).toBeNull();
    act(() => st().onSyncRejected('BUSY', 3_000));
    expect(st().errorMessage).toContain('서버가 바빠');

    const before = st().resyncNonce;
    err('STALE_SYNC');
    expect(st().resyncNonce).toBe(before + 1);
    err('PUZZLE_CLOSED');
    err('ALREADY_SOLVED');
    expect(st().errorMessage).toContain('서버가 바빠'); // 조용한 사유는 새 문구를 띄우지 않는다
    err('CLUE_CHANGED');
    expect(st().errorMessage).toContain('단서 칸은 바꿀 수 없어요');
  });

  it('재접속 스냅샷 — 같은 퍼즐에 보내지 않은 내 입력이 있으면 로컬이 이기고 버전만 서버보다 크게', () => {
    act(() => {
      st().place(7);
    });
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq: 5,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid: [2, 9, 0, 0, 0, 1, 0, 0, 0], version: 40, solved: false },
      }),
    );
    expect(st().board[1]).toBe(7);
    expect(st().takeSync(1, false)?.version).toBe(41);
    // 보낼 것이 없으면 서버 판을 받는다
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq: 6,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid: [2, 9, 0, 0, 0, 1, 0, 0, 0], version: 42, solved: false },
      }),
    );
    expect(st().board[1]).toBe(9);
  });

  it('보낸 판이 나가지 않았으면(소켓 끊김) 보내지 않은 것으로 되돌린다 — 완성 확인 시간 제한도', () => {
    act(() => {
      st().place(7);
    });
    st().takeSync(1_000, true);
    expect(st().sync.awaitingSolveSince).toBe(1_000);
    act(() => st().markUnsent());
    expect(st().sync.dirty).toBe(true);
    expect(st().sync.awaitingSolveSince).toBe(0);
    // 완성 확인 시간 제한 — 표시를 풀고 다시 보낸다
    st().takeSync(2_000, true);
    act(() => st().solveConfirmTimedOut());
    expect(st().sync.awaitingSolveSince).toBe(0);
    expect(st().sync.dirty).toBe(true);
  });

  /** 리뷰 1 — 보냈지만 서버가 확인하지 않은 판(죽은 소켓으로 나간 SYNC)은 다시 받은 낡은 서버 판에 덮이지 않는다. */
  it('보낸 판이 서버에 닿지 않았으면(받은 판의 버전이 못 미침) 로컬이 이기고 다시 보낸다', () => {
    act(() => {
      st().place(7);
    });
    expect(st().takeSync(1_000, false)?.version).toBe(1); // 보냈다 — dirty 가 내려간다
    expect(st().sync.dirty).toBe(false);
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq: 5,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid: LO_SHU_CLUES, version: 0, solved: false }, // 서버는 v1 을 못 받았다
      }),
    );
    expect(st().board[1]).toBe(7);
    expect(st().sync.dirty).toBe(true);
    expect(st().takeSync(1_001, false)).toMatchObject({ version: 2, grid: [2, 7, 0, 0, 0, 1, 0, 0, 0] });
    // 서버가 그 버전을 받았다고 알려 주면(같은 판) 그대로 받고 다시 보낼 것이 없다
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq: 6,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid: [2, 7, 0, 0, 0, 1, 0, 0, 0], version: 2, solved: false },
      }),
    );
    expect(st().board[1]).toBe(7);
    expect(st().sync.dirty).toBe(false);
    expect(st().sync.editVersion).toBe(0);
  });

  /** 리뷰 2 — 다시 보내야 하는 것(재연결·STALE)과 서버에 없는 내 입력을 나눈다. 입력이 없으면 다른 탭이 더 진행한 서버 판을 덮지 않는다. */
  it('입력 없이 다시 받으면 다른 탭이 더 진행한 서버 판을 받는다 — 재연결·STALE_SYNC 는 다시 보낼 것으로 켜지 않는다', () => {
    act(() => {
      st().place(7);
    });
    st().takeSync(1_000, false);
    // 서버가 v1 을 받았다(확인)
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq: 5,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid: [2, 7, 0, 0, 0, 1, 0, 0, 0], version: 1, solved: false },
      }),
    );
    // 서버가 모르는 내 입력이 없으면 되돌릴 것도 없다(재전송이 다른 탭 판을 덮지 않게)
    act(() => st().markUnsent());
    expect(st().sync.dirty).toBe(false);
    // 다른 탭이 더 진행해 이 탭의 다음 프레임이 낡았다 — STALE 은 다시 받기만 한다
    const before = st().resyncNonce;
    err('STALE_SYNC');
    expect(st().resyncNonce).toBe(before + 1);
    expect(st().sync.dirty).toBe(false);
    const other = [2, 7, 6, 9, 0, 1, 0, 0, 0];
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq: 9,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid: other, version: 4, solved: false },
      }),
    );
    expect(st().board).toEqual(other); // 다른 탭의 판
    expect(st().sync.dirty).toBe(false);
    expect(st().takeSync(2_000, false)).toBeNull(); // 덮어쓸 프레임이 나가지 않는다
    // 이 탭에서 새로 입력하면 그 입력이 이기고, 버전은 서버보다 크다
    act(() => {
      st().select(4);
      st().place(5);
    });
    expect(st().takeSync(2_001, false)).toMatchObject({ version: 5, grid: [2, 7, 6, 9, 5, 1, 0, 0, 0] });
  });

  it('STALE_SYNC 뒤 받은 판이 거절된 프레임 버전 이상이면 서버 판, 그 사이 새 입력이 있으면 로컬', () => {
    act(() => {
      st().place(7);
    });
    st().takeSync(1_000, false); // v1 — 다른 탭이 이미 v3 까지 썼다
    err('STALE_SYNC');
    act(() => {
      st().select(2);
      st().place(6); // 다시 받기 전에 새 입력
    });
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq: 9,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid: [2, 9, 0, 0, 0, 1, 0, 0, 0], version: 3, solved: false },
      }),
    );
    expect(st().board.slice(0, 3)).toEqual([2, 7, 6]);
    expect(st().takeSync(1_001, false)?.version).toBe(4);
  });

  it('BAD_GRID 같은 어긋남은 거절된 판이 다시 이기지 않는다 — 서버 판으로 맞춘다', () => {
    act(() => {
      st().place(7);
    });
    st().takeSync(1_000, false);
    err('BAD_GRID');
    act(() =>
      st().applySnapshot({
        roomId: 'r1',
        phase: 'PLAYING',
        eventSeq: 9,
        tableView: view(),
        privateHand: { puzzleIndex: 0, grid: LO_SHU_CLUES, version: 0, solved: false },
      }),
    );
    expect(st().board).toEqual(LO_SHU_CLUES);
    expect(st().sync.dirty).toBe(false);
  });

  it('공개 이벤트 — 시작·진행도·완성·마감·끝·탈주·매치 끝', () => {
    ev('PROGRESS', { seat: 1, filled: 5, lines: 1 });
    expect(st().seats[1]).toMatchObject({ filled: 5, lines: 1 });
    ev('SOLVED', { seat: 1, place: 1, elapsedMs: 40_000 });
    expect(st().seats[1]).toMatchObject({ solved: true, solvePlace: 1, filled: 9, lines: 8 });
    expect(st().mySolved).toBe(false);
    ev('CLOSING', { remainingMs: 30_000 });
    expect(st().closing).toBe(true);
    ev('SOLVED', { seat: 0, place: 2, elapsedMs: 50_000 });
    expect(st().mySolved).toBe(true);
    expect(canEditBoard(st())).toBe(false);
    ev('PUZZLE_ENDED', {
      index: 0,
      reason: 'ALL_SOLVED',
      results: [
        { seat: 0, rank: 2, points: 0, solved: true, elapsedMs: 50_000, filled: 9, lines: 8, deserted: false },
        { seat: 1, rank: 1, points: 1, solved: true, elapsedMs: 40_000, filled: 9, lines: 8, deserted: false },
      ],
      grids: [LO_SHU, LO_SHU],
      solution: LO_SHU,
      totals: [0, 1],
      nextInMs: 8_000,
    });
    expect(st().phase).toBe('RESULT');
    expect(st().seats.map((s) => [s.points, s.firsts])).toEqual([[0, 0], [1, 1]]);
    expect(st().lastPuzzle?.reason).toBe('ALL_SOLVED');
    // 다음 퍼즐 — 판·버전이 새로
    ev('PUZZLE_STARTED', { index: 1, count: 3, size: 3, magicSum: 15, clues: LO_SHU_CLUES, limitMs: 60_000, filled: 2, lines: 0 });
    expect(st().phase).toBe('PLAYING');
    expect(st().board).toEqual(LO_SHU_CLUES);
    expect(st().mySolved).toBe(false);
    expect(st().seats[1]).toMatchObject({ solved: false, filled: 2, points: 1 });
    // 같은 퍼즐 시작이 한 번 더 와도 내 판을 지우지 않는다
    act(() => {
      st().place(7);
    });
    ev('PUZZLE_STARTED', { index: 1, count: 3, size: 3, magicSum: 15, clues: LO_SHU_CLUES, limitMs: 60_000, filled: 2, lines: 0 });
    expect(st().board[1]).toBe(7);
    ev('SEAT_DESERTED', { seat: 1 });
    expect(st().seats[1].deserted).toBe(true);
    ev('MATCH_ENDED', { reason: 'LAST_SEAT', placements: [{ seat: 0, rank: 1, points: 0, firsts: 0, deserted: false }] });
    expect(st().phase).toBe('ENDED');
    expect(st().result?.reason).toBe('LAST_SEAT');
    expect(canEditBoard(st())).toBe(false);
  });

  it('준비 단계는 판이 없다', () => {
    seedStore({ phase: 'INTRO', size: 0, magicSum: 0, clues: null, seats: [seat(0), seat(1)] }, null);
    expect(st().board).toEqual([]);
    expect(canEditBoard(st())).toBe(false);
  });
});
