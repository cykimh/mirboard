import { beforeEach, describe, expect, it } from 'vitest';
import { useTichuStore } from './tichuStore';
import type { PrivateHand, TableView } from '@/types/tichu';

/**
 * D-108 — `roundHistory` 의 진실원 계약.
 *
 * 라이브 `ROUND_ENDED` 는 즉시 반응성을 위해 append 하고, `/resync` 는 서버 권위값으로
 * **통째로 교체**한다. 교체이지 append 가 아니라는 점이 핵심이다 — append 였다면 재접속
 * 때마다 같은 라운드가 중복으로 쌓인다.
 */

function baseTable(overrides: Partial<TableView> = {}): TableView {
  return {
    phase: 'PLAYING',
    dealingCardCount: 0,
    readySeats: [],
    passingSubmittedSeats: [],
    currentTurnSeat: 0,
    handCounts: { 0: 14, 1: 14, 2: 14, 3: 14 },
    currentTop: null,
    currentTopSeat: -1,
    declarations: { 0: 'NONE', 1: 'NONE', 2: 'NONE', 3: 'NONE' },
    roundScores: { A: 0, B: 0 },
    matchScores: { A: 0, B: 0 },
    roundNumber: 1,
    finishingOrder: [],
    activeWishRank: null,
    completedRounds: [],
    ...overrides,
  };
}

const emptyHand: PrivateHand = { seat: 0, cards: [] };

function resyncWith(table: TableView) {
  useTichuStore.getState().applySnapshot({
    tableView: table,
    privateHand: emptyHand,
  });
}

function roundEndedEvent(seq: number, teamAScore: number, teamBScore: number) {
  return {
    type: 'ROUND_ENDED',
    seq,
    payload: {
      score: { teamAScore, teamBScore, firstFinisherSeat: 0, doubleVictory: false },
    },
  };
}

describe('tichuStore roundHistory — resync 복원 (D-108)', () => {
  beforeEach(() => {
    useTichuStore.getState().reset('room-history');
  });

  it('resync 가 서버의 completedRounds 로 roundHistory 를 채운다', () => {
    resyncWith(
      baseTable({
        completedRounds: [
          { teamAScore: 300, teamBScore: 0, firstFinisherSeat: 0, doubleVictory: true },
          { teamAScore: 40, teamBScore: 60, firstFinisherSeat: 2, doubleVictory: false },
        ],
        matchScores: { A: 340, B: 60 },
        roundNumber: 3,
      }),
    );

    expect(useTichuStore.getState().roundHistory).toEqual([
      { teamAScore: 300, teamBScore: 0, firstFinisherSeat: 0, doubleVictory: true },
      { teamAScore: 40, teamBScore: 60, firstFinisherSeat: 2, doubleVictory: false },
    ]);
  });

  it('append 가 아니라 교체다 — 재접속을 반복해도 중복 누적되지 않는다', () => {
    const table = baseTable({
      completedRounds: [
        { teamAScore: 100, teamBScore: 0, firstFinisherSeat: 1, doubleVictory: false },
      ],
    });

    resyncWith(table);
    resyncWith(table);
    resyncWith(table);

    expect(useTichuStore.getState().roundHistory).toHaveLength(1);
  });

  it('라이브 append 뒤 resync 가 들어와도 그 라운드가 두 번 세지지 않는다', () => {
    // 라이브로 1라운드 종료를 받는다.
    useTichuStore.getState().applyEvent(roundEndedEvent(1, 100, 0));
    expect(useTichuStore.getState().roundHistory).toHaveLength(1);

    // 곧이어 resync — 서버도 같은 라운드 하나만 알고 있다.
    resyncWith(
      baseTable({
        completedRounds: [
          { teamAScore: 100, teamBScore: 0, firstFinisherSeat: 0, doubleVictory: false },
        ],
      }),
    );

    expect(useTichuStore.getState().roundHistory).toHaveLength(1);
  });

  it('라이브 append 는 그대로 살아 있다 — resync 없이도 즉시 반영', () => {
    useTichuStore.getState().applyEvent(roundEndedEvent(1, 100, 0));
    useTichuStore.getState().applyEvent(roundEndedEvent(2, 20, 80));

    expect(useTichuStore.getState().roundHistory).toEqual([
      { teamAScore: 100, teamBScore: 0, firstFinisherSeat: 0, doubleVictory: false },
      { teamAScore: 20, teamBScore: 80, firstFinisherSeat: 0, doubleVictory: false },
    ]);
  });

  it('completedRounds 가 없는 응답(구 서버)에도 깨지지 않는다', () => {
    const legacy = baseTable();
    delete (legacy as Partial<TableView>).completedRounds;

    resyncWith(legacy);

    expect(useTichuStore.getState().roundHistory).toEqual([]);
  });
});
