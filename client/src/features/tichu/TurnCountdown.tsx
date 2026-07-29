import { useEffect, useState } from 'react';
import { useTichuStore } from './tichuStore';

/**
 * Phase 15(#6) — 경기장 내 턴 카운트다운. 서버 TURN_CHANGED 수신 시각
 * (store.turnStartedAt) + 방 turnSeconds 로 잔여 초를 클라가 로컬 계산.
 * 실제 타임아웃 강제는 서버(TurnTimeoutScheduler) 담당 — 표시는 근사.
 *
 * D-87 에서 GameTable 에서 분리. 동작 불변.
 */
export function TurnCountdown({ turnSeconds }: { turnSeconds: number }) {
  const turnStartedAt = useTichuStore((s) => s.turnStartedAt);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(id);
  }, []);
  if (turnStartedAt == null) return null;
  const elapsed = Math.floor((now - turnStartedAt) / 1000);
  const remaining = Math.max(0, turnSeconds - elapsed);
  const urgent = remaining <= 5;
  return (
    <div className={`turn-countdown ${urgent ? 'urgent' : ''}`} aria-live="off">
      ⏱ {remaining}
    </div>
  );
}
