import { useEffect, useRef, useState, type RefObject } from 'react';
import type { LastDraw } from './onecardStore';
import { CARD_FLIGHT_MS, nextFrame, prefersReducedMotion } from './useCardPlayFlight';

export interface DrawFlight {
  /** 먹은 이벤트의 순번 — 한 비행의 식별자. */
  id: number;
  toSeat: number;
  count: number;
  /** 도착점(먹은 사람의 좌석 패널·내 줄 가운데) — 게임판 루트 기준. */
  left: number;
  top: number;
  /** 출발점(가운데 더미) − 도착점. 처음 프레임은 더미에 두고 settled 가 되면 좌석으로 transition 한다(FLIP). */
  dx: number;
  dy: number;
  settled: boolean;
}

/**
 * D-152 — 누가 카드를 먹으면 가운데 더미(`.oc-pile`)에서 그 사람의 좌석 패널(내가 먹었으면 내 줄 `.oc-me`)로 뒷면을 날린다 — 카드 내기
 * 비행(`useCardPlayFlight`, D-139)의 반대 방향. 근거는 스토어의 방금 먹은 카드(`lastDraw` — 공개 이벤트 순번)라 순번이 바뀔 때만 난다.
 * 카드 애니메이션 설정이 꺼져 있거나 OS 가 동작 줄이기를 원하면 날리지 않는다.
 */
export function useCardDrawFlight(
  rootRef: RefObject<HTMLElement | null>,
  lastDraw: LastDraw | null,
  mySeat: number,
  enabled: boolean,
): DrawFlight | null {
  const prevSeq = useRef<number | null>(lastDraw?.seq ?? null);
  const [flight, setFlight] = useState<DrawFlight | null>(null);

  useEffect(() => {
    const seq = lastDraw?.seq ?? null;
    if (seq === prevSeq.current) return;
    prevSeq.current = seq;
    if (!lastDraw || !enabled || prefersReducedMotion()) return;
    const root = rootRef.current;
    if (!root) return;
    const source = root.querySelector('.oc-pile');
    const target =
      lastDraw.seat === mySeat
        ? root.querySelector('.oc-me')
        : root.querySelector(`.oc-felt [data-seat="${lastDraw.seat}"]`);
    if (!target || !source) return;

    const rootRect = root.getBoundingClientRect();
    const t = target.getBoundingClientRect();
    const s = source.getBoundingClientRect();
    const tx = t.left + t.width / 2;
    const ty = t.top + t.height / 2;
    setFlight({
      id: lastDraw.seq,
      toSeat: lastDraw.seat,
      count: lastDraw.count,
      left: tx - rootRect.left,
      top: ty - rootRect.top,
      dx: s.left + s.width / 2 - tx,
      dy: s.top + s.height / 2 - ty,
      settled: false,
    });
  }, [lastDraw, enabled, mySeat, rootRef]);

  // 카드 내기 비행과 같은 정리 — 비행 id 에만 묶는다.
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
