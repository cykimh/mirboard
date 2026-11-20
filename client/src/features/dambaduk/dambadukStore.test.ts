import { beforeEach, describe, expect, it } from 'vitest';
import { useDambadukStore } from './dambadukStore';
import { ONE_REGION, OPTIONS_C4, placingView, playingView, snapshot } from './dambadukFixtures';

const store = () => useDambadukStore.getState();

describe('dambadukStore — 공개 이벤트 거울(순수 리듀서)', () => {
  beforeEach(() => {
    store().reset('r1');
  });

  it('스냅샷을 그대로 옮기고 고르던 것을 비운다', () => {
    store().selectStone(4);
    store().applySnapshot(snapshot(playingView(), 12_000));
    const s = store();
    expect(s.stones).toHaveLength(8);
    expect(s.options).toEqual(OPTIONS_C4);
    expect(s.selectedStoneId).toBeNull();
    expect(s.turnClock?.remainingMs).toBe(12_000);
  });

  it('STONE_PLACED 는 돌을 더하고 배치 순서를 줄이며, 같은 돌은 두 번 더하지 않는다', () => {
    store().applySnapshot(snapshot(placingView()));
    const ev = { type: 'STONE_PLACED', seq: 2, payload: { seat: 0, stoneId: 4, kind: 'STRAIGHT', x: 3, y: 3, timedOut: false } };
    expect(store().applyEvent(ev)).toBe('applied');
    expect(store().applyEvent(ev)).toBe('applied');
    expect(store().stones.filter((s) => s.id === 4)).toHaveLength(1);
    expect(store().placementQueue).toEqual([1, 1, 0]);
    expect(store().turnSeat).toBe(-1);
    expect(store().fxSeq).toBe(1);
  });

  it('STONE_MOVED 는 돌을 옮기고 담을 세우며(넘긴 담은 없앤다) 담 넘기를 지운다', () => {
    store().applySnapshot(snapshot(playingView({ walls: [50], jumpsLeft: [1, 1], seatCount: 2 })));
    const ev = {
      type: 'STONE_MOVED',
      seq: 2,
      payload: { seat: 0, stoneId: 4, path: ['UP'], fromX: 2, fromY: 3, toX: 2, toY: 2, wall: 'UP', wallFace: 51, jumpedFace: 50, timedOut: false },
    };
    expect(store().applyEvent(ev)).toBe('applied');
    const s = store();
    expect(s.stones.find((x) => x.id === 4)).toMatchObject({ x: 2, y: 2 });
    expect(s.walls).toEqual([51]);
    expect(s.jumpsLeft).toEqual([0, 1]);
    expect(s.moveCount).toBe(1);
    expect(s.lastMove?.wallFace).toBe(51);
    expect(store().applyEvent(ev)).toBe('applied'); // 이미 적용 — 그대로
    expect(store().moveCount).toBe(1);
  });

  it('출발 자리가 어긋난 옮김은 권위 스냅샷을 청한다', () => {
    store().applySnapshot(snapshot(playingView()));
    const ev = {
      type: 'STONE_MOVED',
      seq: 2,
      payload: { seat: 0, stoneId: 4, path: ['UP'], fromX: 6, fromY: 6, toX: 6, toY: 5, wall: 'UP', wallFace: 3, jumpedFace: -1, timedOut: false },
    };
    expect(store().applyEvent(ev)).toBe('unhandled');
    expect(store().applyEvent({ ...ev, payload: { ...ev.payload, stoneId: 99 } })).toBe('unhandled');
  });

  it('TURN_CHANGED 는 차례·터·고를 수 있는 수를 바꾸고 배치가 끝나면 순서를 비운다', () => {
    store().applySnapshot(snapshot(placingView()));
    store().applyEvent({
      type: 'TURN_CHANGED',
      seq: 2,
      payload: { seat: 0, phase: 'PLAYING', territory: ONE_REGION, placeKinds: [], options: OPTIONS_C4 },
    });
    const s = store();
    expect(s.phase).toBe('PLAYING');
    expect(s.turnSeat).toBe(0);
    expect(s.placementQueue).toEqual([]);
    expect(s.options).toEqual(OPTIONS_C4);
    expect(s.turnEpoch).toBe(1);
  });

  it('SEAT_OUT 은 빠진 좌석과 치운 돌을, TURN_SKIPPED 는 건너뜀을 적는다', () => {
    store().applySnapshot(snapshot(playingView({ seatCount: 3, seatColors: [0, 1, 2] })));
    store().applyEvent({ type: 'SEAT_OUT', seq: 2, payload: { seat: 1, reason: 'DESERTED', removedStoneIds: [2, 3] } });
    expect(store().exits).toEqual([{ seat: 1, reason: 'DESERTED' }]);
    expect(store().stones.map((s) => s.id)).not.toContain(2);
    store().applyEvent({ type: 'TURN_SKIPPED', seq: 3, payload: { seat: 2 } });
    expect(store().skipped).toEqual({ seat: 2, n: 1 });
  });

  it('MATCH_ENDED 뒤에는 판 이벤트를 무시한다', () => {
    store().applySnapshot(snapshot(playingView()));
    store().applyEvent({
      type: 'MATCH_ENDED',
      seq: 2,
      payload: {
        reason: 'SEPARATED',
        standings: [
          { seat: 0, score: 21, largest: 21, rank: 1, exit: null },
          { seat: 1, score: 16, largest: 16, rank: 2, exit: null },
        ],
        winners: [0],
        draw: false,
        causeSeat: -1,
        territory: ONE_REGION,
        moveCount: 30,
      },
    });
    expect(store().result?.winners).toEqual([0]);
    expect(store().phase).toBe('ENDED');
    expect(store().applyEvent({ type: 'TURN_SKIPPED', seq: 3, payload: { seat: 1 } })).toBe('ignored');
  });

  it('보낸 수는 판에 반영될 때까지 스냅샷을 넘어 남는다', () => {
    store().applySnapshot(snapshot(playingView()));
    store().markPending({ type: 'move', stoneId: 4, toX: 2, toY: 2, wallFace: 51, jumpedFace: -1, at: { moveCount: 0, stoneCount: 8 } });
    store().applySnapshot(snapshot(playingView()));
    expect(store().pending).not.toBeNull();
    store().applySnapshot(snapshot(playingView({ moveCount: 1 })));
    expect(store().pending).toBeNull();
  });
});
