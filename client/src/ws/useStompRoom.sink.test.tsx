import { renderHook, act, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RoomEventSink } from './roomEventSink';
import type { ApplyEventResult } from '@/types/stomp';

// ── @stomp/stompjs 가짜 ──────────────────────────────────────────────
// activate() 시 onConnect 를 즉시 호출하고, subscribe 핸들러를 목적지별로 캡처한다.
const handlers = new Map<string, (frame: { body: string }) => void>();
/** 구독·resync 호출 순서 기록 (S5 — 접속 직후 순서). */
const calls: string[] = [];
let activateCount = 0;
let deactivateCount = 0;
let clientCount = 0;

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    connected = false;
    private onConnect: () => void;
    constructor(cfg: { onConnect: () => void }) {
      clientCount++;
      this.onConnect = cfg.onConnect;
    }
    activate() {
      activateCount++;
      this.connected = true;
      this.onConnect();
    }
    subscribe(dest: string, cb: (frame: { body: string }) => void) {
      calls.push(`sub:${dest}`);
      handlers.set(dest, cb);
      return { unsubscribe: () => {} };
    }
    publish() {}
    deactivate() {
      deactivateCount++;
      this.connected = false;
    }
  },
}));

const resyncMock = vi.fn();
vi.mock('@/api/rooms', () => ({
  roomsApi: {
    resync: (...args: unknown[]) => {
      calls.push('resync');
      return resyncMock(...args);
    },
  },
}));

import { useStompRoom } from './useStompRoom';

const ROOM = 'r-1';
const TOKEN = 'tok';
const SNAP = {
  roomId: ROOM,
  phase: 'PLAYING',
  eventSeq: 7,
  tableView: { hi: 1 },
  privateHand: { seat: 0 },
  disconnectedSeats: [2],
  chips: { 1: 500 },
};

function makeSink(applyResult: ApplyEventResult = 'applied') {
  return {
    reset: vi.fn(),
    applySnapshot: vi.fn(),
    applyEvent: vi.fn<RoomEventSink['applyEvent']>(() => applyResult),
    applyPrivateEvent: vi.fn(),
    setError: vi.fn(),
  };
}

const frame = (body: unknown) => ({ body: JSON.stringify(body) });

/** 공개 토픽에 이벤트 한 건. seq 를 생략하면 JSON 에서 키가 빠진다(서버 `NON_NULL` 과 같다). */
const publish = (seq: number | undefined, type = 'X') =>
  handlers.get(`/topic/room/${ROOM}`)!(frame({ type, seq, payload: {} }));

beforeEach(() => {
  handlers.clear();
  calls.length = 0;
  activateCount = 0;
  deactivateCount = 0;
  clientCount = 0;
  resyncMock.mockReset();
  resyncMock.mockResolvedValue(SNAP);
});

