import { renderHook, act, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RoomEventSink } from './roomEventSink';
import type { ApplyEventResult } from '@/types/stomp';

// ── @stomp/stompjs 가짜 ──────────────────────────────────────────────
// activate() 시 onConnect 를 즉시 호출하고, subscribe 핸들러를 목적지별로 캡처한다.
const handlers = new Map<string, (frame: { body: string }) => void>();
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
  roomsApi: { resync: (...args: unknown[]) => resyncMock(...args) },
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
