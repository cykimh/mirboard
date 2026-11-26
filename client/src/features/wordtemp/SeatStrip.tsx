import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import type { SeatChatLine } from '@/features/chat/useSeatChatLines';
import type { SeatView, WordTempPhase } from '@/types/wordtemp';
import { NoteArt } from './wordTempArt';

interface Props {
  seats: SeatView[];
  phase: WordTempPhase | null;
  setterSeat: number;
  mySeat: number;
  hintCount: number;
  maxHints: number;
  guessSeats: number[];
  guessedSeats: number[];
  deltas: Record<number, number>;
  playerIds: number[];
  nameOf: (seat: number) => string;
  tiers: Record<number, Tier>;
  disconnected: Set<number>;
  chatLines?: Record<number, SeatChatLine>;
  onKick?: (userId: number) => void;
}

/** 좌석이 지금 하는 일 — 내용 없이 "하는 중/했어요"만(§12: 누가 골랐는지·냈는지는 공개, 무엇인지는 아니다). 봇은 "생각 중…". */
export function seatStatus(
  s: SeatView,
  p: Pick<Props, 'phase' | 'setterSeat' | 'hintCount' | 'maxHints' | 'guessSeats' | 'guessedSeats'>,
): { text: string; done: boolean } | null {
  if (!s.alive) return null;
  const setter = s.seat === p.setterSeat;
  switch (p.phase) {
    case 'WORD_SELECT':
      return s.chosen ? { text: '골랐어요', done: true } : { text: s.bot ? '생각 중…' : '고르는 중', done: false };
    case 'HINT':
      if (!setter) return { text: '기다리는 중', done: false };
      return { text: `${s.bot ? '생각 중…' : '힌트 내는 중'} ${p.hintCount}/${p.maxHints}`, done: false };
    case 'GUESS':
      if (setter) return { text: '답을 기다려요', done: false };
      if (p.guessedSeats.includes(s.seat)) return { text: '냈어요', done: true };
      if (!p.guessSeats.includes(s.seat)) return null;
      return { text: s.bot ? '생각 중…' : '추리 중', done: false };
    default:
      return null;
  }
}

/**
 * 좌석 띠 — 좌석마다 이름·계급·총점과 지금 하는 일. 출제자에게는 쪽지 표식이 붙는다. 남의 낱말·힌트·답은 여기 오지 않는다(서버가 보내지 않는다).
 */
export function SeatStrip(props: Props) {
  const { seats, setterSeat, mySeat, deltas, playerIds, nameOf, tiers, disconnected, chatLines = {}, onKick } = props;
  return (
    <ol className="wt-strip" aria-label="좌석" data-count={seats.length}>
      {seats.map((s) => {
        const uid = playerIds[s.seat];
        const me = s.seat === mySeat;
        const setter = s.seat === setterSeat;
        const status = seatStatus(s, props);
        const delta = deltas[s.seat] ?? 0;
        return (
          <li
            key={s.seat}
            className={`wt-seat${me ? ' wt-seat-me' : ''}${setter ? ' wt-seat-setter' : ''}${s.alive ? '' : ' wt-seat-out'}${status?.done ? ' wt-seat-done' : ''}`}
            data-seat={s.seat}
          >
            <span className="wt-seat-head">
              {setter && (
                <span className="wt-seat-mark" title="출제자">
                  <NoteArt size={14} />
                  <span className="wt-sr">출제자</span>
                </span>
              )}
              {uid != null && tiers[uid] && <TierBadge tier={tiers[uid]} size={14} />}
              <span className="wt-seat-name">
                {nameOf(s.seat)}
                {me ? ' (나)' : ''}
              </span>
              <span className="wt-seat-points" aria-label={`점수 ${s.total}`}>
                {s.total}점
              </span>
            </span>
            <span className="wt-seat-status">
              {!s.alive && <span className="wt-tag wt-tag-warn">나감</span>}
              {s.alive && disconnected.has(s.seat) && <span className="wt-tag wt-tag-warn">끊김</span>}
              {status && <span className={`wt-seat-doing${status.done ? ' wt-seat-doing-done' : ''}`}>{status.text}</span>}
              {delta > 0 && (
                <span className="wt-seat-delta" aria-label={`${delta}점 얻음`}>
                  +{delta}
                </span>
              )}
              {onKick && !me && s.alive && disconnected.has(s.seat) && uid != null && (
                <button type="button" className="wt-tag wt-tag-btn" onClick={() => onKick(uid)}>
                  내보내기
                </button>
              )}
            </span>
            {chatLines[s.seat] && (
              <span key={chatLines[s.seat].id} className="wt-seat-chat" title={chatLines[s.seat].text}>
                {chatLines[s.seat].text}
              </span>
            )}
          </li>
        );
      })}
    </ol>
  );
}
