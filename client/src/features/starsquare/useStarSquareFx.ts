import { useCallback, useEffect, useState } from 'react';
import { playCues } from '@/features/sound/tableSfx';
import { starSquareLocalCues } from './starSquareSfx';
import { useStarSquareStore } from './starSquareStore';

/** 무대를 가로지르는 별똥별 하나(화면 전용). */
export interface MeteorFx {
  id: number;
  seat: number;
  mine: boolean;
  /** 떨어지는 길을 조금씩 달리한다(0~3). */
  lane: number;
}

/** 동시에 그리는 별똥별 상한 — 봇 여럿이 한꺼번에 풀어도 쌓이지 않게. */
export const METEOR_MAX = 3;

let meteorSeq = 0;

/**
 * 별자리 수판의 <b>화면 전용</b> 연출 상태(D-286). 스토어를 구독해 바뀐 순간만 잡는다 — 게임 상태를 바꾸지 않고, 보내기를 늦추지 않으며,
 * 타이머를 만들지 않는다(별똥별은 CSS 애니메이션이 끝나면 `dismiss` 로 지운다 — 컴포넌트보다 오래 사는 것이 없다).
 *
 * <ul>
 *   <li>내 입력 효과음 — 넣기·지우기·되돌리기·새 별자리 선·완성({@link starSquareLocalCues}). 소리는 음소거만 따른다(연출 끄기와 무관).</li>
 *   <li>별똥별 — 어느 좌석이 새로 완성하면 하나. `animate` 가 꺼져 있으면 만들지 않는다.</li>
 * </ul>
 */
export function useStarSquareFx(animate: boolean) {
  const [meteors, setMeteors] = useState<MeteorFx[]>([]);

  useEffect(() => {
    return useStarSquareStore.subscribe((st, prev) => {
      if (st.board !== prev.board) playCues(starSquareLocalCues(prev, st));
      if (st.seats === prev.seats && st.puzzleIndex === prev.puzzleIndex && st.phase === prev.phase) return;
      // 퍼즐이 바뀌거나 푸는 단계가 끝나면 남은 별똥별을 치운다
      if (st.puzzleIndex !== prev.puzzleIndex || st.phase !== 'PLAYING') {
        setMeteors((cur) => (cur.length ? [] : cur));
        return;
      }
      if (!animate || prev.phase !== 'PLAYING') return;
      const fresh = st.seats.filter((s) => s.solved && prev.seats.find((p) => p.seat === s.seat)?.solved === false);
      if (fresh.length === 0) return;
      setMeteors((cur) =>
        [
          ...cur,
          ...fresh.map((s) => {
            const id = ++meteorSeq;
            return { id, seat: s.seat, mine: s.seat === st.mySeat, lane: id % 4 };
          }),
        ].slice(-METEOR_MAX),
      );
    });
  }, [animate]);

  const dismiss = useCallback((id: number) => {
    setMeteors((cur) => cur.filter((m) => m.id !== id));
  }, []);

  return { meteors: animate ? meteors : [], dismiss };
}
