import { avatarSrc } from '@/api/avatar';
import { animalFor } from '@/components/avatarGlyph';
import type { TableSide } from '@/components/seatOrder';
import type { EliminationReason, OneCardSeatView } from '@/types/onecard';

const ACCENTS = [
  'var(--oc-accent-0)',
  'var(--oc-accent-1)',
  'var(--oc-accent-2)',
  'var(--oc-accent-3)',
  'var(--oc-accent-4)',
  'var(--oc-accent-5)',
];

/** 좌석별 accent 색 — 좌석 카드와 내 정보줄 테두리를 잇는다. */
export function seatAccent(seat: number): string {
  return ACCENTS[((seat % ACCENTS.length) + ACCENTS.length) % ACCENTS.length];
}

/** D-151 — 좌석에 그리는 뒷면 최대 장수. 넘으면 줄임표(…) — 많을 때 장수를 하나하나 보는 의미가 없다. */
export const SEAT_HAND_SHOWN_MAX = 10;

export const ELIMINATED_LABEL: Record<EliminationReason, string> = {
  BANKRUPT: '파산',
  DESERTED: '탈주',
};

interface Props {
  seat: OneCardSeatView;
  userId?: number;
  username?: string;
  isBot?: boolean;
  isTurn?: boolean;
  /** D-132 — 일반 진행 기준 다음 차례. */
  isNext?: boolean;
  isDisconnected?: boolean;
  /** D-132 — 테이블 자리(관전자의 아래 좌석은 `bottom`). 자리는 게임판의 옆줄 래퍼가 잡고, 이 값은 표식이다. */
  side?: TableSide | 'bottom';
  /** 옆줄 안 순서(시계 방향, 0 부터). */
  slot?: number;
}

/**
 * 상대 좌석 하나 — 이름과 남은 손패 **장수**만 보인다(State Hiding, D-144 — 장수만큼 작은 뒷면). 1장 남으면 표시를 세우고, 탈락하면 사유를 단다.
 * 테이블(D-132)의 옆줄·윗줄에 놓인다 — 옆줄 칸은 좁아질 수 있어 이름은 말줄임하고 `title` 로 전체 이름을 보인다.
 */
export function OneCardSeat({
  seat,
  userId,
  username,
  isBot = false,
  isTurn = false,
  isNext = false,
  isDisconnected = false,
  side,
  slot,
}: Props) {
  const glyph = isBot ? '🤖' : animalFor(userId, seat.seat);
  const name = username ?? (isBot ? '봇' : `#${userId ?? seat.seat}`);
  const out = seat.eliminated !== null;
  const last = !out && seat.handCount === 1;

  const className = [
    'oc-seat',
    isTurn ? 'oc-seat-turn' : '',
    out ? 'oc-seat-out' : '',
    isDisconnected ? 'oc-seat-offline' : '',
    last ? 'oc-seat-last' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      className={className}
      style={{ ['--oc-accent' as string]: seatAccent(seat.seat) }}
      data-seat={seat.seat}
      data-side={side}
      data-slot={slot}
    >
      {isNext && <span className="oc-badge oc-seat-next">다음</span>}
      <div className="oc-seat-top">
        <span className="oc-seat-avatar" aria-hidden>
          {!isBot && userId != null ? (
            <img
              src={avatarSrc(userId)}
              alt=""
              draggable={false}
              onError={(e) => {
                (e.currentTarget as HTMLImageElement).style.display = 'none';
              }}
            />
          ) : null}
          <span className="oc-seat-glyph">{glyph}</span>
        </span>
        <span className="oc-seat-name" title={name}>
          {name}
        </span>
      </div>

      {/* D-144 — 숫자 대신 장수만큼 작은 뒷면. 정확한 장수는 보조기기·마우스 오버로. */}
      <div className="oc-seat-count">
        <span
          className="oc-seat-hand"
          role="img"
          aria-label={`남은 손패 ${seat.handCount}장`}
          title={`남은 손패 ${seat.handCount}장`}
          style={{ ['--oc-hand-count' as string]: Math.min(seat.handCount, SEAT_HAND_SHOWN_MAX) }}
        >
          {Array.from({ length: Math.min(seat.handCount, SEAT_HAND_SHOWN_MAX) }, (_, i) => (
            <span key={i} className="oc-seat-hand-card" />
          ))}
          {seat.handCount > SEAT_HAND_SHOWN_MAX && <span className="oc-seat-hand-more">…</span>}
        </span>
        {last && <span className="oc-seat-one">1장!</span>}
      </div>

      {(out || isDisconnected) && (
        <span className="status-tag oc-seat-tag">
          {out ? ELIMINATED_LABEL[seat.eliminated!] : '연결 끊김'}
        </span>
      )}
    </div>
  );
}
