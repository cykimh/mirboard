import { useEffect, useState } from 'react';
import { useYachtStore } from './yachtStore';

export const TURN_URGENT_SECONDS = 5;

/**
 * 차례 전체 남은 시간(D-183). 기준은 서버다 — resync 의 `tableView.turnRemainingMs`(엔진 계산)로 맞추고 `TURN_CHANGED` 에서 방의
 * 턴 제한부터 다시 센다. 굴림·홀드로는 다시 세지 않는다(서버도 그렇다). 0 이 되면 서버가 자동 기록한다.
 * 원카드 `OneCardTurnCountdown` 과 같은 방식이고 `aria-live="off"`(매초 낭독하지 않는다).
 */
export function YachtTurnCountdown({ turnSeconds }: { turnSeconds: number }) {
  const clock = useYachtStore((s) => s.turnClock);
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
  const urgent = seconds <= TURN_URGENT_SECONDS;
  return (
    <span
      className={`yt-badge yt-countdown${urgent ? ' yt-countdown-urgent' : ''}`}
      role="timer"
      aria-live="off"
      aria-label={`이번 차례 남은 시간 ${seconds}초`}
      title="0초가 되면 점수가 가장 높은 칸에 자동으로 기록합니다"
    >
      <span className="yt-countdown-icon" aria-hidden>
        ⏱{' '}
      </span>
      {seconds}초
    </span>
  );
}
