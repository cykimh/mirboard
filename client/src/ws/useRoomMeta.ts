import { Client } from '@stomp/stompjs';
import { useEffect, useRef } from 'react';
import type { StompEnvelope } from '@/types/stomp';
import type { Room } from '@/types/api';

/**
 * Phase 13C(#3) — RoomPage 의 2초 `GET /api/rooms/{id}` 폴링을 대체하는 경량 STOMP
 * 구독. `/topic/room/{roomId}/meta` 에서 `ROOM_META_UPDATED`(Room 스냅샷) /
 * `ROOM_DESTROYED` 를 받아 콜백한다. 게임 이벤트용 useStompRoom 과 별개의 가벼운
 * 연결 — RoomPage 가 WAITING/IN_GAME 전 구간에서 메타 변경을 즉시 반영.
 */
export function useRoomMeta(
  roomId: string,
  token: string | null,
  onMeta: (room: Room) => void,
  onDestroyed?: () => void,
) {
  const onMetaRef = useRef(onMeta);
  const onDestroyedRef = useRef(onDestroyed);
  onMetaRef.current = onMeta;
  onDestroyedRef.current = onDestroyed;

  useEffect(() => {
    if (!token) return;
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${proto}//${window.location.host}/ws`;

    const client = new Client({
      brokerURL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 2000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        client.subscribe(`/topic/room/${roomId}/meta`, (frame) => {
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          if (env.type === 'ROOM_META_UPDATED') {
            onMetaRef.current(env.payload as Room);
          } else if (env.type === 'ROOM_DESTROYED') {
            onDestroyedRef.current?.();
          }
        });
      },
    });

    client.activate();
    return () => {
      client.deactivate();
    };
  }, [roomId, token]);
}
