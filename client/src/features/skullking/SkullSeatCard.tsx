import { avatarSrc } from '@/api/avatar';
import { animalFor } from '@/components/avatarGlyph';
import { seatAccent } from './seatLayout';
import type { SeatView } from '@/types/skullking';

interface Props {
  seat: SeatView;
  userId?: number;
  username?: string;
  isBot?: boolean;
  isTurn?: boolean;
  isDeserted?: boolean;
  isDisconnected?: boolean;
  /** 전원 제출 후에만 예측값을 보여준다 (§5). */
  bidsRevealed: boolean;
}

/**
 * 상대 좌석 하나. 절대배치가 아니라 **정상 흐름**에 놓이므로 좌석 수가 2~8 로 변해도
 * 그리드가 알아서 줄바꿈한다 (D-103 Row-Flow). 티츄 `SeatAvatar` 를 재사용하지 않는 이유:
 * `seat % 2` 팀색 링이 개인전에서 무의미하고 `size` 가 인라인이라 CSS 로 줄일 수 없다.
 */
export function SkullSeatCard({
  seat,
  userId,
  username,
  isBot = false,
  isTurn = false,
  isDeserted = false,
  isDisconnected = false,
  bidsRevealed,
}: Props) {
  const glyph = isBot ? '🤖' : animalFor(userId, seat.seat);
  const name = username ?? (isBot ? '봇' : `#${userId ?? seat.seat}`);

  const className = [
    'sk-seat',
    isTurn ? 'sk-seat-turn' : '',
    isDeserted ? 'sk-seat-deserted' : '',
    isDisconnected ? 'sk-seat-offline' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      className={className}
      style={{ ['--sk-accent' as string]: seatAccent(seat.seat) }}
      data-seat={seat.seat}
    >
      <div className="sk-seat-top">
        <span className="sk-seat-avatar" aria-hidden>
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
          <span className="sk-seat-glyph">{glyph}</span>
        </span>
        <span className="sk-seat-name" title={name}>
          {name}
        </span>
      </div>

      <div className="sk-seat-stats">
        <span className="sk-stat" title="예측 승수">
          <span className="sk-stat-key" aria-hidden>예</span>
          <span className="sk-stat-val">
            {bidsRevealed && seat.bid !== null
              ? seat.bid
              : seat.hasBid
                ? '제출'
                : '—'}
          </span>
        </span>
        <span className="sk-stat" title="획득 트릭">
          <span className="sk-stat-key" aria-hidden>획</span>
          <span className="sk-stat-val">{seat.tricksWon}</span>
        </span>
        <span className="sk-stat" title="남은 손패">
          <span className="sk-stat-key" aria-hidden>손</span>
          <span className="sk-stat-val">{seat.handCount}</span>
        </span>
      </div>

      {(isDeserted || isDisconnected) && (
        <span className="status-tag sk-seat-tag">
          {isDeserted ? '탈주(자동)' : '연결 끊김'}
        </span>
      )}
    </div>
  );
}