describe('useStompRoom — RoomEventSink 주입 (D-103)', () => {
  it('마운트 시 sink.reset 1회 + resync 응답을 가공 없이 applySnapshot 으로 넘긴다', async () => {
    const sink = makeSink();
    renderHook(() => useStompRoom(ROOM, TOKEN, sink));

    await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
    expect(sink.reset).toHaveBeenCalledTimes(1);
    expect(sink.reset).toHaveBeenCalledWith(ROOM);
    // 껍데기를 재조립하지 않고 그대로 — 필드 누락/오타 회귀 가드.
    expect(sink.applySnapshot).toHaveBeenCalledWith(SNAP);
  });

  /**
   * 이 설계의 핵심 가정. sink 를 ref 에 담지 않았다면 인라인 객체가 매 렌더 새 참조가 되어
   * effect 가 재실행되고 소켓 재연결 + reset + resync 무한 루프가 된다.
   */
  it('렌더마다 새 인라인 sink 를 넘겨도 소켓·reset·resync 호출이 늘지 않는다', async () => {
    const spy = { reset: vi.fn(), applySnapshot: vi.fn() };
    const { rerender } = renderHook(() =>
      useStompRoom(ROOM, TOKEN, {
        reset: spy.reset,
        applySnapshot: spy.applySnapshot,
        applyEvent: () => 'applied',
        applyPrivateEvent: () => {},
        setError: () => {},
      }),
    );
    await waitFor(() => expect(spy.applySnapshot).toHaveBeenCalled());

    const clientsAfterMount = clientCount;
    const resetAfterMount = spy.reset.mock.calls.length;
    const resyncAfterMount = resyncMock.mock.calls.length;

    rerender();
    rerender();
    rerender();

    expect(clientCount).toBe(clientsAfterMount);
    expect(spy.reset.mock.calls.length).toBe(resetAfterMount);
    expect(resyncMock.mock.calls.length).toBe(resyncAfterMount);
  });

  it.each([
    ['unhandled', true],
    ['applied', false],
    ['ignored', false],
  ] as const)(
    '다음 순번 이벤트에 sink 가 %s 를 반환하면 resync 재호출=%s',
    async (result, shouldResync) => {
      const sink = makeSink(result);
      renderHook(() => useStompRoom(ROOM, TOKEN, sink));
      await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
      const before = resyncMock.mock.calls.length;

      act(() => publish(SNAP.eventSeq + 1));

      expect(sink.applyEvent).toHaveBeenCalledTimes(1);
      if (shouldResync) {
        expect(resyncMock.mock.calls.length).toBeGreaterThan(before);
      } else {
        expect(resyncMock.mock.calls.length).toBe(before);
      }
    },
  );

  describe('순번 판정은 훅이 한다 (D-124)', () => {
    async function mounted(sink: ReturnType<typeof makeSink>) {
      renderHook(() => useStompRoom(ROOM, TOKEN, sink));
      await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
      return resyncMock.mock.calls.length;
    }

    it('지난 순번(중복)은 sink 로 넘기지 않고 resync 도 하지 않는다', async () => {
      const sink = makeSink();
      const before = await mounted(sink);

      act(() => publish(SNAP.eventSeq));

      expect(sink.applyEvent).not.toHaveBeenCalled();
      expect(resyncMock.mock.calls.length).toBe(before);
    });

    it('구멍 난 순번은 sink 로 넘기지 않고 resync 한다', async () => {
      const sink = makeSink();
      const before = await mounted(sink);

      act(() => publish(SNAP.eventSeq + 2));

      expect(sink.applyEvent).not.toHaveBeenCalled();
      expect(resyncMock.mock.calls.length).toBeGreaterThan(before);
    });

    it('반영한 이벤트마다 기준점이 전진한다', async () => {
      const sink = makeSink();
      const before = await mounted(sink);

      act(() => {
        publish(SNAP.eventSeq + 1);
        publish(SNAP.eventSeq + 2);
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(2);
      expect(resyncMock.mock.calls.length).toBe(before);
    });

    it('ignored 도 기준점을 전진시킨다 — 다음 순번이 구멍으로 보이지 않는다', async () => {
      const sink = makeSink('ignored');
      const before = await mounted(sink);

      act(() => {
        publish(SNAP.eventSeq + 1);
        publish(SNAP.eventSeq + 2);
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(2);
      expect(resyncMock.mock.calls.length).toBe(before);
    });

    it('unhandled 는 기준점을 전진시키지 않는다 — resync 가 다시 세운다', async () => {
      const sink = makeSink('unhandled');
      const before = await mounted(sink);

      act(() => {
        publish(SNAP.eventSeq + 1); // unhandled → resync
        publish(SNAP.eventSeq + 2); // 기준점이 그대로라 구멍 → resync, sink 미호출
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(1);
      expect(resyncMock.mock.calls.length).toBe(before + 2);
    });

    it('순번 없는 메타 이벤트는 판정 없이 넘기고 기준점을 건드리지 않는다', async () => {
      const sink = makeSink();
      const before = await mounted(sink);

      act(() => {
        publish(undefined, 'PLAYER_DISCONNECTED');
        publish(SNAP.eventSeq + 1);
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(2);
      expect(resyncMock.mock.calls.length).toBe(before);
    });

    it('방이 바뀌면 기준점이 0 으로 돌아간다', async () => {
      const sink = makeSink();
      const { rerender } = renderHook(({ room }) => useStompRoom(room, TOKEN, sink), {
        initialProps: { room: ROOM },
      });
      await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
      // 새 방의 resync 는 응답이 오지 않게 붙잡아, 기준점이 리셋값(0)인 구간을 본다.
      resyncMock.mockImplementation(() => new Promise(() => {}));

      rerender({ room: 'r-2' });
      act(() => {
        handlers.get('/topic/room/r-2')!(frame({ type: 'X', seq: 1, payload: {} }));
      });

      expect(sink.applyEvent).toHaveBeenCalledTimes(1);
    });
  });

  it('본인 큐 프레임은 ERROR·미지 타입까지 전량 applyPrivateEvent 로 간다', async () => {
    const sink = makeSink();
    renderHook(() => useStompRoom(ROOM, TOKEN, sink));
    await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
    sink.setError.mockClear();

    const queue = handlers.get(`/user/queue/room/${ROOM}`)!;
    const frames = [
      { type: 'HAND_DEALT', payload: { seat: 0, cards: [] } },
      { type: 'CARDS_RECEIVED', payload: { seat: 0, received: [] } },
      { type: 'ERROR', payload: { code: 'C', message: 'm' } },
      { type: 'WHO_KNOWS', payload: {} },
    ];
    act(() => frames.forEach((f) => queue(frame(f))));

    expect(sink.applyPrivateEvent).toHaveBeenCalledTimes(4);
    frames.forEach((f) =>
      expect(sink.applyPrivateEvent).toHaveBeenCalledWith(expect.objectContaining(f)),
    );
    // 서버 ERROR 는 setError 로 가지 않는다 — setError 는 REST resync 실패 전용(규약 3).
    expect(sink.setError).not.toHaveBeenCalled();
  });

  it('resync 실패는 sink.setError 로 보고된다', async () => {
    resyncMock.mockRejectedValue(new Error('boom'));
    const sink = makeSink();
    renderHook(() => useStompRoom(ROOM, TOKEN, sink));

    await waitFor(() => expect(sink.setError).toHaveBeenCalledWith('boom'));
    expect(sink.applySnapshot).not.toHaveBeenCalled();
  });

  /** D-79 — 모바일 포그라운드 복귀 시 소켓 재가동 + 즉시 resync (탈주 유예 방어). */
  it('visibilitychange visible 이면 재가동 + resync', async () => {
    const sink = makeSink();
    renderHook(() => useStompRoom(ROOM, TOKEN, sink));
    await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
    const beforeActivate = activateCount;
    const beforeResync = resyncMock.mock.calls.length;

    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(activateCount).toBeGreaterThan(beforeActivate);
    expect(resyncMock.mock.calls.length).toBeGreaterThan(beforeResync);
  });

  it('언마운트 시 소켓을 정리한다', async () => {
    const sink = makeSink();
    const { unmount } = renderHook(() => useStompRoom(ROOM, TOKEN, sink));
    await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());

    unmount();

    expect(deactivateCount).toBeGreaterThan(0);
  });

  it('토큰이 없으면 소켓도 resync 도 없다', () => {
    const sink = makeSink();
    renderHook(() => useStompRoom(ROOM, null, sink));

    expect(clientCount).toBe(0);
    expect(resyncMock).not.toHaveBeenCalled();
  });
});

describe('useStompRoom — S5 보강 (낡은 resync·접속 순서·다시 받기)', () => {
  /** 응답을 테스트가 원하는 순서로 풀 수 있게 붙잡아 둔다. */
  function heldResyncs() {
    const pending: Array<(snap: unknown) => void> = [];
    resyncMock.mockImplementation(() => new Promise((resolve) => pending.push(resolve)));
    return pending;
  }

  /**
   * 응답과 STOMP 프레임의 도착 순서는 정해져 있지 않다 — 락 안에서 seq 5 를 읽은 응답이 그 뒤 seq 6·7 프레임보다 늦게
   * 닿을 수 있다. 그 응답을 적용하면 공개 상태와 기준점이 되돌아가, 사람 차례에서는 다음 이벤트가 오지 않아 판이 멈췄다.
   */
  it('기준점보다 낡은 응답은 버린다 — 이미 반영한 이벤트를 되돌리지 않는다', async () => {
    const pending = heldResyncs();
    const sink = makeSink();
    renderHook(() => useStompRoom(ROOM, TOKEN, sink));
    await waitFor(() => expect(pending).toHaveLength(2)); // 마운트 + 접속 직후

    await act(async () => pending[0]({ ...SNAP, eventSeq: 5 }));
    act(() => {
      publish(6);
      publish(7);
    });
    await act(async () => pending[1]({ ...SNAP, eventSeq: 5 })); // 6·7 보다 먼저 읽은 낡은 응답

    expect(sink.applySnapshot).toHaveBeenCalledTimes(1);
    const before = resyncMock.mock.calls.length;
    act(() => publish(8)); // 기준점이 7 그대로면 '다음'이다
    expect(sink.applyEvent).toHaveBeenCalledTimes(3);
    expect(resyncMock.mock.calls.length).toBe(before);
  });

  it('기준점과 같은 순번의 응답은 그대로 적용한다', async () => {
    const pending = heldResyncs();
    const sink = makeSink();
    renderHook(() => useStompRoom(ROOM, TOKEN, sink));
    await waitFor(() => expect(pending).toHaveLength(2));

    await act(async () => pending[0]({ ...SNAP, eventSeq: 5 }));
    await act(async () => pending[1]({ ...SNAP, eventSeq: 5 }));

    expect(sink.applySnapshot).toHaveBeenCalledTimes(2);
  });

  /**
   * resync 를 먼저 보내면 서버가 스냅샷을 읽은 뒤·구독을 등록하기 전에 낸 이벤트가 스냅샷에도 프레임에도 없다. 공개 토픽과
   * 본인 큐(손패) 둘 다 resync 앞이어야 한다. 서버 등록이 비동기라 틈을 좁힐 뿐 보장은 아니다(SUBSCRIBE 영수증이 없다).
   */
  it('접속하면 공개 토픽·본인 큐를 구독한 뒤에 resync 한다', async () => {
    renderHook(() => useStompRoom(ROOM, TOKEN, makeSink()));
    await waitFor(() => expect(calls.filter((c) => c === 'resync')).toHaveLength(2));

    const afterMount = calls.indexOf('resync') + 1; // 첫 resync 는 마운트 때 것
    const connectResync = calls.indexOf('resync', afterMount);
    const topic = calls.indexOf(`sub:/topic/room/${ROOM}`, afterMount);
    const queue = calls.indexOf(`sub:/user/queue/room/${ROOM}`, afterMount);
    expect(topic).toBeGreaterThanOrEqual(0);
    expect(queue).toBeGreaterThanOrEqual(0);
    expect(topic).toBeLessThan(connectResync);
    expect(queue).toBeLessThan(connectResync);
  });

  /**
   * 방이 바뀌는 사이(게임판이 마운트된 채 roomId 가 바뀜 — 뒤로/앞으로) 이전 방의 resync 응답이 늦게 닿으면 새 방의 기준점을
   * 올려, 새 방의 정상 스냅샷과 이벤트를 전부 '낡음'·'중복'으로 버렸다(사전 리뷰 I-1). 방 전환마다 오르는 세대로 버린다.
   */
  it('방이 바뀐 뒤 도착한 이전 방의 응답은 버린다 — 새 방의 기준점을 올리지 않는다', async () => {
    const pending: Array<{ roomId: string; resolve: (snap: unknown) => void }> = [];
    resyncMock.mockImplementation(
      (_token: string, roomId: string) => new Promise((resolve) => pending.push({ roomId, resolve })),
    );
    const of = (roomId: string) => pending.filter((p) => p.roomId === roomId);
    const sink = makeSink();
    const { rerender } = renderHook(({ room }) => useStompRoom(room, TOKEN, sink), {
      initialProps: { room: 'A' },
    });
    await waitFor(() => expect(of('A').length).toBeGreaterThan(0));

    rerender({ room: 'B' });
    await waitFor(() => expect(of('B').length).toBeGreaterThan(0));

    // 이전 방 A 의 늦은 응답(순번 50)이 먼저, 새 방 B 의 응답(순번 3)이 뒤에 닿는다.
    await act(async () => of('A').forEach((p) => p.resolve({ ...SNAP, roomId: 'A', eventSeq: 50 })));
    await act(async () => of('B').forEach((p) => p.resolve({ ...SNAP, roomId: 'B', eventSeq: 3 })));

    const applied = sink.applySnapshot.mock.calls.map((call) => (call[0] as { roomId: string }).roomId);
    expect(applied).not.toContain('A');
    expect(applied.at(-1)).toBe('B');
    act(() => handlers.get('/topic/room/B')!(frame({ type: 'X', seq: 4, payload: {} })));
    expect(sink.applyEvent).toHaveBeenCalledTimes(1);
  });

  /** 실패 경로의 세대 가드 — 이전 방의 요청이 늦게 실패해도 새 방 화면에 오류가 뜨면 안 된다. */
  it('방이 바뀐 뒤 실패한 이전 방의 resync 는 setError 로 가지 않는다', async () => {
    const pending: Array<{ roomId: string; reject: (err: Error) => void }> = [];
    resyncMock.mockImplementation(
      (_token: string, roomId: string) => new Promise((_resolve, reject) => pending.push({ roomId, reject })),
    );
    const sink = makeSink();
    const { rerender } = renderHook(({ room }) => useStompRoom(room, TOKEN, sink), {
      initialProps: { room: 'A' },
    });
    await waitFor(() => expect(pending.some((p) => p.roomId === 'A')).toBe(true));

    rerender({ room: 'B' });
    await waitFor(() => expect(pending.some((p) => p.roomId === 'B')).toBe(true));

    await act(async () =>
      pending.filter((p) => p.roomId === 'A').forEach((p) => p.reject(new Error('A 의 요청이 늦게 실패'))),
    );

    expect(sink.setError).not.toHaveBeenCalled();
  });

  /**
   * 언마운트도 방 전환과 같다 — 언마운트 직전에 보낸 resync(락 대기로 최대 ~3초)가 끝나면 모듈 전역 스토어에 적용돼, 그 사이
   * 같은 게임의 다른 방 게임판이 마운트됐다면 그 방 화면이 이전 방 스냅샷으로 덮인다.
   */
  it('언마운트 뒤 도착한 resync 응답은 sink 로 가지 않는다', async () => {
    const pending = heldResyncs();
    const sink = makeSink();
    const { unmount } = renderHook(() => useStompRoom(ROOM, TOKEN, sink));
    await waitFor(() => expect(pending).toHaveLength(2)); // 마운트 + 접속 직후

    unmount();
    await act(async () => pending.forEach((resolve) => resolve({ ...SNAP, eventSeq: 9 })));

    expect(sink.applySnapshot).not.toHaveBeenCalled();
  });

  it('게임판이 권위 스냅샷을 다시 청할 수 있다 — requestResync', async () => {
    const sink = makeSink();
    const { result } = renderHook(() => useStompRoom(ROOM, TOKEN, sink));
    await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
    const before = resyncMock.mock.calls.length;
    const applied = sink.applySnapshot.mock.calls.length;

    await act(async () => {
      await result.current.requestResync();
    });

    expect(resyncMock.mock.calls.length).toBe(before + 1);
    expect(sink.applySnapshot.mock.calls.length).toBe(applied + 1);
  });
});
