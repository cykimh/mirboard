import { Client } from '@stomp/stompjs';
import { useEffect, useRef } from 'react';
import type { StompEnvelope } from '@/types/stomp';
import type { Room } from '@/types/api';
import { runResumeGate, watchHiddenIdle } from './hiddenIdle';

/**
 * Phase 13C(#3) — RoomPage 의 2초 `GET /api/rooms/{id}` 폴링을 대체하는 경량 STOMP
 * 구독. `/topic/room/{roomId}/meta` 에서 `ROOM_META_UPDATED`(Room 스냅샷) /
 * `ROOM_DESTROYED` 를 받아 콜백한다. 게임 이벤트용 useStompRoom 과 별개의 가벼운
 * 연결 — RoomPage 가 WAITING/IN_GAME 전 구간에서 메타 변경을 즉시 반영.
 *
 * <p>D-134 — 휴대폰이 백그라운드로 가면 소켓이 죽고(D-79) 그동안의 메타(준비 해제·퇴장·방 소멸)를 놓친다. 다시 구독만 해서는
 * 놓친 것을 받을 길이 없으므로 **다시 연결됐을 때**(첫 연결 말고)·**화면이 다시 보일 때**·**온라인이 됐을 때** `onResync` 로
 * 방 화면에 다시 맞추라고 알린다. 첫 연결에서는 부르지 않는다 — 방 화면이 막 `join-or-reconnect` 로 방을 받았다.
 */
export function useRoomMeta(
  roomId: string,
  token: string | null,
  onMeta: (room: Room) => void,
  onDestroyed?: () => void,
  onResync?: () => void,
) {
  const onMetaRef = useRef(onMeta);
  const onDestroyedRef = useRef(onDestroyed);
  const onResyncRef = useRef(onResync);
  onMetaRef.current = onMeta;
  onDestroyedRef.current = onDestroyed;
  onResyncRef.current = onResync;

  useEffect(() => {
    if (!token) return;
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${proto}//${window.location.host}/ws`;
    // 이 소켓이 한 번이라도 연결됐는가 — 두 번째 onConnect 부터가 "다시 연결".
    let connectedOnce = false;

    const client = new Client({
      brokerURL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 2000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      discardWebsocketOnCommFailure: true, // D-158 — 하트비트를 놓치면 소켓을 바로 버리고 다시 붙는다
      onConnect: () => {
        client.subscribe(`/topic/room/${roomId}/meta`, (frame) => {
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          if (env.type === 'ROOM_META_UPDATED') {
            onMetaRef.current(env.payload as Room);
          } else if (env.type === 'ROOM_DESTROYED') {
            onDestroyedRef.current?.();
          }
        });
        // 구독을 보낸 뒤에 — 다시 맞춘 뒤 바뀐 메타는 이 구독이 받는다.
        if (connectedOnce) onResyncRef.current?.();
        connectedOnce = true;
      },
    });

    client.activate();

    let disposed = false;
    // D-273 — 숨긴 탭이라 스스로 끊었는가. 그렇다면 방 화면 재동기화(관전 목록 복귀 포함 — `setResumeGate`)를 기다린 뒤 다시 붙인다.
    // 다시 붙으면 onConnect 가 한 번 더 onResync 한다(겹치면 진행 중인 것을 기다릴 뿐).
    let idleDropped = false;
    const onResume = () => {
      if (document.visibilityState !== 'visible') return;
      if (idleDropped) {
        idleDropped = false;
        void runResumeGate().then(() => {
          if (!disposed && document.visibilityState === 'visible') client.activate();
          else if (!disposed) idleDropped = true;
        });
        return;
      }
      onResyncRef.current?.();
    };
    document.addEventListener('visibilitychange', onResume);
    window.addEventListener('online', onResume);
    // D-273 — 오래 숨긴 탭은 스스로 끊어 운영 서버가 잠들게 한다.
    const stopIdleWatch = watchHiddenIdle(() => {
      if (disposed) return;
      idleDropped = true;
      void client.deactivate();
    });

    return () => {
      disposed = true;
      stopIdleWatch();
      document.removeEventListener('visibilitychange', onResume);
      window.removeEventListener('online', onResume);
      client.deactivate();
    };
  }, [roomId, token]);
}
