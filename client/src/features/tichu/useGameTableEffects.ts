import { useEffect, useRef, useState } from 'react';
import type { Card, Hand } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import type { EffectKind, EffectTone } from './effectStore';
import type { TichuRoomState } from './tichuStore';

/** Phase B(D-78) — 비행 카드 오버레이 상태. left/top 은 arena 기준 도착 중심,
 *  dx/dy 는 출발 좌석 중심까지의 오프셋(여기서 0 으로 transition). */
export interface FlyState {
  id: number;
  cards: Card[];
  left: number;
  top: number;
  dx: number;
  dy: number;
  settled: boolean;
}

interface UseGameTableEffectsArgs {
  mySeat: number;
  myTeam: 'A' | 'B';
  myTurn: boolean;
  /** Mahjong 리드 컨텍스트 식별자. 바뀌면 소원 모달 dismiss 를 초기화한다. */
  wishContextKey: string | null;
  setWishModalDismissed: (v: boolean) => void;
  matchEnded: TichuRoomState['matchEnded'];
  triggerEffect: (kind: EffectKind, text?: string, tone?: EffectTone) => void;
  playChime: () => void;
  cardAnimEnabled: boolean;
  currentTop: Hand | null;
  currentTopSeat: number;
  spectator: boolean;
  isInPassing: boolean;
  iAmPassSubmitted: boolean;
  passCardsBySlot: { left: Card | null; partner: Card | null; right: Card | null };
  sendAction: (action: Record<string, unknown>) => void;
}

/**
 * GameTable 의 부수효과 묶음 — 소원 모달 초기화, 매치 종료/내 차례 연출 트리거,
 * 카드 비행 애니메이션, 패스 자동 제출.
 *
 * 애니메이션에 필요한 DOM ref(arena/centerTrick)와 비행 상태를 돌려주므로,
 * 호출부는 ref 를 해당 엘리먼트에 꽂고 fly 를 렌더하기만 하면 된다.
 *
 * D-87 에서 GameTable 에서 분리. 각 effect 본문과 **선언 순서**는 이동 전과
 * 동일하다 — 순서가 바뀌면 실행 순서가 바뀌므로 재배치 금지.
 */
export function useGameTableEffects({
  mySeat,
  myTeam,
  myTurn,
  wishContextKey,
  setWishModalDismissed,
  matchEnded,
  triggerEffect,
  playChime,
  cardAnimEnabled,
  currentTop,
  currentTopSeat,
  spectator,
  isInPassing,
  iAmPassSubmitted,
  passCardsBySlot,
  sendAction,
}: UseGameTableEffectsArgs) {
  useEffect(() => {
    setWishModalDismissed(false);
    // setWishModalDismissed 는 setState 라 안정적 — wishContextKey 변화로만 트리거.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wishContextKey]);

  // 매치 종료 시 연출 1회 트리거. mySeat 으로 승/패/관전(중립) 분기 — 패배 시 트로피
  // 축하가 아니라 차분한 muted 연출(EffectsOverlay 가 tone 으로 렌더).
  useEffect(() => {
    if (!matchEnded) return;
    const won = mySeat >= 0 ? matchEnded.winningTeam === myTeam : null;
    const text =
      won === null ? `Team ${matchEnded.winningTeam} 승리` : won ? '🏆 승리!' : '아쉽게 패배';
    const tone = won === null ? 'neutral' : won ? 'win' : 'lose';
    triggerEffect('MATCH_VICTORY', text, tone);
  }, [matchEnded, myTeam, mySeat, triggerEffect]);

  // P3(9) — 내 차례 진입(상승엣지)에 합성음 + 펄스 배지 1회.
  const prevMyTurnRef = useRef(false);
  useEffect(() => {
    if (myTurn && !prevMyTurnRef.current) {
      playChime();
      triggerEffect('MY_TURN');
    }
    prevMyTurnRef.current = myTurn;
  }, [myTurn, playChime, triggerEffect]);

  // Phase B(D-78) — 카드 제출 시 제출 좌석에서 중앙 트릭으로 날아오는 FLIP 애니.
  // 토글(cardAnimEnabled) ON + reduced-motion 아님일 때만. 비행 중에는 중앙 정적
  // 카드를 visibility:hidden(레이아웃 유지)으로 가려 이중 표시를 방지.
  const arenaRef = useRef<HTMLDivElement | null>(null);
  const centerTrickRef = useRef<HTMLDivElement | null>(null);
  const prevTrickKeyRef = useRef<string | null>(null);
  const flyIdRef = useRef(0);
  const [fly, setFly] = useState<FlyState | null>(null);

  const trickPlayKey = currentTop
    ? `${currentTopSeat}:${currentTop.cards.map(cardKey).join(',')}`
    : null;

  useEffect(() => {
    const prev = prevTrickKeyRef.current;
    prevTrickKeyRef.current = trickPlayKey;
    if (!trickPlayKey || trickPlayKey === prev || !currentTop) return;
    if (!cardAnimEnabled) return;
    const reduce =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce) return;

    const arena = arenaRef.current;
    const center = centerTrickRef.current;
    if (!arena || !center) return;
    const viewIdx = ((currentTopSeat - mySeat) + 4) % 4;
    const viewPos = ['s', 'w', 'n', 'e'][viewIdx];
    const seatEl = arena.querySelector(`.seat-${viewPos}`) as HTMLElement | null;
    if (!seatEl) return;

    const arenaRect = arena.getBoundingClientRect();
    const centerRect = center.getBoundingClientRect();
    const seatRect = seatEl.getBoundingClientRect();
    const centerCx = centerRect.left + centerRect.width / 2;
    const centerCy = centerRect.top + centerRect.height / 2;
    const seatCx = seatRect.left + seatRect.width / 2;
    const seatCy = seatRect.top + seatRect.height / 2;

    setFly({
      id: ++flyIdRef.current,
      cards: currentTop.cards,
      left: centerCx - arenaRect.left,
      top: centerCy - arenaRect.top,
      dx: seatCx - centerCx,
      dy: seatCy - centerCy,
      settled: false,
    });
  }, [trickPlayKey, cardAnimEnabled, currentTop, currentTopSeat, mySeat]);

  // 비행 시작(다음 프레임에 settled=true 로 transition 발동) + 종료 후 정리.
  useEffect(() => {
    if (!fly || fly.settled) return;
    const id = fly.id;
    const raf = requestAnimationFrame(() =>
      requestAnimationFrame(() =>
        setFly((f) => (f && f.id === id ? { ...f, settled: true } : f)),
      ),
    );
    const timer = window.setTimeout(
      () => setFly((f) => (f && f.id === id ? null : f)),
      420,
    );
    return () => {
      cancelAnimationFrame(raf);
      window.clearTimeout(timer);
    };
  }, [fly]);

  // Phase 13(#2) — 패스 3장이 모두 배정되면 별도 제출 버튼 없이 자동 제출.
  // 슬롯 재클릭으로 되돌릴 수 있는 단계가 끝난(3장 확정) 시점이라 안전.
  useEffect(() => {
    if (spectator || !isInPassing || iAmPassSubmitted) return;
    const { left, partner, right } = passCardsBySlot;
    if (left && partner && right) {
      sendAction({
        '@action': 'PASS_CARDS',
        toLeft: left,
        toPartner: partner,
        toRight: right,
      });
    }
    // sendAction 은 안정적 식별자가 아니라 의존성에서 제외 (passSelection 변화로만 트리거).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [passCardsBySlot, spectator, isInPassing, iAmPassSubmitted]);

  return { arenaRef, centerTrickRef, fly };
}
