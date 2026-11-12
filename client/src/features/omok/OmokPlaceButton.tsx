import { useEffect, useRef, useState } from 'react';

/** D-195 — [두기]를 이만큼 누르고 있으면 강하게 두기. 이 상수 하나로 조절한다. */
export const SLAM_HOLD_MS = 1500;

interface Props {
  disabled: boolean;
  /** 짧게 떼면 `false`(보통 두기), 끝까지 누르면 `true`(강하게 두기). */
  onPlace: (slam: boolean) => void;
  /** 누르는 중인지 — 판의 미리보기 돌이 달아오르게. */
  onChargeChange?: (charging: boolean) => void;
}

/**
 * [두기] 버튼 (D-195). 짧게 누르면 보통 두기, {@link SLAM_HOLD_MS} 동안 누르고 있으면 게이지가 다 차는 순간 강하게 두기로 확정한다.
 * 터치·마우스는 포인터로, 키보드는 Space·Enter 길게(반복 입력은 무시). 버튼 밖으로 손가락이 나가거나 포인터가 취소되면 아무것도 두지
 * 않는다. 길게 눌러도 문맥 메뉴·텍스트 선택이 뜨지 않게 막는다(CSS 와 함께). 버튼 클릭 이벤트는 쓰지 않는다 — 누름·뗌으로만 정한다.
 */
export function OmokPlaceButton({ disabled, onPlace, onChargeChange }: Props) {
  const [charging, setCharging] = useState(false);
  const timer = useRef<number | null>(null);
  const pressing = useRef(false);
  const onPlaceRef = useRef(onPlace);
  onPlaceRef.current = onPlace;
  const onChargeRef = useRef(onChargeChange);
  onChargeRef.current = onChargeChange;

  const setCharge = (on: boolean) => {
    setCharging(on);
    onChargeRef.current?.(on);
  };

  const clear = () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  };

  const start = () => {
    if (disabled || pressing.current) return;
    pressing.current = true;
    setCharge(true);
    timer.current = window.setTimeout(() => {
      timer.current = null;
      pressing.current = false;
      setCharge(false);
      onPlaceRef.current(true);
    }, SLAM_HOLD_MS);
  };

  /** 누름이 끝났다 — `commit` 이면 아직 다 차지 않았을 때 보통 두기. */
  const finish = (commit: boolean) => {
    if (!pressing.current) return;
    pressing.current = false;
    clear();
    setCharge(false);
    if (commit) onPlaceRef.current(false);
  };

  // 누르는 사이 둘 수 없게 되면(차례가 넘어감·끊김) 조용히 그친다. 사라질 때도 타이머를 지운다.
  useEffect(() => {
    if (disabled) finish(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [disabled]);
  useEffect(() => clear, []);

  const isKey = (e: React.KeyboardEvent) => e.key === ' ' || e.key === 'Enter';

  return (
    <button
      type="button"
      className={`om-primary om-place${charging ? ' om-charging' : ''}`}
      style={{ '--om-hold-ms': `${SLAM_HOLD_MS}ms` } as React.CSSProperties}
      disabled={disabled}
      aria-label={charging ? '강하게 두기 모으는 중' : '여기에 두기 — 길게 누르면 강하게'}
      onPointerDown={(e) => {
        if (e.pointerType === 'mouse' && e.button !== 0) return;
        start();
      }}
      onPointerUp={() => finish(true)}
      onPointerLeave={() => finish(false)}
      onPointerCancel={() => finish(false)}
      onKeyDown={(e) => {
        if (!isKey(e)) return;
        e.preventDefault();
        if (!e.repeat) start();
      }}
      onKeyUp={(e) => {
        if (!isKey(e)) return;
        e.preventDefault();
        finish(true);
      }}
      onBlur={() => finish(false)}
      onContextMenu={(e) => e.preventDefault()}
    >
      <span className="om-hold-gauge" aria-hidden="true" />
      <span className="om-place-label">{charging ? '💥 꾹… 강하게!' : '여기에 두기'}</span>
      <span className="om-place-hint" aria-hidden="true">
        {charging ? '끝까지 누르고 있으면 강하게' : '꾹 누르면 강하게'}
      </span>
    </button>
  );
}
