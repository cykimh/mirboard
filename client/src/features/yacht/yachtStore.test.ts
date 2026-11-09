import { beforeEach, describe, expect, it } from 'vitest';
import { useYachtStore } from './yachtStore';
import { yachtRoomSink } from './yachtRoomSink';
import type { YachtSeatView, YachtTableView } from '@/types/yacht';

const seat = (n: number, over: Partial<YachtSeatView> = {}): YachtSeatView => ({
  seat: n,
  scores: {},
  upperSubtotal: 0,
  bonus: 0,
  total: 0,
  deserted: false,
  ...over,
});

function table(over: Partial<YachtTableView> = {}): YachtTableView {
  return {
    phase: 'PLAYING',
    seats: [seat(0), seat(1)],
    round: 1,
    turnSeat: 0,
    dice: null,
    held: [false, false, false, false, false],
    rolls: 0,
    candidates: {},
    turnRemainingMs: null,
    result: null,
    ...over,
  };
}

function snap(t: YachtTableView) {
  return { roomId: 'r', phase: t.phase, eventSeq: 1, tableView: t, privateHand: null, disconnectedSeats: [] };
}

const ev = (type: string, payload: unknown) => ({ type, seq: 2, payload });

describe('yachtStore', () => {
  beforeEach(() => {
    useYachtStore.getState().reset('r');
  });

  it('snapshot takes the engine turn time from the table view', () => {
    useYachtStore.getState().applySnapshot(snap(table({ turnRemainingMs: 12_000 })));
    expect(useYachtStore.getState().turnClock?.remainingMs).toBe(12_000);
    useYachtStore.getState().applySnapshot(snap(table()));
    expect(useYachtStore.getState().turnClock).toBeNull();
  });

  it('a roll replaces dice, held, rolls and candidates and bumps the roll signal', () => {
    useYachtStore.getState().applySnapshot(snap(table()));
    const r = useYachtStore.getState().applyEvent(
      ev('DICE_ROLLED', { seat: 0, dice: [6, 6, 6, 2, 2], held: [false, false, false, false, false], rolls: 1, candidates: { FULL_HOUSE: 22 } }),
    );
    const s = useYachtStore.getState();
    expect(r).toBe('applied');
    expect(s.dice).toEqual([6, 6, 6, 2, 2]);
    expect(s.rolls).toBe(1);
    expect(s.candidates).toEqual({ FULL_HOUSE: 22 });
    expect(s.rollNonce).toBe(1);
  });

  it('a hold or roll for a seat that is not on turn asks for a resync', () => {
    useYachtStore.getState().applySnapshot(snap(table({ turnSeat: 1 })));
    expect(useYachtStore.getState().applyEvent(ev('DICE_HELD', { seat: 0, held: [true, false, false, false, false] }))).toBe(
      'unhandled',
    );
  });

  it('scored writes the result values and clears candidates', () => {
    useYachtStore.getState().applySnapshot(snap(table({ candidates: { YACHT: 50 } })));
    useYachtStore.getState().applyEvent(
      ev('SCORED', { seat: 0, category: 'YACHT', points: 50, upperSubtotal: 0, bonus: 0, total: 50, auto: false }),
    );
    const s = useYachtStore.getState();
    expect(s.seats[0].scores).toEqual({ YACHT: 50 });
    expect(s.seats[0].total).toBe(50);
    expect(s.candidates).toEqual({});
    expect(s.lastScore).toMatchObject({ seat: 0, category: 'YACHT', points: 50 });
  });

  it('applying the same scored event twice gives the same table (result values)', () => {
    useYachtStore.getState().applySnapshot(snap(table()));
    const e = ev('SCORED', { seat: 1, category: 'SIXES', points: 18, upperSubtotal: 18, bonus: 0, total: 18, auto: true });
    useYachtStore.getState().applyEvent(e);
    const once = useYachtStore.getState().seats;
    useYachtStore.getState().applyEvent(e);
    expect(useYachtStore.getState().seats).toEqual(once);
  });

  it('turn changed starts a fresh turn and the clock when the room has a limit', () => {
    useYachtStore.getState().applySnapshot(snap(table({ dice: [1, 2, 3, 4, 5], rolls: 2 })));
    useYachtStore.getState().applyEvent(ev('TURN_CHANGED', { seat: 1, round: 1, turnSeconds: 45 }));
    const s = useYachtStore.getState();
    expect(s.turnSeat).toBe(1);
    expect(s.dice).toBeNull();
    expect(s.rolls).toBe(0);
    expect(s.turnClock).toMatchObject({ remainingMs: null });
    useYachtStore.getState().applyEvent(ev('TURN_CHANGED', { seat: 0, round: 2, turnSeconds: 0 }));
    expect(useYachtStore.getState().turnClock).toBeNull();
    expect(useYachtStore.getState().round).toBe(2);
  });

  it('a deserter is marked and its turn is cleared; the match end freezes progress events', () => {
    useYachtStore.getState().applySnapshot(snap(table({ dice: [1, 1, 1, 1, 1], rolls: 1 })));
    useYachtStore.getState().applyEvent(ev('PLAYER_ELIMINATED', { seat: 0, total: 7 }));
    expect(useYachtStore.getState().seats[0]).toMatchObject({ deserted: true, total: 7 });
    expect(useYachtStore.getState().dice).toBeNull();

    useYachtStore.getState().applyEvent(
      ev('MATCH_ENDED', { reason: 'NO_HUMANS', placements: [{ seat: 1, rank: 1, total: 0, deserted: false }] }),
    );
    expect(useYachtStore.getState().result?.reason).toBe('NO_HUMANS');
    expect(useYachtStore.getState().applyEvent(ev('TURN_CHANGED', { seat: 1, round: 2, turnSeconds: 0 }))).toBe('ignored');
    expect(useYachtStore.getState().applyEvent(ev('PLAYER_DISCONNECTED', { seat: 1 }))).toBe('applied');
  });

  it("my own hold echo is not applied (the screen leads), another seat's is", () => {
    useYachtStore.getState().applySnapshot(snap(table({ dice: [1, 2, 3, 4, 5], rolls: 1 })));
    useYachtStore.getState().setOwnSeat(0);
    useYachtStore.getState().setHeldLocal([true, true, false, false, false]);
    useYachtStore.getState().applyEvent(ev('DICE_HELD', { seat: 0, held: [true, false, false, false, false] }));
    expect(useYachtStore.getState().held).toEqual([true, true, false, false, false]);

    useYachtStore.getState().setOwnSeat(1);
    useYachtStore.getState().applyEvent(ev('DICE_HELD', { seat: 0, held: [false, false, true, false, false] }));
    expect(useYachtStore.getState().held).toEqual([false, false, true, false, false]);
  });

  it('unknown events ask for a resync', () => {
    expect(useYachtStore.getState().applyEvent(ev('MATCH_STARTED', {}))).toBe('unhandled');
  });
});

describe('yachtRoomSink', () => {
  beforeEach(() => {
    useYachtStore.getState().reset('r');
  });

  it('labels server errors and swallows the hold race', () => {
    yachtRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'HOLD_UNCHANGED', message: '' } } as never);
    expect(useYachtStore.getState().errorMessage).toBeNull();
    yachtRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'NOT_ROLLED_YET', message: 'x' } } as never);
    expect(useYachtStore.getState().errorMessage).toBe('먼저 주사위를 굴리세요.');
  });

  it('ignores private frames that are not errors', () => {
    yachtRoomSink.applyPrivateEvent({ type: 'HAND_DEALT', payload: {} } as never);
    expect(useYachtStore.getState().errorMessage).toBeNull();
  });
});
