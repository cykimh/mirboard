import { useEffect, useState } from 'react';
import { useOneCardStore } from './onecardStore';

/** 이 초 이하로 남으면 강조한다. */
export const TURN_URGENT_SECONDS = 5;

/**
 * D-131 — 지금 차례의 남은 시간. 기준은 서버다: resync 의 `turnRemainingMs` 로 맞추고, 서버가 턴 데드라인을 다시 거는 순간의
 * 공개 이벤트(`TURN_CHANGED`·`PLAYER_ELIMINATED`)에서 방의 턴 제한부터 다시 센다(스토어 `turnClock`). 0 이 되면 서버가 시간
 * 초과를 처리해(먹기) 이벤트가 새 기준을 준다 — 여기서는 0 에서 멈출 뿐이다.
 *
 * <p>턴 제한이 꺼진 방·경쟁 창이 열린 동안(다음 차례가 멈춰 있다)·끝난 판에는 보이지 않는다. 매초 낭독하지 않는다
 * (`aria-live="off"` — 시간 초과가 가까운 것은 강조 색으로만). 다음 정수 초가 바뀌는 순간에만 다시 그린다.
 */
export function OneCardTurnCountdown({ turnSeconds }: { turnSeconds: number }) {
  const clock = useOneCardStore((s) => s.turnClock);
  // 서버 값도 한 턴(방의 턴 제한)을 넘지 않게 자른다 — 무장한 인스턴스와 resync 를 읽은 인스턴스의 시계 차이 등으로 넘으면
  // '31초'가 보였다.
  const fullTurnMs = turnSeconds * 1000;
  const deadline =
    turnSeconds > 0 && clock !== null ? clock.since + Math.min(clock.remainingMs ?? fullTurnMs, fullTurnMs) : null;
  // 마지막 틱의 시각과 그때의 기준. 기준이 바뀐 첫 그리기는 지난 틱의 시각이 아니라 지금 시각으로 센다 — 먹기 뒤
  // TURN_CHANGED 처럼 마운트된 채 기준만 바뀌면, 낡은 시각으로는 방의 턴 제한보다 큰 값(31초)이 한 프레임 보였다.
  const [tick, setTick] = useState(() => ({ deadline, now: Date.now() }));

  useEffect(() => {
    if (deadline === null) return;
    let timer = 0;
    const run = () => {
      const current = Date.now();
      setTick({ deadline, now: current });
      const left = deadline - current;
      if (left <= 0) return;
      // 표시는 올림 초라 다음 정수 초 경계에서만 바뀐다.
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
      className={`oc-badge oc-countdown${urgent ? ' oc-countdown-urgent' : ''}`}
      role="timer"
      aria-live="off"
      aria-label={`이번 차례 남은 시간 ${seconds}초`}
      title="0초가 되면 자동으로 먹습니다"
    >
      <span aria-hidden>⏱ </span>
      <span className="oc-countdown-num">{seconds}</span>초
    </span>
  );
}
