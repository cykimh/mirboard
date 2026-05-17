import { useEffect, useState } from 'react';
import { useRoomChatStore } from './roomChatStore';

interface ArenaChatBubblesProps {
  /** 좌석 인덱스 → userId. RoomChat 과 동일 소스(useRoomChatStore) 구독. */
  playerIds: number[];
  /** 본인 좌석 (없으면 -1 — 관전자). 좌석 회전 매핑 기준. */
  mySeat: number;
}

/** 메시지 노출 수명(ms). 마지막 ~1s 는 fade. */
const TTL_MS = 5000;
const FADE_MS = 1200;
const VIEW_POS = ['s', 'w', 'n', 'e'] as const;

/**
 * Phase 15(#5) — 경기장 내 채팅 말풍선. `.table-arena` 안에 마운트되어 발신자
 * 좌석 근처에 최근 메시지를 잠깐 띄운다(기존 우측 RoomChat 패널은 그대로 유지).
 * 좌석당 최신 1건만 노출해 겹침 방지.
 */
export function ArenaChatBubbles({ playerIds, mySeat }: ArenaChatBubblesProps) {
  const messages = useRoomChatStore((s) => s.messages);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(id);
  }, []);

  const bySeat = new Map<number, (typeof messages)[number]>();
  for (const m of messages) {
    if (now - m.ts > TTL_MS) continue;
    const seat = playerIds.indexOf(m.userId);
    if (seat < 0) continue;
    bySeat.set(seat, m); // messages 는 시간순 → 같은 좌석은 최신으로 덮어씀.
  }
  if (bySeat.size === 0) return null;

  return (
    <>
      {[...bySeat.entries()].map(([seat, m]) => {
        const viewIdx = ((seat - mySeat) + 4) % 4;
        const viewPos = VIEW_POS[viewIdx];
        const fading = now - m.ts > TTL_MS - FADE_MS;
        return (
          <div
            key={seat}
            className={`arena-bubble arena-bubble-${viewPos} ${fading ? 'fading' : ''}`}
          >
            <span className="arena-bubble-name">{m.username}</span>
            <span className="arena-bubble-text">{m.message}</span>
          </div>
        );
      })}
    </>
  );
}
