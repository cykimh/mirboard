import { useEffect, useState } from 'react';
import { playTableSfx } from '@/features/sound/tableSfx';
import { useYachtStore } from './yachtStore';

export const TURN_URGENT_SECONDS = 5;
/** D-293 — 내 차례가 이만큼 남으면 큰 숫자와 소리로 센다(3·2·1). */
export const FINAL_COUNT_SECONDS = 3;

/**
 * 차례 전체 남은 시간(초, 올림). 기준은 서버다 — resync 의 `tableView.turnRemainingMs`(엔진 계산)로 맞추고 `TURN_CHANGED` 에서 방의
 * 턴 제한부터 다시 센다. 굴림·홀드로는 다시 세지 않는다(서버도 그렇다). 턴 제한이 없거나 시계가 없으면 null.
 * 시계 배지와 마지막 3초 연출(D-293)이 이 훅 하나를 같이 써서 두 숫자가 어긋나지 않는다.
 */
export function useTurnSecondsLeft(turnSeconds: number): number | null {
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
  return Math.ceil(Math.max(0, deadline - now) / 1000);
}

/**
 * 차례 전체 남은 시간(D-183). 0 이 되면 서버가 자동 기록한다.
 * 원카드 `OneCardTurnCountdown` 과 같은 방식이고 `aria-live="off"`(매초 낭독하지 않는다).
 */
export function YachtTurnCountdown({ turnSeconds }: { turnSeconds: number }) {
  const seconds = useTurnSecondsLeft(turnSeconds);
  if (seconds === null) return null;
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

// D-293 — 이미 울린 숫자(차례 하나에 숫자마다 한 번). 모듈에 둬서 게임판이 다시 그려지거나 resync 로 시계가 조금 물러나도 다시
// 울리지 않는다. 게임판은 한 번에 하나만 떠 있다.
let rung: { key: string; seconds: Set<number> } = { key: '', seconds: new Set() };

/** 테스트용 — 울린 기록을 비운다. */
export function resetFinalCount() {
  rung = { key: '', seconds: new Set() };
}

interface FinalCountProps {
  turnSeconds: number;
  /** 내 차례이고 판이 진행 중일 때만 true — 아니면 숫자도 소리도 없다. */
  active: boolean;
  /** 차례 하나를 가리키는 값(방·좌석·라운드). 같은 값의 같은 숫자는 한 번만 울린다. */
  turnKey: string;
  /** 연출 설정 — 꺼져 있으면 큰 숫자가 움직임 없이 바뀌기만 한다(소리는 음소거만 따른다). */
  animate: boolean;
}

/**
 * D-293 — 내 차례 마지막 3초: 주사위 판 가운데의 큰 3·2·1 + 초마다 짧은 "똑"(1 은 조금 높게). 꾸밈이라 `aria-hidden` 이다
 * (남은 시간은 시계 배지가 읽어 준다). 눌림을 가로채지 않는다(CSS `pointer-events: none`).
 */
export function YachtFinalCount({ turnSeconds, active, turnKey, animate }: FinalCountProps) {
  const seconds = useTurnSecondsLeft(turnSeconds);
  const count = active && seconds !== null && seconds >= 1 && seconds <= FINAL_COUNT_SECONDS ? seconds : null;

  // 리뷰 — 판이 끝나면 기록을 비운다: 같은 방에서 다음 판(D-153)을 하면 좌석·라운드 번호가 같은 차례가 다시 오는데, 지난 판의 기록이
  // 남아 있으면 3·2·1 이 소리 없이 지나갔다. 차례 키가 바뀐 것만으로는 비우지 않는다 — 게임판이 다시 붙을 때(스토어 초기화 → 스냅샷)
  // 키가 잠깐 흔들려도 같은 차례의 숫자를 또 울리지 않게, 기록의 주인은 **울릴 때만** 바꾼다(아래 효과).
  const ended = useYachtStore((s) => s.result !== null);
  useEffect(() => {
    if (ended) resetFinalCount();
  }, [ended]);

  useEffect(() => {
    if (count === null) return;
    if (rung.key !== turnKey) rung = { key: turnKey, seconds: new Set() };
    if (rung.seconds.has(count)) return;
    rung.seconds.add(count);
    playTableSfx(count === 1 ? 'clock.last' : 'clock.tick');
  }, [count, turnKey]);

  if (count === null) return null;
  return (
    <span key={count} className={`yt-final${animate ? ' yt-final-live' : ''}${count === 1 ? ' yt-final-last' : ''}`} aria-hidden="true">
      {count}
    </span>
  );
}
