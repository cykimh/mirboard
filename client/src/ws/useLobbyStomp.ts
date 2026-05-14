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

    const client = new Client({
      brokerURL: brokerUrl,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 2000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/topic/lobby/chat', (frame) => {
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
