import { useEffect, useRef, useState } from 'react';
import type { PlayedCardView, SkullKingPhase } from '@/types/skullking';

/**
 * "촛불 선장실" 연출 보조 (D-231). **화면 전용** — 서버 판정·차례·타이머를 건드리지 않고 입력도 막지 않는다. 여기 값은 이미
 * 받은 공개 정보에서 파생한 표시일 뿐이다.
 */

const isPirate = (pc: PlayedCardView) =>
  pc.card.special === 'PIRATE' || (pc.card.special === 'TIGRESS' && pc.declaredAs === 'PIRATE');

/**
 * 끝난 트릭의 포획 보너스 (룰 §11 — 스컬킹이 잡은 해적 1장당 30, 해적이 잡은 인어 1장당 20, 인어가 잡은 스컬킹 40).
 * 트릭 위에 "+30" 을 띄우는 표시용이다 — 실제 점수는 서버가 라운드 정산에서 내고, 예측을 못 맞히면 0 이 된다(§10).
 * 색상 14 보너스는 여기서 세지 않는다(포획 연출만).
 */
export function captureBonus(cards: PlayedCardView[], winnerSeat: number): number {
  const winner = cards.find((pc) => pc.seat === winnerSeat);
  if (!winner) return 0;
  const count = (pred: (pc: PlayedCardView) => boolean) => cards.filter(pred).length;
  if (winner.card.special === 'SKULL_KING') return 30 * count(isPirate);
  if (winner.card.special === 'MERMAID') return count((pc) => pc.card.special === 'SKULL_KING') > 0 ? 40 : 0;
  if (isPirate(winner)) return 20 * count((pc) => pc.card.special === 'MERMAID');
  return 0;
}

/** 예측 공개 양피지가 떠 있는 시간(ms). CSS `sk-fx-reveal` 애니메이션 길이와 같다. */
export const BID_REVEAL_MS = 2200;

/**
 * 이 세션이 예측 단계 → 플레이 단계 전이를 **직접 본** 때에만 잠깐 값이 된다(재접속·새로고침으로 플레이 중에 들어오면 뜨지
 * 않는다). 값은 공개된 라운드 번호 — 양피지의 key 로 쓴다.
 */
export function useBidReveal(phase: SkullKingPhase | null, roundNumber: number): number | null {
  const prev = useRef<SkullKingPhase | null>(phase);
  const [shown, setShown] = useState<number | null>(null);

  useEffect(() => {
    const was = prev.current;
    prev.current = phase;
    if (was === 'BIDDING' && phase === 'PLAYING') setShown(roundNumber);
  }, [phase, roundNumber]);

  useEffect(() => {
    if (shown === null) return;
    const t = window.setTimeout(() => setShown(null), BID_REVEAL_MS);
    return () => window.clearTimeout(t);
  }, [shown]);

  return shown;
}
