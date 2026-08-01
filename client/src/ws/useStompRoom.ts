import { Client } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { roomsApi } from '@/api/rooms';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useReactionStore } from '@/features/chat/reactionStore';
import type { RoomEventSink } from './roomEventSink';
import type { ResyncEnvelope, StompEnvelope } from '@/types/stomp';

interface ChatPayload {
  userId: number;
  username: string;
  message: string;
}

/**
 * 방의 STOMP 연결 + 이벤트 디스패치. 공개 토픽과 본인 큐를 동시 구독.
 *
 * Phase 5d 부터: 공개 이벤트는 `sink.applyEvent` 로 부분 패치를 시도하고, 리듀서가
 * 없는 라이프사이클 이벤트 / seq gap / unknown 일 때만 /resync 로 권위 있는
 * 스냅샷 재취득. 초기 mount 와 STOMP onConnect 는 항상 /resync (재접속 안전망).
 *
 * <p><b>D-103: 본 훅은 게임을 모른다.</b> 과거 `useTichuStore` 를 직접 구독해 두 번째 게임의
 * 이벤트를 받을 자리가 없었다. 지금은 {@link RoomEventSink} 를 주입받고, 게임별 sink 가
 * 스토어에 꽂는다. 채팅·리액션·재접속 재가동은 게임과 무관하므로 그대로 훅에 남는다.
 *
 * @param sink 게임별 이벤트 싱크. **모듈 상수**를 넘길 것 — 규약은
 *             {@link RoomEventSink} javadoc 참조.
 */
export function useStompRoom<TTable = unknown, TPrivate = unknown>(
  roomId: string,
  token: string | null,
  sink: RoomEventSink<TTable, TPrivate>,
) {
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  // sink 를 ref 에 담아 effect 의존성에서 뺀다 — 호출부가 인라인 객체를 넘겨도 소켓
  // 재연결·reset·resync 루프가 원리적으로 불가능해진다. 대가는 stale closure 위험이고,
  // 그래서 sink 메서드는 호출 시점에 getState() 를 읽어야 한다(RoomEventSink 규약 2).
  const sinkRef = useRef(sink);
  sinkRef.current = sink;
  const resetChat = useRoomChatStore((s) => s.reset);
  const appendChat = useRoomChatStore((s) => s.appendIncoming);
  const appendReaction = useReactionStore((s) => s.add);
  const resetReactions = useReactionStore((s) => s.reset);
  /** GameTable 에서 채팅 패널 열림 여부를 ref 로 넘겨주면 appendChat 가 unreadCount 분기. */
  const chatPanelOpenRef = useRef(false);

  const resync = useCallback(async () => {
    if (!token) return;
    try {
      const snap = await roomsApi.resync<ResyncEnvelope<TTable, TPrivate>>(
        token,
        roomId,
      );
      // 껍데기를 가공하지 않고 그대로 넘긴다 — 게임별 필드 해석은 sink 책임.
      sinkRef.current.applySnapshot(snap);
    } catch (err) {
      sinkRef.current.setError((err as Error).message);
    }
  }, [token, roomId]);

  useEffect(() => {
    sinkRef.current.reset(roomId);
    resetChat(roomId);
    resetReactions();
    resync();
  }, [roomId, resetChat, resetReactions, resync]);

  useEffect(() => {
    if (!token) return;
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${proto}//${window.location.host}/ws`;

    const client = new Client({
      brokerURL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 2000,
      onConnect: () => {
        setConnected(true);
        // 연결/재연결 직후 권위 있는 스냅샷으로 lastSeq 동기화.
        resync();
        client.subscribe(`/topic/room/${roomId}`, (frame) => {
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          const result = sinkRef.current.applyEvent(env);
          if (result === 'unhandled' || result === 'gap') {
            // 라이프사이클 이벤트 또는 갭 — 권위 있는 스냅샷 재취득.
            resync();
          }
          // 'applied' / 'duplicate' 인 경우엔 추가 동작 없음.
        });
        // 본인 큐는 프레임을 가리지 않고 전량 게임 sink 로 넘긴다 — `ERROR` 까지 포함(D-103).
        // 게임마다 에러 코드가 달라 라벨링 위치가 게임 쪽이어야 하고, 같은 큐인데
        // HAND_DEALT 는 게임이 ERROR 는 훅이 처리하는 비대칭도 없어진다.
        client.subscribe(`/user/queue/room/${roomId}`, (frame) => {
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          sinkRef.current.applyPrivateEvent(env);
        });
        // Phase 8B — 인-게임 채팅 구독.
        client.subscribe(`/topic/room/${roomId}/chat`, (frame) => {
          const env = JSON.parse(frame.body) as StompEnvelope<ChatPayload>;
          if (env.type !== 'CHAT') return;
          appendChat(
            {
              eventId: env.eventId,
              ts: env.ts,
              userId: env.payload.userId,
              username: env.payload.username,
              message: env.payload.message,
            },
            chatPanelOpenRef.current,
          );
        });
        // P2(7) — 이모지 반응 구독.
        client.subscribe(`/topic/room/${roomId}/reaction`, (frame) => {
          const env = JSON.parse(frame.body) as StompEnvelope<{
            fromSeat: number;
            emoji: string;
          }>;
          if (env.type !== 'REACTION') return;
          appendReaction(env.payload.fromSeat, env.payload.emoji);
        });
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    });
    client.activate();
    clientRef.current = client;

    // 모바일 백그라운드(앱 전환·화면 잠금) 시 OS 가 소켓을 죽이는데, 게임 소켓엔
    // 하트비트가 없어 클라가 끊김을 모를 수 있다. 포그라운드 복귀/네트워크 회복 때
    // 소켓을 즉시 재가동하고 권위 스냅샷으로 화면을 곧바로 최신화(resync 는 REST 라
    // 소켓 상태와 무관). 이렇게 해야 탈주 유예가 끝나기 전에 빠르게 재접속된다.
    const onResume = () => {
      if (document.visibilityState !== 'visible') return;
      client.activate(); // 이미 active 면 no-op, 끊겼으면 재연결을 앞당김
      resync();
    };
    document.addEventListener('visibilitychange', onResume);
    window.addEventListener('online', onResume);

    return () => {
      document.removeEventListener('visibilitychange', onResume);
      window.removeEventListener('online', onResume);
      client.deactivate();
      clientRef.current = null;
      setConnected(false);
    };
  }, [token, roomId, resync]);

  const sendAction = useCallback(
    (action: Record<string, unknown>) => {
      const client = clientRef.current;
      if (!client?.connected) return;
      client.publish({
        destination: `/app/room/${roomId}/action`,
        body: JSON.stringify(action),
      });
    },
    [roomId],
  );

  const sendChat = useCallback(
    (message: string) => {
      const client = clientRef.current;
      if (!client?.connected) return;
      const trimmed = message.trim();
      if (!trimmed) return;
      client.publish({
        destination: `/app/room/${roomId}/chat`,
        body: JSON.stringify({ message: trimmed.slice(0, 500) }),
      });
    },
    [roomId],
  );

  const sendReaction = useCallback(
    (emoji: string) => {
      const client = clientRef.current;
      if (!client?.connected) return;
      client.publish({
        destination: `/app/room/${roomId}/reaction`,
        body: JSON.stringify({ emoji }),
      });
    },
    [roomId],
  );

  return { connected, sendAction, sendChat, sendReaction, chatPanelOpenRef };
}
