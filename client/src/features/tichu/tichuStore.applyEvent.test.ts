import { beforeEach, describe, expect, it } from 'vitest';
import { useTichuStore } from './tichuStore';
import type { Hand, TableView } from '@/types/tichu';

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
    ...overrides,
  };
}

function loadTable(table: TableView, lastSeq = 0) {
  useTichuStore.setState({
    tableView: table,
    lastSeq,
  });
}

describe('tichuStore.applyEvent — Phase 5d patch reducers', () => {
  beforeEach(() => {
    useTichuStore.getState().reset('room-patch');
  });

  it('dedups events with seq <= lastSeq', () => {
    loadTable(baseTable(), 10);
    const r = useTichuStore.getState().applyEvent({
      type: 'TURN_CHANGED',
      seq: 10,
      payload: { currentTurnSeat: 2 },
    });
    expect(r).toBe('duplicate');
    expect(useTichuStore.getState().tableView!.currentTurnSeat).toBe(0);
  });

  it('detects gaps for resync fallback', () => {
    loadTable(baseTable(), 5);
    const r = useTichuStore.getState().applyEvent({
      type: 'TURN_CHANGED',
      seq: 7,
      payload: { currentTurnSeat: 2 },
    });
    expect(r).toBe('gap');
  });

  it('PLAYED reduces handCount and updates currentTop', () => {
    loadTable(baseTable(), 1);
    const playedHand: Hand = {
      type: 'PAIR',
      cards: [
        { suit: 'JADE', rank: 5, special: null },
        { suit: 'SWORD', rank: 5, special: null },
      ],
      rank: 5,
      length: 2,
    };
    const r = useTichuStore.getState().applyEvent({
      type: 'PLAYED',
      seq: 2,
      payload: { seat: 1, hand: playedHand },
    });
    expect(r).toBe('applied');
    const table = useTichuStore.getState().tableView!;
    expect(table.handCounts[1]).toBe(12);
    expect(table.currentTopSeat).toBe(1);
    expect(table.currentTop?.type).toBe('PAIR');
    expect(useTichuStore.getState().lastSeq).toBe(2);
  });

  it('TURN_CHANGED updates currentTurnSeat', () => {
    loadTable(baseTable(), 1);
    useTichuStore.getState().applyEvent({
      type: 'TURN_CHANGED',
      seq: 2,
      payload: { currentTurnSeat: 3 },
    });
    expect(useTichuStore.getState().tableView!.currentTurnSeat).toBe(3);
  });

  it('TRICK_TAKEN clears top and adds points to team', () => {
    loadTable(
      baseTable({
        currentTop: { type: 'SINGLE', cards: [], rank: 7, length: 1 },
        currentTopSeat: 2,
      }),
      1,
    );
    useTichuStore.getState().applyEvent({
      type: 'TRICK_TAKEN',
      seq: 2,
      payload: { takerSeat: 2, trickPoints: 15 },
    });
    const table = useTichuStore.getState().tableView!;
    expect(table.currentTop).toBeNull();
    expect(table.currentTopSeat).toBe(-1);
    expect(table.roundScores.A).toBe(15);
    expect(table.roundScores.B).toBe(0);
  });

  it('PLAYER_FINISHED appends to finishingOrder and zeros handCount', () => {
    loadTable(baseTable({ handCounts: { 0: 0, 1: 14, 2: 14, 3: 14 } }), 1);
    useTichuStore.getState().applyEvent({
      type: 'PLAYER_FINISHED',
      seq: 2,
      payload: { seat: 0, order: 1 },
    });
    const table = useTichuStore.getState().tableView!;
    expect(table.finishingOrder).toEqual([0]);
    expect(table.handCounts[0]).toBe(0);
  });

  it('TICHU_DECLARED records declaration', () => {
    loadTable(baseTable(), 1);
    useTichuStore.getState().applyEvent({
      type: 'TICHU_DECLARED',
      seq: 2,
      payload: { seat: 1, kind: 'GRAND_TICHU' },
    });
    expect(useTichuStore.getState().tableView!.declarations[1]).toBe('GRAND_TICHU');
  });

  it('PLAYER_READY adds seat in sorted order, idempotent', () => {
    loadTable(baseTable({ phase: 'DEALING', dealingCardCount: 8 }), 1);
    useTichuStore.getState().applyEvent({
      type: 'PLAYER_READY',
      seq: 2,
      payload: { seat: 2 },
    });
    useTichuStore.getState().applyEvent({
      type: 'PLAYER_READY',
      seq: 3,
      payload: { seat: 0 },
    });
    expect(useTichuStore.getState().tableView!.readySeats).toEqual([0, 2]);
  });

  it('PASSING_SUBMITTED adds seat to submitted list', () => {
    loadTable(baseTable({ phase: 'PASSING' }), 1);
    useTichuStore.getState().applyEvent({
      type: 'PASSING_SUBMITTED',
      seq: 2,
      payload: { seat: 1 },
    });
    expect(useTichuStore.getState().tableView!.passingSubmittedSeats).toEqual([1]);
  });

  it('WISH_MADE sets activeWishRank', () => {
    loadTable(baseTable(), 1);
    useTichuStore.getState().applyEvent({
      type: 'WISH_MADE',
      seq: 2,
      payload: { rank: 7 },
    });
    expect(useTichuStore.getState().tableView!.activeWishRank).toBe(7);
  });

  it('TRICK_TAKEN preserves activeWishRank (wish 는 라운드 전체 유지)', () => {
    loadTable(baseTable({ activeWishRank: 5 }), 1);
    useTichuStore.getState().applyEvent({
      type: 'TRICK_TAKEN',
      seq: 2,
      payload: { takerSeat: 1, trickPoints: 10 },
    });
    expect(useTichuStore.getState().tableView!.activeWishRank).toBe(5);
    expect(useTichuStore.getState().tableView!.roundScores.B).toBe(10);
  });

  it('DRAGON_GIVEN 은 seq 만 진행 — 점수 패치는 동반 TRICK_TAKEN 이 처리', () => {
    loadTable(baseTable(), 1);
    const r = useTichuStore.getState().applyEvent({
      type: 'DRAGON_GIVEN',
      seq: 2,
      payload: { fromSeat: 0, toSeat: 1 },
    });
    expect(r).toBe('applied');
    expect(useTichuStore.getState().lastSeq).toBe(2);
    // tableView 자체는 동일.
    expect(useTichuStore.getState().tableView!.roundScores.A).toBe(0);
    expect(useTichuStore.getState().tableView!.roundScores.B).toBe(0);
  });

  it('ROUND_ENDED sets banner state and advances seq', () => {
    loadTable(baseTable(), 1);
    useTichuStore.getState().applyEvent({
      type: 'ROUND_ENDED',
      seq: 2,
      payload: { score: { teamAScore: 120, teamBScore: 80, firstFinisherSeat: 0 } },
    });
    expect(useTichuStore.getState().roundEnded?.teamAScore).toBe(120);
    expect(useTichuStore.getState().lastSeq).toBe(2);
  });

  it('MATCH_ENDED sets banner state', () => {
    loadTable(baseTable(), 1);
    useTichuStore.getState().applyEvent({
      type: 'MATCH_ENDED',
      seq: 2,
      payload: { winningTeam: 'A', finalScores: { A: 1100, B: 600 }, roundsPlayed: 3 },
    });
    expect(useTichuStore.getState().matchEnded?.winningTeam).toBe('A');
    expect(useTichuStore.getState().matchEnded?.roundsPlayed).toBe(3);
  });

  it('lifecycle events (DEALING_PHASE_STARTED, etc.) return unhandled for resync fallback', () => {
    loadTable(baseTable(), 1);
    const r = useTichuStore.getState().applyEvent({
      type: 'DEALING_PHASE_STARTED',
      seq: 2,
      payload: { phaseCardCount: 14 },
    });
    expect(r).toBe('unhandled');
    // store 는 변경되지 않음.
    expect(useTichuStore.getState().lastSeq).toBe(1);
  });

  it('unknown event types return unhandled', () => {
    loadTable(baseTable(), 1);
    const r = useTichuStore.getState().applyEvent({
      type: 'TOTALLY_NEW_EVENT',
      seq: 2,
      payload: {},
    });
    expect(r).toBe('unhandled');
  });
});
