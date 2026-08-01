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
    ['gap', true],
    ['unhandled', true],
    ['applied', false],
    ['duplicate', false],
  ] as const)(
    'applyEvent 가 %s 를 반환하면 resync 재호출=%s',
    async (result, shouldResync) => {
      const sink = makeSink(result);
      renderHook(() => useStompRoom(ROOM, TOKEN, sink));
      await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());
      const before = resyncMock.mock.calls.length;

      act(() => {
        handlers.get(`/topic/room/${ROOM}`)!(frame({ type: 'X', seq: 1, payload: {} }));
      });

      expect(sink.applyEvent).toHaveBeenCalled();
      if (shouldResync) {
        expect(resyncMock.mock.calls.length).toBeGreaterThan(before);
      } else {
        expect(resyncMock.mock.calls.length).toBe(before);
      }
    },
  );

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
