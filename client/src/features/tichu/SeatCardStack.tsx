import type { CSSProperties } from 'react';

const BACK_SRC = '/cards/back.svg';
const MAX_SHOWN = 6;

/**
 * 본인 시점 좌석 위치(S=하단/W=우측/N=상단/E=좌측). 시계 방향이라 E 가 다음 차례(+1), W 가 직전 차례(+3) 상대다
 * (`seatView.viewPosOf`, D-132). 부채 방향을 중앙 지향으로 회전.
 */
export type SeatViewPos = 's' | 'w' | 'n' | 'e';

/**
 * 좌석 앞 손패 수를 실제 카드 뒷면(back.svg) 부채꼴 스택 + 숫자로 직관적으로 표시(#6).
 * "손패 N장" 텍스트를 대체. 장수가 많아도 표시는 MAX_SHOWN 까지만 겹쳐 보여주고
 * 정확한 수는 옆 숫자로.
 *
 * #3 — 부채를 좌석 위치별로 중앙을 향하게 회전(실제 보드게임처럼). s=위·n=아래·
 * w=왼쪽으로 펼침(오른쪽 좌석 — 직전 차례 상대가 중앙을 향함)·e=오른쪽으로 펼침(왼쪽 좌석 — 다음 차례 상대가
 * 중앙을 향함). 실제 회전은 `styles/parts/06-seat-cardstack.css` 의 `.fan-{viewPos}` 가 담당(s 는 기본 위 부채).
 */
export function SeatCardStack({
  count,
  viewPos = 's',
}: {
  count: number;
  viewPos?: SeatViewPos;
}) {
  if (count <= 0) {
    return <div className={`seat-cardstack seat-cardstack-empty fan-${viewPos}`}>—</div>;
  }
  const shown = Math.min(count, MAX_SHOWN);
  const mid = (shown - 1) / 2;

  return (
    <div className={`seat-cardstack fan-${viewPos}`} aria-label={`${count}장`}>
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
      {/* 내 좌석(s)은 실제 손패가 아래에 보이므로 장수 숫자는 생략(요청). */}
      {viewPos !== 's' && <span className="seat-cardstack-count">{count}</span>}
    </div>
  );
}
