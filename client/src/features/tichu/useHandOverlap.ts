import { useCallback, useLayoutEffect, useRef } from 'react';

/** 겹치지 않을 때 카드 사이 기본 간격(px). */
const DESIRED_GAP = 6;

/**
 * 손패 한 줄 레이아웃 측정 훅(#1).
 *
 * 컨테이너 폭과 카드 수/폭을 재서, 공간이 남으면 양수 간격(겹치지 않음)을,
 * 좁으면 폭에 정확히 맞춰 음수 마진(겹침)을 `--hand-overlap` CSS 변수로 세팅한다.
 * 항상 컨테이너 폭에 맞추므로 카드가 화면 밖으로 넘치지 않는다.
 * 카드 수 변화(딜링/제출)와 폭 변화(리사이즈/회전)에 ResizeObserver 로 반응.
 *
 * 반환한 ref 를 `.hand-cards` 컨테이너에 붙이고, CSS 가
 * `margin-left: var(--hand-overlap)` 로 소비한다. count<=1 이면(겹침 비활성 포함)
 * 측정·관찰을 건너뛴다.
 */
export function useHandOverlap(count: number) {
  const ref = useRef<HTMLDivElement | null>(null);

  const recompute = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const first = el.firstElementChild as HTMLElement | null;
    const containerW = el.clientWidth;
    if (!first || count <= 1 || containerW <= 0) {
      el.style.setProperty('--hand-overlap', `${DESIRED_GAP}px`);
      return;
    }
    const cardW = first.getBoundingClientRect().width;
    // 한 줄을 정확히 컨테이너 폭에 맞추는 카드 간 마진(음수면 겹침). 여유가 있으면
    // DESIRED_GAP 로만 띄우고(겹치지 않음), 좁으면 폭에 맞춰 겹친다 — 어느 경우에도
    // 한 줄을 넘지 않아 가장자리 카드가 잘리거나 화면 밖으로 나가지 않는다.
    const fit = (containerW - count * cardW) / (count - 1);
    const margin = Math.min(DESIRED_GAP, fit);
    el.style.setProperty('--hand-overlap', `${margin}px`);
  }, [count]);

  useLayoutEffect(() => {
    recompute();
    const el = ref.current;
    // 겹침 비활성(count<=1)·RO 미지원이면 관찰하지 않는다 — 패스/딜링 단계에서
    // 소비되지도 않는 값을 리사이즈마다 다시 쓰는 낭비를 피한다(#2).
    if (!el || count <= 1 || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(recompute);
    ro.observe(el);
    return () => ro.disconnect();
  }, [recompute, count]);

  return ref;
}
