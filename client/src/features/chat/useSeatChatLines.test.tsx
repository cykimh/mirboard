import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SEAT_CHAT_TTL_MS, useSeatChatLines } from './useSeatChatLines';
import { useRoomChatStore } from './roomChatStore';

/** D-163 — 좌석마다 최근 채팅 한 줄을 잠깐(5초) 보인다. 받은 시각 기준(서버 시각·휴대폰 시계 차이와 무관). */
const send = (eventId: string, userId: number, message: string) =>
  act(() =>
    useRoomChatStore.getState().appendIncoming({ eventId, ts: 0, userId, username: `u${userId}`, message }, true),
  );

describe('useSeatChatLines (D-163)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useRoomChatStore.getState().reset('r1');
  });
  afterEach(() => vi.useRealTimers());

  it('보낸 사람 좌석에 최근 한 줄 — 나도 포함, 같은 좌석은 최신으로', () => {
    const { result } = renderHook(() => useSeatChatLines([100, 101, 102]));
    send('a', 101, '안녕');
    send('b', 100, '나야');
    send('c', 101, '두 번째');

    expect(result.current[1]?.text).toBe('두 번째');
    expect(result.current[0]?.text).toBe('나야');
    expect(result.current[2]).toBeUndefined();
  });

  it('5초가 지나면 사라진다', () => {
    const { result } = renderHook(() => useSeatChatLines([100, 101]));
    send('a', 101, '안녕');
    expect(result.current[1]?.text).toBe('안녕');

    act(() => vi.advanceTimersByTime(SEAT_CHAT_TTL_MS + 600));
    expect(result.current[1]).toBeUndefined();
  });

  it('좌석에 없는 사람(관전자)의 채팅은 좌석에 뜨지 않는다', () => {
    const { result } = renderHook(() => useSeatChatLines([100, 101]));
    send('a', 555, '관전 중');

    expect(Object.keys(result.current)).toEqual([]);
  });

  it('들어오기 전에 쌓인 채팅(재접속·방 입장)은 띄우지 않는다', () => {
    send('old', 101, '예전 말');
    const { result } = renderHook(() => useSeatChatLines([100, 101]));

    expect(result.current[1]).toBeUndefined();
  });
});
