import { useEffect, useRef, useState, type RefObject } from 'react';
import type { OneCardCard } from '@/types/onecard';
import type { LastPlay } from './onecardStore';

/** 날아가는 시간(ms). CSS `.oc-fly-settled` 의 transition 과 같은 값. */
export const CARD_FLIGHT_MS = 380;

export interface CardFlight {
  /** 낸 이벤트의 순번 — 한 비행의 식별자. */
  id: number;
  card: OneCardCard;
  /** D-139 후속 — 날아오는 동안 가운데에 그대로 둘 내기 전 맨 위 카드. */
  under: LastPlay['under'];
  fromSeat: number;
  /** 도착점(맨 위 카드 가운데) — 게임판 루트 기준. */
  left: number;
  top: number;
  /** 출발점 − 도착점. 처음 프레임은 출발점에 두고 settled 가 되면 도착점으로 transition 한다(FLIP). */
  dx: number;
  dy: number;
  settled: boolean;
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

function nextFrame(run: () => void): () => void {
  if (typeof window.requestAnimationFrame === 'function') {
    let inner = 0;
    const outer = window.requestAnimationFrame(() => {
      inner = window.requestAnimationFrame(run);
    });
    return () => {
      window.cancelAnimationFrame(outer);
      window.cancelAnimationFrame(inner);
    };
  }
  const timer = window.setTimeout(run, 16);
  return () => window.clearTimeout(timer);
}

/**
 * D-139 — 카드를 내면 낸 사람의 좌석 패널(내가 냈으면 내 줄 `.oc-me`)에서 가운데 맨 위 카드(`.oc-top`) 자리로 카드를 날린다(티츄 D-78 의
 * FLIP 과 같은 방식). 근거는 스토어의 방금 낸 카드(`lastPlay` — 공개 이벤트 순번)라 순번이 바뀔 때만 날고, 처음 그릴 때·resync 로는 날지 않는다.
 * 카드 애니메이션 설정(`enabled`)이 꺼져 있거나 OS 가 동작 줄이기를 원하면 날리지 않는다.
 */
export function useCardPlayFlight(
  rootRef: RefObject<HTMLElement | null>,
  lastPlay: LastPlay | null,
  mySeat: number,
  enabled: boolean,
): CardFlight | null {
  const prevSeq = useRef<number | null>(lastPlay?.seq ?? null);
  const [flight, setFlight] = useState<CardFlight | null>(null);

  useEffect(() => {
    const seq = lastPlay?.seq ?? null;
    if (seq === prevSeq.current) return;
    prevSeq.current = seq;
    if (!lastPlay || !enabled || prefersReducedMotion()) return;
    const root = rootRef.current;
    if (!root) return;
    const target = root.querySelector('.oc-top');
    const source =
      lastPlay.seat === mySeat
        ? root.querySelector('.oc-me')
        : root.querySelector(`.oc-felt [data-seat="${lastPlay.seat}"]`);
    if (!target || !source) return;

    const rootRect = root.getBoundingClientRect();
    const t = target.getBoundingClientRect();
    const s = source.getBoundingClientRect();
    const tx = t.left + t.width / 2;
    const ty = t.top + t.height / 2;
    setFlight({
      id: lastPlay.seq,
      card: lastPlay.card,
      under: lastPlay.under,
      fromSeat: lastPlay.seat,
      left: tx - rootRect.left,
      top: ty - rootRect.top,
      dx: s.left + s.width / 2 - tx,
      dy: s.top + s.height / 2 - ty,
      settled: false,
    });
  }, [lastPlay, enabled, mySeat, rootRef]);

  // 다음 프레임에 settled 로 바꿔 transition 을 걸고, 다 날면 걷는다. 비행 id 에만 묶는다(티츄와 같은 이유 — settled 전이가 정리 타이머를 지우지 않게).
  const flightId = flight?.id ?? null;
  useEffect(() => {
    if (flightId === null) return;
    const cancel = nextFrame(() => setFlight((f) => (f && f.id === flightId ? { ...f, settled: true } : f)));
    const timer = window.setTimeout(
      () => setFlight((f) => (f && f.id === flightId ? null : f)),
      CARD_FLIGHT_MS + 80,
    );
    return () => {
      cancel();
      window.clearTimeout(timer);
    };
  }, [flightId]);

  return flight;
}
