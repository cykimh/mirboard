import { useEffect, useState } from 'react';
import { useDambadukStore } from './dambadukStore';

export const TURN_URGENT_SECONDS = 5;

/**
 * D-131 — 지금 차례의 남은 시간(오목 `OmokTurnCountdown` 과 같은 계산 — 기준은 서버). resync 의 `turnRemainingMs` 로 맞추고
 * `TURN_CHANGED` 에서 방의 턴 제한부터 다시 센다. 0 이 되면 서버가 자동 한 수를 둔다(같은 사람 연속 두 번째면 빠진다 — 룰 §8).
 */
export function DambadukTurnCountdown({ turnSeconds }: { turnSeconds: number }) {
  const clock = useDambadukStore((s) => s.turnClock);
  const fullTurnMs = turnSeconds * 1000;
  const deadline =
    turnSeconds > 0 && clock !== null ? clock.since + Math.min(clock.remainingMs ?? fullTurnMs, fullTurnMs) : null;
  const [tick, setTick] = useState(() => ({ deadline, now: Date.now() }));

  useEffect(() => {
    if (deadline === null) return;
    let timer = 0;
    const run = () => {
      const current = Date.now();
      setTick({ deadline, now: current });
      const left = deadline - current;
      if (left <= 0) return;
      timer = window.setTimeout(run, left % 1000 || 1000);
    };
    run();
    return () => window.clearTimeout(timer);
  }, [deadline]);

  if (deadline === null) return null;
  const now = tick.deadline === deadline ? tick.now : Date.now();
  const seconds = Math.ceil(Math.max(0, deadline - now) / 1000);
  return (
    <span
      className={`db-badge db-countdown${seconds <= TURN_URGENT_SECONDS ? ' db-countdown-urgent' : ''}`}
      role="timer"
      aria-live="off"
      aria-label={`이번 차례 남은 시간 ${seconds}초`}
      title="0초가 되면 자동으로 한 수 둡니다(같은 사람 연속 두 번째면 빠집니다)"
    >
      <span aria-hidden>⏱ </span>
      {seconds}초
    </span>
  );
}
