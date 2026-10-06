import { Client } from '@stomp/stompjs';
import { useEffect, useRef, useState } from 'react';
import type { LobbyChatPayload, StompEnvelope } from '@/types/stomp';

export interface LobbyChatMessage {
  eventId: string;
  ts: number;
  userId: number;
  username: string;
  message: string;
}

const MAX_MESSAGES = 200;

export function useLobbyStomp(token: string | null) {
  const [messages, setMessages] = useState<LobbyChatMessage[]>([]);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    if (!token) return;

    const brokerUrl = (() => {
      if (typeof window === 'undefined') return 'ws://localhost:8080/ws';
      const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      return `${proto}//${window.location.host}/ws`;
    })();

    // D-130 — deactivate() 는 비동기라 정리된 뒤에도 이 소켓의 콜백이 한동안 돈다(DISCONNECT 영수증을 기다린다).
    // 토큰이 바뀐 뒤 늦게 닿은 이전 소켓의 영수증·닫힘이 새 소켓의 connected 를 내리지 않게 정리된 뒤엔 무시한다.
    let disposed = false;
    const client = new Client({
      brokerURL: brokerUrl,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 2000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        if (disposed) return;
        setConnected(true);
        client.subscribe('/topic/lobby/chat', (frame) => {
          if (disposed) return;
          const env = JSON.parse(frame.body) as StompEnvelope<LobbyChatPayload>;
          if (env.type !== 'CHAT') return;
          const msg: LobbyChatMessage = {
            eventId: env.eventId,
            ts: env.ts,
            userId: env.payload.userId,
            username: env.payload.username,
            message: env.payload.message,
          };
          setMessages((prev) => [...prev, msg].slice(-MAX_MESSAGES));
        });
      },
      onDisconnect: () => {
        if (!disposed) setConnected(false);
      },
      onStompError: () => {
        if (!disposed) setConnected(false);
      },
      // D-130 — 갑작스러운 끊김(네트워크·서버 재시작)은 onDisconnect 가 아니라 이것만 부른다.
      onWebSocketClose: () => {
        if (!disposed) setConnected(false);
      },
    });

    client.activate();
    clientRef.current = client;
    return () => {
      disposed = true;
      client.deactivate();
      clientRef.current = null;
      setConnected(false);
    };
  }, [token]);

  function send(message: string) {
    const client = clientRef.current;
    if (!client || !client.connected) return;
    client.publish({
      destination: '/app/lobby/chat',
      body: JSON.stringify({ message }),
    });
  }

  return { messages, connected, send };
}
