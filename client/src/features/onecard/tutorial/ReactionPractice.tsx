import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { RACE_SLOT_COUNT, raceLeftCss, racePosition } from '../raceSlots';

/** 버튼이 뜨기까지의 대기(ms) — 매번 달라 미리 누를 수 없다. */
export const PRACTICE_MIN_DELAY_MS = 600;
export const PRACTICE_DELAY_SPAN_MS = 1200;
/** 실제 경쟁 창과 같은 3초. */
export const PRACTICE_WINDOW_MS = 3000;

type Phase = 'idle' | 'waiting' | 'showing' | 'done';

interface Props {
  /** 테스트에서 고정한다. */
  random?: () => number;
}

/**
 * D-129 — '원카드!' 반응 연습. **로컬 전용**이다 — 소켓·스토어를 쓰지 않는다(`ReactionPractice.test` 가 원문으로
 * 확인). 실제 경쟁처럼 무작위 대기 뒤 같은 슬롯 표(`raceSlots`)의 무작위 자리에 버튼이 뜨고, 누르기까지 걸린 시간을
 * 보여 준다. 3초가 지나면 실제 창처럼 닫힌다.
 */
export function ReactionPractice({ random = Math.random }: Props) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [pos, setPos] = useState({ left: 50, top: 50 });
  const [result, setResult] = useState<number | null>(null);
  const shownAt = useRef(0);
  const timer = useRef<number | null>(null);

  const clearTimer = () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => clearTimer, []);

  const start = () => {
    clearTimer();
    setResult(null);
    setPhase('waiting');
    timer.current = window.setTimeout(
      () => {
        const p = racePosition(
          Math.floor(random() * RACE_SLOT_COUNT),
          Math.round(random() * 200 - 100),
          Math.round(random() * 200 - 100),
        );
        // 슬롯 표는 화면 위쪽(세로 5~60%)을 쓴다 — 연습 칸 높이에 맞게 늘인다.
        setPos({ left: p.left, top: 10 + (p.top / 60) * 80 });
        shownAt.current = Date.now();
        setPhase('showing');
        timer.current = window.setTimeout(() => {
          timer.current = null;
          setPhase('done');
        }, PRACTICE_WINDOW_MS);
      },
      PRACTICE_MIN_DELAY_MS + Math.floor(random() * PRACTICE_DELAY_SPAN_MS),
    );
  };

  const press = () => {
    clearTimer();
    setResult(Date.now() - shownAt.current);
    setPhase('done');
  };

  // 버튼이 뜬 순간도 알린다 — 실전 경쟁이 aria-live 로 창이 열린 사실을 알리는 것과 같다(연습도 같아야 연습이 된다).
  const status =
    phase === 'waiting'
      ? '곧 나타납니다…'
      : phase === 'showing'
        ? '버튼이 나타났습니다 — 지금 누르세요!'
        : phase === 'done'
          ? result !== null
            ? `반응 시간 ${result}ms — 봇은 1.0~2.5초 사이에 누릅니다.`
            : '3초가 지났습니다 — 실제 판이면 벌칙 없이 닫힙니다.'
          : '';

  return (
    <div className="tutorial-practice">
      <p>시작을 누르고, 버튼이 나타나면 최대한 빨리 누르세요. 위치는 매번 바뀝니다.</p>
      <div className="oc-practice-area">
        {phase === 'showing' && (
          <button
            type="button"
            className="oc-race-btn oc-race-call"
            style={{ left: raceLeftCss(pos.left), top: `${pos.top}%` }}
            onClick={press}
          >
            <span className="oc-race-label">원카드!</span>
          </button>
        )}
      </div>
      <p role="status" aria-live="polite" style={{ minHeight: '1.5em', textAlign: 'center' }}>
        {status}
      </p>
      <div style={{ display: 'flex', justifyContent: 'center' }}>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={phase === 'waiting' || phase === 'showing'}
          onClick={start}
        >
          {phase === 'idle' ? '시작' : '다시'}
        </Button>
      </div>
    </div>
  );
}
