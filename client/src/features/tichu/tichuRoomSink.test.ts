import { beforeEach, describe, expect, it } from 'vitest';
import { tichuRoomSink } from './tichuRoomSink';
import { useTichuStore } from './tichuStore';
import type { PrivateHand, TableView } from '@/types/tichu';
import type { ResyncEnvelope } from '@/types/stomp';

/**
 * 특성화 테스트 — C1(D-103) 에서 `useStompRoom` 안에 있던 티츄 분기를 이 파일로 옮겼다.
 * 여기서 고정하는 것은 "옮기기 전과 동작이 같다" 이며, 특히 `applySnapshot` 의 5필드 매핑이
 * 중요하다: `eventSeq→lastSeq` 오타는 모든 이벤트를 gap 으로 만들어 resync 폭주가 되고,
 * `chips`/`disconnectedSeats` 오타는 예외 없이 배지만 사라진다.
 */

const TABLE = { phase: 'PLAYING', handCounts: { 0: 14 } } as unknown as TableView;
const HAND: PrivateHand = { seat: 1, cards: [] };

const envelope = (type: string, payload: unknown) => ({
  eventId: 'e',
  type,
  ts: 0,
  payload,
});

beforeEach(() => {
  useTichuStore.getState().reset('r-1');
});

describe('tichuRoomSink — 옮겨진 티츄 분기 (D-103)', () => {
  it('모듈 상수라 참조가 안정적이다', () => {
    expect(tichuRoomSink).toBe(tichuRoomSink);
  });

  it('applySnapshot 이 5필드를 값 단위로 매핑한다', () => {
    const snap: ResyncEnvelope<TableView, PrivateHand> = {
      roomId: 'r-1',
      phase: 'PLAYING',
      eventSeq: 42,
      tableView: TABLE,
      privateHand: HAND,
      disconnectedSeats: [2, 3],
      chips: { 10: 1000, 20: 500 },
    };

    tichuRoomSink.applySnapshot(snap);

    const s = useTichuStore.getState();
    expect(s.tableView).toBe(TABLE);
    expect(s.privateHand).toBe(HAND);
    expect(s.lastSeq).toBe(42); // ← eventSeq→lastSeq. 틀리면 resync 폭주.
    expect([...s.disconnectedSeats]).toEqual([2, 3]);
    expect(s.chips).toEqual({ 10: 1000, 20: 500 });
    expect(s.errorMessage).toBeNull();
  });

  it('관전자 resync(privateHand=null)에서도 예외 없이 통과한다', () => {
    expect(() =>
      tichuRoomSink.applySnapshot({
        roomId: 'r-1',
        phase: 'PLAYING',
        eventSeq: 1,
        tableView: TABLE,
        privateHand: null,
        disconnectedSeats: undefined,
        chips: null,
      }),
    ).not.toThrow();

    expect(useTichuStore.getState().lastSeq).toBe(1);
    expect(useTichuStore.getState().chips).toEqual({});
  });

  it('HAND_DEALT 는 privateHand 를 갱신한다', () => {
    tichuRoomSink.applyPrivateEvent(
      envelope('HAND_DEALT', { seat: 2, cards: [] }),
    );

    expect(useTichuStore.getState().privateHand).toEqual({ seat: 2, cards: [] });
  });

  it('CARDS_RECEIVED 는 받은 카드 목록을 넣는다', () => {
    const received = [
      { card: { suit: 'JADE', rank: 5, special: null }, fromSeat: 3 },
    ];
    tichuRoomSink.applyPrivateEvent(
      envelope('CARDS_RECEIVED', { seat: 0, received }),
    );

    expect(useTichuStore.getState().lastReceived).toEqual(received);
  });

  it('서버 ERROR 는 `code: message` 포맷 그대로 (옮기기 전과 비트 동치)', () => {
    tichuRoomSink.applyPrivateEvent(
      envelope('ERROR', { code: 'NOT_YOUR_TURN', message: 'nope' }),
    );

    expect(useTichuStore.getState().errorMessage).toBe('NOT_YOUR_TURN: nope');
  });

  it('미지 타입은 상태를 바꾸지 않는다', () => {
    tichuRoomSink.applyPrivateEvent(envelope('HAND_DEALT', { seat: 1, cards: [] }));
    const before = useTichuStore.getState().privateHand;

    tichuRoomSink.applyPrivateEvent(envelope('SOMETHING_NEW', { x: 1 }));

    expect(useTichuStore.getState().privateHand).toBe(before);
    expect(useTichuStore.getState().errorMessage).toBeNull();
  });

  it('applyEvent 는 스토어 판정을 그대로 위임한다', () => {
    tichuRoomSink.applySnapshot({
      roomId: 'r-1',
      phase: 'PLAYING',
      eventSeq: 5,
      tableView: TABLE,
      privateHand: HAND,
    });

    expect(tichuRoomSink.applyEvent({ type: 'PASSED', seq: 3, payload: { seat: 0 } }))
      .toBe('duplicate');
    expect(tichuRoomSink.applyEvent({ type: 'PASSED', seq: 99, payload: { seat: 0 } }))
      .toBe('gap');
    expect(tichuRoomSink.applyEvent({ type: 'NOPE', seq: 6, payload: {} }))
      .toBe('unhandled');
  });

  it('setError 는 REST resync 실패 통로다', () => {
    tichuRoomSink.setError('network down');
    expect(useTichuStore.getState().errorMessage).toBe('network down');
  });
});
