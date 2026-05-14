import { Client } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { roomsApi } from '@/api/rooms';
import { useTichuStore } from '@/features/tichu/tichuStore';
import type { Card, ResyncResponse } from '@/types/tichu';
import type { StompEnvelope } from '@/types/stomp';

interface RoundEndedPayload {
  score: {
    teamAScore: number;
    teamBScore: number;
    firstFinisherSeat: number;
  };
}

interface HandDealtPayload {
  seat: number;
  cards: Card[];
}

interface ErrorPayload {
  code: string;
  message: string;
}

/**
 * 방의 STOMP 연결 + 이벤트 디스패치. 공개 토픽과 본인 큐를 동시 구독.
 * 액션 후 매번 `/resync` 로 최신 스냅샷을 가져오는 보수적 전략 — 라이브 이벤트 patch
 * 는 후속 작업 (4f). 단조 증가 `seq` 는 patch 도입 시 사용.
 */
export function useStompRoom(roomId: string, token: string | null) {
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);
  const applySnapshot = useTichuStore((s) => s.applySnapshot);
  const applyPrivateHand = useTichuStore((s) => s.applyPrivateHand);
  const setError = useTichuStore((s) => s.setError);
  const setRoundEnded = useTichuStore((s) => s.setRoundEnded);
  const reset = useTichuStore((s) => s.reset);

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
    resync();
  }, [roomId, reset, resync]);

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
        resync();
        client.subscribe(`/topic/room/${roomId}`, (frame) => {
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          if (env.type === 'ROUND_ENDED') {
            const payload = env.payload as RoundEndedPayload;
            setRoundEnded(payload.score);
          } else {
            // For other public events (PLAYED, PASSED, TURN_CHANGED, ...) MVP
            // simply re-fetches the authoritative table view.
            resync();
          }
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
  }, [token, roomId, resync, applyPrivateHand, setError, setRoundEnded]);

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

  return { connected, sendAction };
}
