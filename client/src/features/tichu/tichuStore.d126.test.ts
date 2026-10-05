import { beforeEach, describe, expect, it } from 'vitest';
import { useTichuStore } from './tichuStore';
import type { CompletedRound, PrivateHand, TableView } from '@/types/tichu';

/**
 * D-126 — 매 플레이 resync 가 우연히 고쳐 주던 화면 상태를 리듀서로 옮긴 계약.
 * (예전에는 비공개 `HAND_DEALT` 가 공개 순번을 써서 카드를 낼 때마다 전원 resync 했다.)
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

const ROUND_1: CompletedRound = {
  teamAScore: 100,
  teamBScore: 0,
  firstFinisherSeat: 0,
  doubleVictory: false,
};
const emptyHand: PrivateHand = { seat: 0, cards: [] };

const apply = (type: string, payload: unknown) =>
  useTichuStore.getState().applyEvent({ type, payload });

describe('tichuStore — resync 에 기대던 화면 상태 (D-126)', () => {
  beforeEach(() => {
    useTichuStore.getState().reset('room-d126');
  });

  it('WISH_CLEARED 는 소원 표시를 지운다', () => {
    useTichuStore.setState({ tableView: baseTable({ activeWishRank: 7 }) });

    expect(apply('WISH_CLEARED', { rank: 7 })).toBe('applied');
    expect(useTichuStore.getState().tableView!.activeWishRank).toBeNull();
  });

  it('ROUND_ENDED 는 공개 뷰를 서버 ROUND_END 뷰와 같게 만든다', () => {
    useTichuStore.setState({
      tableView: baseTable({
        currentTurnSeat: 2,
        currentTop: { type: 'SINGLE', cards: [], rank: 9, length: 1 },
        currentTopSeat: 2,
        activeWishRank: 4,
        roundScores: { A: 35, B: 65 },
      }),
    });

    apply('ROUND_ENDED', {
      score: { teamAScore: 235, teamBScore: -135, firstFinisherSeat: 2, doubleVictory: false },
    });

    const t = useTichuStore.getState().tableView!;
    expect(t.phase).toBe('ROUND_END');
    expect(t.currentTurnSeat).toBe(-1);
    expect(t.currentTop).toBeNull();
    expect(t.currentTopSeat).toBe(-1);
    expect(t.activeWishRank).toBeNull();
    expect(t.roundScores).toEqual({ A: 235, B: -135 });
  });

  it('MATCH_ENDED 는 누적 점수를 최종 점수로 맞춘다', () => {
    useTichuStore.setState({ tableView: baseTable({ matchScores: { A: 700, B: 800 } }) });

    apply('MATCH_ENDED', { winningTeam: 'B', finalScores: { A: 900, B: 1050 }, roundsPlayed: 6 });

    expect(useTichuStore.getState().tableView!.matchScores).toEqual({ A: 900, B: 1050 });
  });

  it('차례가 넘어가면 지난 에러 문구를 지운다', () => {
    useTichuStore.setState({ tableView: baseTable(), errorMessage: 'NOT_YOUR_TURN: ...' });

    apply('TURN_CHANGED', { currentTurnSeat: 1 });

    expect(useTichuStore.getState().errorMessage).toBeNull();
  });

  it('같은 매치 안의 resync(다음 라운드 시작)는 라운드 종료 표시를 지우지 않는다', () => {
    useTichuStore.setState({ tableView: baseTable() });
    apply('ROUND_ENDED', {
      score: { teamAScore: 100, teamBScore: 0, firstFinisherSeat: 0, doubleVictory: false },
    });

    useTichuStore.getState().applySnapshot({
      tableView: baseTable({ phase: 'DEALING', roundNumber: 2, completedRounds: [ROUND_1] }),
      privateHand: emptyHand,
    });

    expect(useTichuStore.getState().roundEnded?.teamAScore).toBe(100);
  });

  it('매치가 끝난 뒤의 resync(화면 복귀)는 종료 패널을 지우지 않는다', () => {
    useTichuStore.setState({ tableView: baseTable() });
    apply('MATCH_ENDED', { winningTeam: 'A', finalScores: { A: 1000, B: 0 }, roundsPlayed: 1 });

    useTichuStore.getState().applySnapshot({
      tableView: baseTable({ phase: 'ROUND_END', completedRounds: [ROUND_1] }),
      privateHand: emptyHand,
    });

    expect(useTichuStore.getState().matchEnded?.winningTeam).toBe('A');
  });

  it('새 매치(리매치)의 resync 는 라운드·매치 종료 표시를 지운다', () => {
    useTichuStore.setState({ tableView: baseTable() });
    apply('ROUND_ENDED', {
      score: { teamAScore: 100, teamBScore: 0, firstFinisherSeat: 0, doubleVictory: false },
    });
    apply('MATCH_ENDED', { winningTeam: 'A', finalScores: { A: 1000, B: 0 }, roundsPlayed: 1 });

    useTichuStore.getState().applySnapshot({
      tableView: baseTable({ phase: 'DEALING', completedRounds: [] }),
      privateHand: emptyHand,
    });

    expect(useTichuStore.getState().roundEnded).toBeNull();
    expect(useTichuStore.getState().matchEnded).toBeNull();
  });
});
