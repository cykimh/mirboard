import { avatarSrc } from '@/api/avatar';
import { animalFor } from '@/components/avatarGlyph';
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
  isDisconnected?: boolean;
}

/**
 * 상대 좌석 하나 — 이름과 남은 손패 **장수**만 보인다(State Hiding). 1장 남으면 표시를 세우고, 탈락하면 사유를 단다.
 * 스컬킹처럼 정상 흐름의 `auto-fit` 그리드에 놓여 2~6인이 폭 미디어 없이 줄바꿈된다.
 */
export function OneCardSeat({
  seat,
  userId,
  username,
  isBot = false,
  isTurn = false,
  isDisconnected = false,
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
    >
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

      <div className="oc-seat-count" title="남은 손패">
        <span className="oc-seat-count-num">{seat.handCount}</span>
        <span className="oc-seat-count-unit">장</span>
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
