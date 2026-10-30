import type { SeatChatLine } from '@/features/chat/useSeatChatLines';
import { avatarSrc } from '@/api/avatar';
import { animalFor } from '@/components/avatarGlyph';
import type { TableSide } from '@/components/seatOrder';
import { seatAccent } from './seatLayout';
import type { SeatView } from '@/types/skullking';

interface Props {
  seat: SeatView;
  userId?: number;
  username?: string;
  isBot?: boolean;
  isTurn?: boolean;
  /** D-132 — 일반 진행 기준 다음 차례. */
  isNext?: boolean;
  isDeserted?: boolean;
  isDisconnected?: boolean;
  /** 전원 제출 후에만 예측값을 보여준다 (§5). */
  bidsRevealed: boolean;
  /**
   * D-132 — 테이블 자리(관전자의 아래 좌석은 `bottom`). 옆줄(left·right)이면 `sk-seat-side` 를 달아 좁은 화면에서 손패 장수를
   * 숨긴다(17-responsive 끝 규칙).
   */
  side?: TableSide | 'bottom';
  /** 옆줄 안 순서(시계 방향, 0 부터). */
  slot?: number;
  /** D-156 — 음성 대화 참여 중(on) / 나와의 연결 실패(failed). */
  voice?: 'on' | 'failed';
  /** D-162 — 방장만: 끊긴 사람을 내보낸다(바로 탈주 처리). */
  onKick?: () => void;
  /** D-163 — 이 사람의 최근 채팅 한 줄(5초). */
  chat?: SeatChatLine;
}

/**
 * 상대 좌석 하나. 절대배치가 아니라 테이블 격자(D-132)의 옆줄·윗줄에 **정상 흐름**으로 놓인다 — 옆줄 칸은 좁아질 수
 * 있어 이름은 말줄임하고 `title` 로 전체 이름을 보인다. 티츄 `SeatAvatar` 를 재사용하지 않는 이유:
 * `seat % 2` 팀색 링이 개인전에서 무의미하고 `size` 가 인라인이라 CSS 로 줄일 수 없다.
 */
export function SkullSeatCard({
  seat,
  userId,
  username,
  isBot = false,
  isTurn = false,
  isNext = false,
  isDeserted = false,
  isDisconnected = false,
  bidsRevealed,
  side,
  slot,
  voice,
  onKick,
  chat,
}: Props) {
  const glyph = isBot ? '🤖' : animalFor(userId, seat.seat);
  const name = username ?? (isBot ? '봇' : `#${userId ?? seat.seat}`);

  const className = [
    'sk-seat',
    isTurn ? 'sk-seat-turn' : '',
    side === 'left' || side === 'right' ? 'sk-seat-side' : '',
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
      data-side={side}
      data-slot={slot}
    >
      {isNext && <span className="sk-badge sk-seat-next">다음</span>}
      {chat && (
        <span key={chat.id} className="sk-seat-chat" title={chat.text}>
          {chat.text}
        </span>
      )}
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
        {voice && (
          <span
            className={`sk-seat-voice${voice === 'failed' ? ' sk-seat-voice-failed' : ''}`}
            title={voice === 'failed' ? '음성 연결 안 됨' : '음성 대화 참여 중'}
            aria-label={voice === 'failed' ? '음성 연결 안 됨' : '음성 대화 참여 중'}
          >
            {voice === 'failed' ? '🎧✕' : '🎧'}
          </span>
        )}
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
        <span className="sk-stat sk-stat-hand" title="남은 손패">
          <span className="sk-stat-key" aria-hidden>손</span>
          <span className="sk-stat-val">{seat.handCount}</span>
        </span>
      </div>

      {(isDeserted || isDisconnected) && (
        <span className="status-tag sk-seat-tag">
          {isDeserted ? '탈주(자동)' : '연결 끊김'}
        </span>
      )}
      {!isDeserted && isDisconnected && onKick && (
        <button type="button" className="sk-badge sk-seat-kick" onClick={onKick}>
          내보내기
        </button>
      )}
    </div>
  );
}
