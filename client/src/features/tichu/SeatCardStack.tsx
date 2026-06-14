import type { CSSProperties } from 'react';

const BACK_SRC = '/cards/back.svg';
const MAX_SHOWN = 6;

/**
 * 좌석 앞 손패 수를 실제 카드 뒷면(back.svg) 부채꼴 스택 + 숫자로 직관적으로 표시(#6).
 * "손패 N장" 텍스트를 대체. 장수가 많아도 표시는 MAX_SHOWN 까지만 겹쳐 보여주고
 * 정확한 수는 옆 숫자로.
 */
export function SeatCardStack({ count }: { count: number }) {
  if (count <= 0) {
    return <div className="seat-cardstack seat-cardstack-empty">—</div>;
  }
  const shown = Math.min(count, MAX_SHOWN);
  const mid = (shown - 1) / 2;

  return (
    <div className="seat-cardstack" aria-label={`${count}장`}>
      <span className="seat-cardstack-fan">
        {Array.from({ length: shown }).map((_, i) => (
          <img
            key={i}
            src={BACK_SRC}
            alt=""
            className="seat-cardback"
            draggable={false}
            style={{ '--i': i - mid } as CSSProperties}
          />
        ))}
      </span>
      <span className="seat-cardstack-count">{count}</span>
    </div>
  );
}
