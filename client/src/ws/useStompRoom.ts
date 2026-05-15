import { Client } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { roomsApi } from '@/api/rooms';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useTichuStore } from '@/features/tichu/tichuStore';
import type { Card, ResyncResponse } from '@/types/tichu';
import type { StompEnvelope } from '@/types/stomp';

interface HandDealtPayload {
  seat: number;
  cards: Card[];
}

interface ErrorPayload {
  code: string;
  message: string;
}

interface ChatPayload {
  userId: number;
  username: string;
  message: string;
}

/**
 * 방의 STOMP 연결 + 이벤트 디스패치. 공개 토픽과 본인 큐를 동시 구독.
 *
 * Phase 5d 부터: 공개 이벤트는 store.applyEvent 로 부분 패치를 시도하고, 리듀서가
 * 없는 라이프사이클 이벤트 / seq gap / unknown 일 때만 /resync 로 권위 있는
 * 스냅샷 재취득. 초기 mount 와 STOMP onConnect 는 항상 /resync (재접속 안전망).
 */
export function useStompRoom(roomId: string, token: string | null) {
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const applySnapshot = useTichuStore((s) => s.applySnapshot);
  const applyPrivateHand = useTichuStore((s) => s.applyPrivateHand);
  const applyEvent = useTichuStore((s) => s.applyEvent);
  const setError = useTichuStore((s) => s.setError);
  const reset = useTichuStore((s) => s.reset);
  const resetChat = useRoomChatStore((s) => s.reset);
  const appendChat = useRoomChatStore((s) => s.appendIncoming);
  /** GameTable 에서 채팅 패널 열림 여부를 ref 로 넘겨주면 appendChat 가 unreadCount 분기. */
  const chatPanelOpenRef = useRef(false);

  const resync = useCallback(async () => {
    if (!token) return;
    try {
      const snap = await roomsApi.resync<ResyncResponse>(token, roomId);
      applySnapshot({
        tableView: snap.tableView,
        privateHand: snap.privateHand,
        eventSeq: snap.eventSeq,
      });
    } catch (err) {
      setError((err as Error).message);
    }
  }, [token, roomId, applySnapshot, setError]);

  useEffect(() => {
    reset(roomId);
    resetChat(roomId);
    resync();
  }, [roomId, reset, resetChat, resync]);

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
          const result = applyEvent(env);
          if (result === 'unhandled' || result === 'gap') {
            // 라이프사이클 이벤트 또는 갭 — 권위 있는 스냅샷 재취득.
            resync();
          }
          // 'applied' / 'duplicate' 인 경우엔 추가 동작 없음.
        });
        client.subscribe(`/user/queue/room/${roomId}`, (frame) => {
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          if (env.type === 'HAND_DEALT') {
            const payload = env.payload as HandDealtPayload;
            applyPrivateHand(payload);
          } else if (env.type === 'ERROR') {
            const payload = env.payload as ErrorPayload;
            setError(`${payload.code}: ${payload.message}`);
          }
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
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    });
    client.activate();
    clientRef.current = client;
    return () => {
      client.deactivate();
      clientRef.current = null;
      setConnected(false);
    };
  }, [token, roomId, resync, applyEvent, applyPrivateHand, setError]);

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

  return { connected, sendAction, sendChat, chatPanelOpenRef };
}
