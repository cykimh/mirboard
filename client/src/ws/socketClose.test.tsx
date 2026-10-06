import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RoomEventSink } from './roomEventSink';

/**
 * D-130 — 소켓이 갑자기 끊기면(네트워크 끊김·서버 재시작·배포, close 1006/1001) 연결 표시가 내려간다.
 *
 * <p>@stomp/stompjs 의 {@code onDisconnect} 는 <b>클라가 먼저 DISCONNECT 를 보내 영수증을 받을 때만</b> 불린다. 갑작스러운
 * 끊김은 {@code onWebSocketClose} 만 부르는데 두 훅은 그것을 등록하지 않아 끊긴 뒤에도 "● 연결"이었다 — 재연결 배너가
 * 안 뜨고, 원카드 버튼의 끊김 가드가 통과돼 누름이 조용히 버려졌다. 라이브러리의 콜백 의미가 핵심이라 모킹하지 않고
 * 진짜 stompjs 를 가짜 WebSocket 위에서 돌린다.
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

vi.mock('@/api/rooms', () => ({
  roomsApi: { resync: () => new Promise(() => {}) },
}));

import { useStompRoom } from './useStompRoom';
import { useLobbyStomp } from './useLobbyStomp';

const sink: RoomEventSink = {
  reset() {},
  applySnapshot() {},
  applyEvent: () => 'applied',
  applyPrivateEvent() {},
  setError() {},
};

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

/** 영수증 없이 소켓이 닫힌다 — 네트워크 끊김·서버 재시작. */
function dropAbruptly(ws: FakeWS) {
  act(() => {
    ws.readyState = 3;
    ws.onclose?.({ code: 1006, reason: '', wasClean: false });
  });
}

afterEach(() => {
  sockets.length = 0;
});

describe('갑작스러운 끊김 (D-130)', () => {
  it('방 소켓 — 끊기면 connected 가 false 로 내려간다', async () => {
    const { result } = renderHook(() => useStompRoom('r-1', 'tok', sink));
    await waitFor(() => expect(sockets).toHaveLength(1));
    accept(sockets[0]);
    await waitFor(() => expect(result.current.connected).toBe(true));

    dropAbruptly(sockets[0]);

    expect(result.current.connected).toBe(false);
  });

  it('로비 소켓 — 끊기면 connected 가 false 로 내려간다', async () => {
    const { result } = renderHook(() => useLobbyStomp('tok'));
    await waitFor(() => expect(sockets).toHaveLength(1));
    accept(sockets[0]);
    await waitFor(() => expect(result.current.connected).toBe(true));

    dropAbruptly(sockets[0]);

    expect(result.current.connected).toBe(false);
  });
});
