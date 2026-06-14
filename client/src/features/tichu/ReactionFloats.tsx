import { useEffect, useState } from 'react';
import { useReactionStore, REACTION_TTL_MS } from '@/features/chat/reactionStore';

const VIEW_POS = ['s', 'w', 'n', 'e'] as const;

/**
 * P2(7) — 좌석에 떠오르는 이모지 반응. `.table-arena` 안에 마운트되어 발신 좌석
 * 근처에서 위로 떠오르며 사라진다(ArenaChatBubbles 와 동일 좌석 좌표 규약). 관전자
 * 발신(fromSeat<0)은 렌더 생략.
 */
export function ReactionFloats({ mySeat }: { mySeat: number }) {
  const recent = useReactionStore((s) => s.recent);
  const prune = useReactionStore((s) => s.prune);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = window.setInterval(() => {
      const n = Date.now();
      prune(n);
      setNow(n);
    }, 400);
    return () => window.clearInterval(id);
  }, [prune]);

  const live = recent.filter((r) => r.fromSeat >= 0 && now - r.ts < REACTION_TTL_MS);
  if (live.length === 0) return null;

  return (
    <>
      {live.map((r) => {
        const viewIdx = ((r.fromSeat - mySeat) + 4) % 4;
        const viewPos = VIEW_POS[viewIdx];
        // 같은 좌석에서 짧게 연속 도착한 반응이 정확히 겹치지 않도록 약간의 가로 분산.
        const dx = ((r.id % 5) - 2) * 16;
        return (
          <div key={r.id} className={`reaction-float reaction-${viewPos}`}>
            <span className="reaction-glyph" style={{ marginLeft: dx }}>
              {r.emoji}
            </span>
          </div>
        );
      })}
    </>
  );
}
