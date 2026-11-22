import { useEffect, useRef, useState } from 'react';
import type { Tier } from '@/api/users';
import { TierBadge } from '@/components/TierBadge';
import type { SeatChatLine } from '@/features/chat/useSeatChatLines';
import type { SeatView } from '@/types/starsquare';
import { TelescopeArt } from './starSquareArt';
import { clockText } from './starSquareMath';

interface Props {
  seats: SeatView[];
  n: number;
  mySeat: number;
  playerIds: number[];
  nameOf: (seat: number) => string;
  tiers: Record<number, Tier>;
  disconnected: Set<number>;
  /** 결과 보기·끝난 매치 — 진행도 대신 점수 합만. */
  showProgress: boolean;
  chatLines?: Record<number, SeatChatLine>;
  onKick?: (userId: number) => void;
}

const NONE: ReadonlySet<number> = new Set();

/**
 * 이 화면이 떠 있는 동안 <b>새로</b> 완성한 좌석(화면 전용 — 별똥별·도장 찍힘 연출). 처음 그릴 때 이미 완성돼 있던 좌석은 넣지 않는다
 * (다시 들어온 사람에게 지난 완성을 다시 터뜨리지 않는다). 완성이 풀리면(다음 퍼즐) 빠진다.
 */
function useFreshSolved(seats: SeatView[]): ReadonlySet<number> {
  const prev = useRef<Map<number, boolean> | null>(null);
  const [fresh, setFresh] = useState<ReadonlySet<number>>(NONE);
  useEffect(() => {
    const before = prev.current;
    prev.current = new Map(seats.map((s) => [s.seat, s.solved]));
    if (!before) return;
    setFresh((cur) => {
      const next = new Set<number>();
      for (const s of seats) {
        if (s.solved && (cur.has(s.seat) || before.get(s.seat) === false)) next.add(s.seat);
      }
      return next.size === cur.size && [...next].every((x) => cur.has(x)) ? cur : next;
    });
  }, [seats]);
  return fresh;
}

/**
 * 좌석 띠(§3.2) — 좌석마다 이름·계급, <b>채운 칸·맞은 줄</b>(남의 판 숫자는 서버가 보내지 않는다 — §5.1), 완성 도장(순서·시간), 점수 합.
 */
export function RivalStrip({ seats, n, mySeat, playerIds, nameOf, tiers, disconnected, showProgress, chatLines = {}, onKick }: Props) {
  const nn = n * n;
  const lines = 2 * n + 2;
  const fresh = useFreshSolved(seats);
  return (
    <ol className="sq-strip" aria-label="모두의 진행">
      {seats.map((s) => {
        const uid = playerIds[s.seat];
        const me = s.seat === mySeat;
        const ratio = nn > 0 ? Math.min(1, s.filled / nn) : 0;
        return (
          <li
            key={s.seat}
            className={`sq-seat${me ? ' sq-seat-me' : ''}${s.solved ? ' sq-seat-solved' : ''}${s.deserted ? ' sq-seat-out' : ''}${fresh.has(s.seat) ? ' sq-seat-fresh' : ''}`}
            data-seat={s.seat}
            style={{ ['--sq-fill' as string]: ratio }}
          >
            {/* 새로 완성한 순간의 별똥별 — 장식(눌림 통과) */}
            {fresh.has(s.seat) && <span className="sq-seat-meteor" aria-hidden="true" />}
            <span className="sq-seat-head">
              <TelescopeArt className="sq-seat-scope" size={16} />
              {uid != null && tiers[uid] && <TierBadge tier={tiers[uid]} size={14} />}
              <span className="sq-seat-name">
                {nameOf(s.seat)}
                {me ? ' (나)' : ''}
              </span>
              <span className="sq-seat-points" aria-label={`점수 ${s.points}`}>
                {s.points}점
              </span>
            </span>
            {showProgress && !s.deserted && (
              <span className="sq-seat-progress">
                {s.solved ? (
                  <span className="sq-seat-stamp" aria-label={`${s.solvePlace}번째 완성, ${clockText(s.elapsedMs)}`}>
                    {s.solvePlace}등 · {clockText(s.elapsedMs)}
                  </span>
                ) : (
                  <span className="sq-seat-count">
                    <span aria-label={`맞은 줄 ${s.lines} / ${lines}`}>
                      ✦ {s.lines}/{lines}줄
                    </span>
                    <span aria-label={`채운 칸 ${s.filled} / ${nn}`}>
                      {' · '}
                      {s.filled}/{nn}칸
                    </span>
                  </span>
                )}
                <span className="sq-seat-bar" aria-hidden="true" />
              </span>
            )}
            {s.deserted && <span className="sq-badge sq-badge-warn">나감</span>}
            {!s.deserted && disconnected.has(s.seat) && <span className="sq-badge sq-badge-warn">끊김</span>}
            {onKick && !me && !s.deserted && disconnected.has(s.seat) && uid != null && (
              <button type="button" className="sq-badge" onClick={() => onKick(uid)}>
                내보내기
              </button>
            )}
            {chatLines[s.seat] && (
              <span key={chatLines[s.seat].id} className="sq-seat-chat" title={chatLines[s.seat].text}>
                {chatLines[s.seat].text}
              </span>
            )}
          </li>
        );
      })}
    </ol>
  );
}
