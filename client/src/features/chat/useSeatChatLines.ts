import { useEffect, useRef, useState } from 'react';
import { useRoomChatStore } from './roomChatStore';

/** 좌석 위 한 줄이 떠 있는 시간. 티츄 경기장 말풍선(`ArenaChatBubbles`)과 같다. */
export const SEAT_CHAT_TTL_MS = 5000;

export interface SeatChatLine {
  /** 같은 좌석에 새 줄이 오면 바뀐다 — 다시 그릴 key. */
  id: string;
  text: string;
}

/**
 * D-163 — 좌석마다 최근 채팅 한 줄(원카드·스컬킹). 보낸 사람 좌석(나 포함)에 최신 1건을 {@link SEAT_CHAT_TTL_MS} 동안 돌려준다.
 * 시각은 서버 `ts` 가 아니라 **이 화면이 받은 때**로 잰다 — 휴대폰 시계가 몇 초만 달라도 서버 시각으로는 아예 안 뜨거나 오래 남는다.
 * 훅이 붙기 전에 쌓인 메시지(입장·재접속)는 띄우지 않는다. 채팅 창은 그대로다(같은 `useRoomChatStore`).
 */
export function useSeatChatLines(playerIds: number[]): Record<number, SeatChatLine> {
  const messages = useRoomChatStore((s) => s.messages);
  const seenAt = useRef<Map<string, number> | null>(null);
  // 떠 있는 줄이 있는 동안 0.5초마다 다시 그려 만료된 줄을 내린다.
  const [, setTick] = useState(0);

  // 처음 붙을 때 이미 있던 메시지는 "오래전에 받은 것"으로 둔다.
  if (seenAt.current === null) {
    seenAt.current = new Map(messages.map((m) => [m.eventId, 0]));
  }
  for (const m of messages) {
    if (!seenAt.current.has(m.eventId)) seenAt.current.set(m.eventId, Date.now());
  }

  const clock = Date.now();
  const fresh = (eventId: string) => clock - (seenAt.current!.get(eventId) ?? 0) <= SEAT_CHAT_TTL_MS;
  const live = messages.some((m) => fresh(m.eventId));
  useEffect(() => {
    if (!live) return;
    const id = window.setInterval(() => setTick((t) => t + 1), 500);
    return () => window.clearInterval(id);
  }, [live]);

  const lines: Record<number, SeatChatLine> = {};
  for (const m of messages) {
    if (!fresh(m.eventId)) continue;
    const seat = playerIds.indexOf(m.userId);
    if (seat < 0) continue;
    lines[seat] = { id: m.eventId, text: m.message }; // 시간순 — 같은 좌석은 최신으로 덮는다.
  }
  return lines;
}
