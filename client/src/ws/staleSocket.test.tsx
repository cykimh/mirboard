import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RoomEventSink } from './roomEventSink';

/**
 * D-130 — 방을 바꾼(또는 토큰을 바꾼) 뒤에도 **정리된 이전 소켓의 콜백**은 새 연결에 닿지 않는다.
 *
 * <p>@stomp/stompjs 의 {@code deactivate()} 는 비동기다 — 연결된 소켓이면 DISCONNECT 를 보내고 영수증이 올 때까지 소켓과
 * 구독 콜백이 살아 있다. 그 사이 이전 소켓의 핸들러가 계속 돌아, (1) 이전 방의 resync 가 새 방의 세대로 세대 가드를
 * 통과해 남의 방 스냅샷이 적용되고 순번 기준점이 부풀어 새 방의 이벤트가 전부 '중복'이 되거나, (2) 늦게 닿은 영수증·
 * 닫힘이 새 소켓의 connected 를 false 로 굳혔다. 모의 Client 는 deactivate() 가 즉시 끝나 이 부류가 안 보이므로 진짜
 * stompjs 를 가짜 WebSocket 위에서 돌린다(socketClose.test.tsx 와 같은 방식).
 */

const sockets: FakeWS[] = [];

class FakeWS {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  readyState = 0;
  binaryType = '';
  protocol = 'v12.stomp';
  url: string;
  sent: string[] = [];
  onopen: ((e: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: ((e: { code: number; reason: string; wasClean: boolean }) => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  constructor(url: string) {
    this.url = url;
    sockets.push(this);
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.readyState = 3;
    this.onclose?.({ code: 1000, reason: '', wasClean: true });
  }
}
vi.stubGlobal('WebSocket', FakeWS);

/** resync 가 어느 방에 대해 불렸는지 — 응답은 방마다 다른 순번(A=200, B=3)을 준다. */
const resyncCalls: string[] = [];
const resyncMock = vi.fn();
vi.mock('@/api/rooms', () => ({
  roomsApi: {
    resync: (token: string, roomId: string) => {
      resyncCalls.push(roomId);
      return resyncMock(token, roomId);
    },
  },
}));

import { useStompRoom } from './useStompRoom';
import { useLobbyStomp } from './useLobbyStomp';

function makeSink() {
  return {
    reset: vi.fn(),
    applySnapshot: vi.fn(),
    applyEvent: vi.fn<RoomEventSink['applyEvent']>(() => 'applied'),
    applyPrivateEvent: vi.fn(),
    setError: vi.fn(),
  } satisfies RoomEventSink;
}

/** 서버가 소켓을 받고 CONNECTED 로 답한다. */
function accept(ws: FakeWS) {
  act(() => {
    ws.readyState = 1;
    ws.onopen?.({});
  });
  act(() => {
    ws.onmessage?.({ data: 'CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0' });
  });
}

/** 그 소켓이 보낸 SUBSCRIBE 중 destination 이 dest 인 것의 구독 id. */
function subscriptionId(ws: FakeWS, dest: string) {
  const all = ws.sent.join('');
  const found = new RegExp(`SUBSCRIBE\\nid:(sub-\\d+)\\ndestination:${dest.replace(/\//g, '\\/')}`).exec(all);
  if (!found) throw new Error(`구독을 찾지 못했다: ${dest}`);
  return found[1];
}

/** 공개 토픽 프레임 한 건이 그 소켓에 닿는다. */
function topicFrame(ws: FakeWS, room: string, seq: number) {
  const id = subscriptionId(ws, `/topic/room/${room}`);
  const body = JSON.stringify({ type: 'X', seq, payload: {} });
  act(() => {
    ws.onmessage?.({ data: `MESSAGE\nsubscription:${id}\nmessage-id:m${seq}\ndestination:/topic/room/${room}\n\n${body}\0` });
  });
}

/** 본인 큐 프레임 한 건(비공개 — 손패·ERROR)이 그 소켓에 닿는다. */
function queueFrame(ws: FakeWS, room: string, type: string) {
  const id = subscriptionId(ws, `/user/queue/room/${room}`);
  const body = JSON.stringify({ type, payload: { code: 'NO_RACE', message: 'late' } });
  act(() => {
    ws.onmessage?.({
      data: `MESSAGE\nsubscription:${id}\nmessage-id:q-${room}\ndestination:/user/queue/room/${room}\n\n${body}\0`,
    });
  });
}

/** cleanup 이 보낸 DISCONNECT 의 영수증이 뒤늦게 닿는다 — 이때에야 stompjs 가 onDisconnect 를 부른다. */
function lateDisconnectReceipt(ws: FakeWS) {
  const receiptId = /receipt:(close-\d+)/.exec(ws.sent.join(''))?.[1];
  expect(receiptId).toBeDefined(); // 영수증을 청하지 않았다면 아래가 공회전이다
  act(() => {
    ws.onmessage?.({ data: `RECEIPT\nreceipt-id:${receiptId}\n\n\0` });
  });
}

beforeEach(() => {
  resyncCalls.length = 0;
  resyncMock.mockReset();
  resyncMock.mockImplementation((_token: string, roomId: string) =>
    Promise.resolve({
      roomId,
      phase: 'PLAYING',
      eventSeq: roomId === 'A' ? 200 : 3,
      tableView: { marker: roomId },
      privateHand: null,
    }),
  );
});

afterEach(() => {
  sockets.length = 0;
});

describe('정리된 이전 소켓의 콜백 (D-130)', () => {
  it('방 소켓 — 전환 뒤 늦게 닿은 이전 소켓의 DISCONNECT 영수증이 새 소켓의 connected 를 내리지 않는다', async () => {
    resyncMock.mockImplementation(() => new Promise(() => {}));
    const sink = makeSink();
    const { result, rerender } = renderHook(({ room }) => useStompRoom(room, 'tok', sink), {
      initialProps: { room: 'A' },
    });
    await waitFor(() => expect(sockets).toHaveLength(1));
    accept(sockets[0]);
    await waitFor(() => expect(result.current.connected).toBe(true));

    rerender({ room: 'B' });
    await waitFor(() => expect(sockets).toHaveLength(2));
    accept(sockets[1]);
    await waitFor(() => expect(result.current.connected).toBe(true));

    lateDisconnectReceipt(sockets[0]);

    expect(result.current.connected).toBe(true);
  });

  /**
   * 이전 방 A 의 소켓이 아직 닫히지 않은 틈에 A 의 공개 프레임이 닿는다. 그 핸들러가 부르는 resync 는 A 의 방 id 로
   * 요청하지만 세대는 지금(B)의 것을 잡아, 세대 가드를 통과해 A 의 스냅샷(순번 200)이 B 판에 적용되고 기준점이 200 으로
   * 부풀었다 — B 의 정상 이벤트(순번 4)는 '중복'으로 버려지고 B 의 응답(순번 3)은 '낡음'으로 버려졌다.
   */
  it('방 소켓 — 전환 뒤 이전 소켓에 닿은 이전 방의 프레임이 이전 방 스냅샷을 새 방에 적용하지 못한다', async () => {
    const sink = makeSink();
    const { rerender } = renderHook(({ room }) => useStompRoom(room, 'tok', sink), {
      initialProps: { room: 'A' },
    });
    await waitFor(() => expect(sockets).toHaveLength(1));
    accept(sockets[0]);
    await waitFor(() => expect(sink.applySnapshot).toHaveBeenCalled());

    rerender({ room: 'B' });
    await waitFor(() => expect(sockets).toHaveLength(2));
    accept(sockets[1]);
    await waitFor(() => expect(resyncCalls.filter((r) => r === 'B').length).toBeGreaterThanOrEqual(2));
    await act(async () => {});
    const appliedRooms = () => sink.applySnapshot.mock.calls.map((c) => (c[0] as { roomId: string }).roomId);
    const appliedBefore = appliedRooms().length;

    // 이전 소켓 A 는 아직 닫히지 않았다(영수증 대기) — A 의 공개 프레임(순번 200)이 닿는다.
    topicFrame(sockets[0], 'A', 200);
    await act(async () => {});
    expect(appliedRooms().slice(appliedBefore)).not.toContain('A');

    // 이어서 B 의 정상 프레임(순번 4) — 기준점이 200 으로 부풀었다면 '중복'으로 버려진다.
    const eventsBefore = sink.applyEvent.mock.calls.length;
    topicFrame(sockets[1], 'B', 4);
    expect(sink.applyEvent.mock.calls.length - eventsBefore).toBe(1);
  });

  /**
   * 재리뷰 N-2 — 본인 큐 핸들러의 가드(`if (disposed) return`)를 고정한다. 이전 방 A 의 소켓이 영수증을 기다리는 틈에 A 의
   * 비공개 프레임(손패·ERROR)이 닿아도 새 방 B 의 sink 로 가지 않는다 — 가면 B 판에 A 의 손패가 깔리거나 A 의 거절 문구가
   * 뜬다. 대조로 B 의 본인 큐 프레임은 그대로 간다.
   */
  it('방 소켓 — 전환 뒤 이전 소켓에 닿은 이전 방의 본인 큐 프레임이 새 방 sink 로 가지 않는다', async () => {
    const sink = makeSink();
    const { rerender } = renderHook(({ room }) => useStompRoom(room, 'tok', sink), {
      initialProps: { room: 'A' },
    });
    await waitFor(() => expect(sockets).toHaveLength(1));
    accept(sockets[0]);

    rerender({ room: 'B' });
    await waitFor(() => expect(sockets).toHaveLength(2));
    accept(sockets[1]);
    sink.applyPrivateEvent.mockClear();

    queueFrame(sockets[0], 'A', 'ERROR');
    expect(sink.applyPrivateEvent).not.toHaveBeenCalled();

    queueFrame(sockets[1], 'B', 'ERROR');
    expect(sink.applyPrivateEvent).toHaveBeenCalledTimes(1);
  });

  it('로비 소켓 — 토큰이 바뀐 뒤 늦게 닿은 이전 소켓의 DISCONNECT 영수증이 새 소켓의 connected 를 내리지 않는다', async () => {
    const { result, rerender } = renderHook(({ token }) => useLobbyStomp(token), {
      initialProps: { token: 'tok-a' },
    });
    await waitFor(() => expect(sockets).toHaveLength(1));
    accept(sockets[0]);
    await waitFor(() => expect(result.current.connected).toBe(true));

    rerender({ token: 'tok-b' });
    await waitFor(() => expect(sockets).toHaveLength(2));
    accept(sockets[1]);
    await waitFor(() => expect(result.current.connected).toBe(true));

    lateDisconnectReceipt(sockets[0]);

    expect(result.current.connected).toBe(true);
  });
});
