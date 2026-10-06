import { Client } from '@stomp/stompjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { roomsApi } from '@/api/rooms';
import { useRoomChatStore } from '@/features/chat/roomChatStore';
import { useReactionStore } from '@/features/chat/reactionStore';
import type { RoomEventSink } from './roomEventSink';
import { judgeSeq } from './seqGate';
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
 * <p><b>D-124: 순번 판정(중복·구멍)은 이 훅만 한다.</b> 기준점은 resync 의 `eventSeq` 이고
 * 판정은 `judgeSeq`(./seqGate) — 게임 스토어는 판정이 끝난 이벤트만 받는 순수 리듀서다.
 *
 * <p><b>D-130 — resync 응답이 기준점보다 낡으면 버린다.</b> 서버는 상태와 순번을 방 락 안에서 함께 읽지만(D-126) REST
 * 응답과 STOMP 프레임의 도착 순서는 정해져 있지 않다 — 늦게 닿은 응답이 이미 반영한 이벤트를 되돌려, 사람 차례에서는
 * 다음 이벤트가 오지 않아 판이 멈췄다. 서버 순번은 방이 살아 있는 동안 줄지 않는다. 방이 바뀐 뒤 도착한 이전 방의
 * 응답도 버린다(방 전환마다 오르는 세대 — 안 그러면 이전 방 응답이 새 방의 기준점을 올려 새 방 스냅샷을 '낡음'으로
 * 버렸다). 정리된 이전 소켓의 콜백(구독·닫힘)도 무시한다(D-130 — `deactivate()` 는 비동기라 DISCONNECT 영수증이 올
 * 때까지 그 소켓의 핸들러가 계속 돈다). 언마운트도 세대를 올려 직전에 보낸 resync 의 늦은 응답을 닫는다. 게임판은
 * `requestResync` 로 권위 스냅샷을 다시 청할 수 있다(게임 스토어가 "다시 받아야 함"을 표시하면 — 훅은 여전히 스토어를
 * 모른다).
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
  /**
   * D-124 — 공개 이벤트 순번의 기준점. 스토어가 아니라 훅이 가진다(판정을 게임마다 복사하지
   * 않도록). resync 스냅샷의 `eventSeq` 가 권위값이고, sink 가 반영했거나 의도적으로 버린
   * 순번 있는 이벤트마다 전진한다.
   */
  const lastSeqRef = useRef(0);
  /**
   * D-130 — 방 전환(reset)마다 오르는 세대. 그 전에 보낸 resync 의 늦은 응답(이전 방 — 토큰이 바뀌었다면 이전 사용자)을
   * 버린다.
   */
  const epochRef = useRef(0);
  const resetChat = useRoomChatStore((s) => s.reset);
  const appendChat = useRoomChatStore((s) => s.appendIncoming);
  const appendReaction = useReactionStore((s) => s.add);
  const resetReactions = useReactionStore((s) => s.reset);
  /** GameTable 에서 채팅 패널 열림 여부를 ref 로 넘겨주면 appendChat 가 unreadCount 분기. */
  const chatPanelOpenRef = useRef(false);

  const resync = useCallback(async () => {
    if (!token) return;
    const epoch = epochRef.current;
    try {
      const snap = await roomsApi.resync<ResyncEnvelope<TTable, TPrivate>>(
        token,
        roomId,
      );
      // D-130 — 그 사이 방이 바뀌었다(reset) — 이전 방의 응답이 새 방의 기준점을 올리지 않게 버린다.
      if (epoch !== epochRef.current) return;
      // D-130 — 이미 반영한 공개 이벤트보다 낡은 응답(그 뒤 프레임이 먼저 닿았다)은 버린다. 같은 순번은 적용한다.
      if (snap.eventSeq < lastSeqRef.current) return;
      // 껍데기를 가공하지 않고 그대로 넘긴다 — 게임별 필드 해석은 sink 책임.
      sinkRef.current.applySnapshot(snap);
      // 순번 기준점은 스냅샷이 다시 세운다 (D-124).
      lastSeqRef.current = snap.eventSeq;
    } catch (err) {
      if (epoch !== epochRef.current) return;
      sinkRef.current.setError((err as Error).message);
    }
  }, [token, roomId]);

  useEffect(() => {
    epochRef.current += 1; // resync() 보다 먼저 — 이 앞에 보낸 resync 의 응답은 이제 낡았다
    sinkRef.current.reset(roomId);
    lastSeqRef.current = 0;
    resetChat(roomId);
    resetReactions();
    resync();
  }, [roomId, resetChat, resetReactions, resync]);

  useEffect(() => {
    if (!token) return;
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${proto}//${window.location.host}/ws`;
    // D-130 — deactivate() 는 비동기라(DISCONNECT 영수증을 기다린다) 정리된 뒤에도 이 소켓의 구독·닫힘 콜백이 한동안 돈다.
    // 그 콜백이 새 방의 상태·연결 표시를 건드리지 않도록 정리된 뒤엔 전부 무시한다. 기준은 `clientRef` 가 아니라 이 지역
    // 플래그다 — 모의 Client 는 activate() 가 onConnect 를 동기로 부르는데 그 시점엔 clientRef 가 아직 비어 있다.
    let disposed = false;

    const client = new Client({
      brokerURL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 2000,
      onConnect: () => {
        if (disposed) return;
        setConnected(true);
        // D-130 — 구독을 모두 보낸 뒤에 resync 한다(맨 끝). resync 를 먼저 보내면 서버가 스냅샷을 읽은 뒤·구독을 등록하기 전에
        // 낸 이벤트가 스냅샷에도 프레임에도 없다. 이 순서는 그 틈을 좁힐 뿐 보장은 아니다 — SUBSCRIBE 등록은 서버에서
        // 비동기이고 단순 브로커는 SUBSCRIBE 영수증을 주지 않는다. 남은 틈은 다음 이벤트의 구멍 판정·탭 복귀 resync·
        // (원카드) 해소 없는 창 resync 가 메운다. 그 사이 닿은 프레임보다 낡은 응답은 위에서 버린다.
        client.subscribe(`/topic/room/${roomId}`, (frame) => {
          if (disposed) return;
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          // D-124 — 순번 판정은 여기서만 한다. sink 는 판정이 끝난 이벤트만 받는다.
          const verdict = judgeSeq(lastSeqRef.current, env.seq);
          if (verdict === 'duplicate') return;
          if (verdict === 'gap') {
            // 놓친 이벤트가 있다 — 권위 스냅샷 재취득(기준점도 스냅샷이 다시 세운다).
            resync();
            return;
          }
          const result = sinkRef.current.applyEvent(env);
          if (result === 'unhandled') {
            // 리듀서 없는 라이프사이클 이벤트 — 권위 스냅샷 재취득. 기준점은 그대로 둔다.
            resync();
            return;
          }
          // 'applied' | 'ignored' — 순번 있는 이벤트면 기준점을 전진시킨다.
          if (verdict === 'next' && typeof env.seq === 'number') lastSeqRef.current = env.seq;
        });
        // 본인 큐는 프레임을 가리지 않고 전량 게임 sink 로 넘긴다 — `ERROR` 까지 포함(D-103).
        // 게임마다 에러 코드가 달라 라벨링 위치가 게임 쪽이어야 하고, 같은 큐인데
        // HAND_DEALT 는 게임이 ERROR 는 훅이 처리하는 비대칭도 없어진다.
        client.subscribe(`/user/queue/room/${roomId}`, (frame) => {
          if (disposed) return;
          const env = JSON.parse(frame.body) as StompEnvelope<unknown>;
          sinkRef.current.applyPrivateEvent(env);
        });
        // Phase 8B — 인-게임 채팅 구독.
        client.subscribe(`/topic/room/${roomId}/chat`, (frame) => {
          if (disposed) return;
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
          if (disposed) return;
          const env = JSON.parse(frame.body) as StompEnvelope<{
            fromSeat: number;
            emoji: string;
          }>;
          if (env.type !== 'REACTION') return;
          appendReaction(env.payload.fromSeat, env.payload.emoji);
        });
        // 연결/재연결 직후 권위 있는 스냅샷으로 순번 기준점 동기화 — 구독을 모두 보낸 뒤에.
        resync();
      },
      onDisconnect: () => {
        if (!disposed) setConnected(false);
      },
      onStompError: () => {
        if (!disposed) setConnected(false);
      },
      // D-130 — 네트워크 끊김·서버 재시작·배포(1006/1001)는 onDisconnect 가 아니라 이것만 부른다(onDisconnect 는 클라가 먼저
      // DISCONNECT 를 보내 영수증을 받을 때만). 없으면 끊긴 뒤에도 "연결"로 보여 재연결 배너가 안 뜨고 보내기가 조용히 버려졌다.
      onWebSocketClose: () => {
        if (!disposed) setConnected(false);
      },
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
      disposed = true;
      // 언마운트·전환·토큰 변경 — 이미 날아간 resync 의 늦은 응답(언마운트 직전에 보낸 것 포함)도 이제 낡았다.
      epochRef.current += 1;
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

  return { connected, sendAction, sendChat, sendReaction, chatPanelOpenRef, requestResync: resync };
}
