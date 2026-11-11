import { useEffect, useRef, useState } from 'react';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { GoldBurst } from './GoldBurst';
import { salonTheme } from './yachtTheme';
import type { MatchResult } from '@/types/yacht';

const REASON_TEXT: Record<MatchResult['reason'], string> = {
  FINISHED: '12칸을 모두 채웠습니다.',
  NO_HUMANS: '사람이 모두 나가 판이 끝났습니다.',
  ACTION_LIMIT: '진행이 너무 길어져 판을 끝냈습니다.',
};

interface Props {
  result: MatchResult;
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
  onBackToRoom?: () => void;
  /** D-201 — 판을 시작할 때의 내 최고 점수(`null` = 기록 없음, `undefined` = 모름·관전). */
  previousBest?: number | null;
  /** D-201 — 게스트는 최고 기록이 남지 않는다. */
  guest?: boolean;
}

/**
 * D-201 — 종료 화면의 최고 기록 한 줄. 끝까지 친 판(`FINISHED`)에서 탈주하지 않은 내 총점만 기록이 된다(서버 `YachtBestScore` 와 같은 셈).
 */
export function bestLine(
  result: MatchResult,
  mySeat: number,
  previousBest: number | null | undefined,
  guest: boolean,
): { text: string; record: boolean } | null {
  const mine = result.placements.find((p) => p.seat === mySeat);
  if (!mine) return null;
  if (guest) return { text: '게스트는 최고 기록이 남지 않아요.', record: false };
  if (previousBest === undefined) return null;
  const counts = result.reason === 'FINISHED' && !mine.deserted;
  if (counts && (previousBest === null || mine.total > previousBest)) {
    return {
      text: previousBest === null ? `첫 최고 기록 ${mine.total}점!` : `새 최고 기록! (이전 ${previousBest}점)`,
      record: true,
    };
  }
  return previousBest === null ? null : { text: `내 최고 ${previousBest}점`, record: false };
}

/** 순위 카드 — 게임판을 유지한 채 보여 준다(D-120·D-153). 1인 판은 내 총점만. */
export function YachtMatchEnd({
  result,
  mySeat,
  nameOf,
  onExit,
  onBackToRoom,
  previousBest,
  guest = false,
}: Props) {
  const solo = result.placements.length === 1;
  const best = bestLine(result, mySeat, previousBest, guest);
  // 결과 카드는 점수표 아래라 휴대폰에서는 화면 밖이다 — 뜨는 순간 한 번 보이는 곳으로 올린다(D-203).
  const ref = useRef<HTMLElement>(null);
  // 내가 1위(여럿 판)이거나 새 최고 기록이면 카드 위에서 금가루가 한 번 터진다 — 카드가 자리를 잡은 뒤에.
  const animate = useCardAnimStore((st) => st.enabled);
  const mine = result.placements.find((p) => p.seat === mySeat);
  const cheer = salonTheme && animate && ((!solo && mine?.rank === 1 && !mine.deserted) || best?.record === true);
  const [burst, setBurst] = useState<{ x: number; y: number } | null>(null);
  useEffect(() => {
    ref.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
    if (!cheer) return;
    const timer = window.setTimeout(() => {
      const r = ref.current?.getBoundingClientRect();
      if (r) setBurst({ x: r.left + r.width / 2, y: Math.max(40, r.top + 30) });
    }, 450);
    return () => window.clearTimeout(timer);
    // 결과 카드가 뜰 때 한 번.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <section className={`yt-end${solo ? ' yt-end-solo' : ''}`} aria-label="게임 결과" ref={ref}>
      <h2 className="yt-end-title">{solo ? `총점 ${result.placements[0].total}점` : '결과'}</h2>
      <p className="yt-end-reason">{REASON_TEXT[result.reason]}</p>
      {best && <p className={`yt-end-best${best.record ? ' yt-end-best-new' : ''}`}>{best.text}</p>}
      {!solo && (
        <ol className="yt-end-list">
          {result.placements.map((p) => (
            <li
              key={p.seat}
              className={`yt-end-row${p.seat === mySeat ? ' yt-end-me' : ''}${!p.deserted && p.rank === 1 ? ' yt-end-first' : ''}`}
            >
              <span className="yt-end-rank">{p.deserted ? '탈주' : `${p.rank}위`}</span>
              <span className="yt-end-name">
                {nameOf(p.seat)}
                {p.seat === mySeat ? ' (나)' : ''}
              </span>
              <span className="yt-end-total">{p.total}점</span>
            </li>
          ))}
        </ol>
      )}
      {solo && <p className="yt-end-note">혼자 한 판은 승패 없이 최고 기록에만 남습니다.</p>}
      {burst && <GoldBurst x={burst.x} y={burst.y} count={120} power={0.9} />}
      <div className="yt-end-actions">
        {onBackToRoom && (
          <button type="button" className="yt-roll" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {onExit && (
          <button type="button" className="yt-badge" onClick={onExit}>
            메인으로
          </button>
        )}
      </div>
    </section>
  );
}
