import { useEffect, useState } from 'react';
import { useArtilleryStore } from './artilleryStore';

export const TURN_URGENT_SECONDS = 5;

/** 차례 남은 시간 — `TURN_CHANGED` 에서 방의 턴 제한부터, resync 면 서버의 남은 시간부터 센다. 0 이 되면 서버가 넘긴다. */
export function ArtilleryCountdown({ turnSeconds }: { turnSeconds: number }) {
  const clock = useArtilleryStore((s) => s.turnClock);
  const full = turnSeconds * 1000;
  const deadline = turnSeconds > 0 && clock ? clock.since + Math.min(clock.remainingMs ?? full, full) : null;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (deadline === null) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [deadline]);
  if (deadline === null) return null;
  const seconds = Math.ceil(Math.max(0, deadline - now) / 1000);
  return (
    <span
      className={`ar-badge ar-countdown${seconds <= TURN_URGENT_SECONDS ? ' ar-countdown-urgent' : ''}`}
      role="timer"
      aria-live="off"
      aria-label={`이번 차례 남은 시간 ${seconds}초`}
      title="0초가 되면 차례가 넘어갑니다"
    >
      <span aria-hidden>⏱ </span>
      {seconds}초
    </span>
  );
}
