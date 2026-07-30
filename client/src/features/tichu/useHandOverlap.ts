import { useCallback, useLayoutEffect, useRef } from 'react';

/** 겹치지 않을 때 카드 사이 기본 간격(px). */
export const DESIRED_GAP = 6;

/**
 * 겹침 한 줄에서 카드마다 최소로 보여야 하는 좌측 슬라이버 폭(px).
 * 좌상단 코너 인덱스(랭크+슈트)가 읽히는 하한 — 두 자리 랭크("10")가
 * padding 6px + 굵은 1.35rem 글리프로 약 30px 을 차지한다. 이보다 좁게
 * 겹치면 랭크가 잘리므로 한 줄을 포기하고 줄바꿈으로 폴백한다.
 */
export const MIN_VISIBLE_STEP = 30;

/**
 * 손패 한 줄의 카드 간 마진(px) 계산 — 레이아웃 비의존 순수 함수(A3 테스트 대상).
 * 공간이 남으면 양수(겹치지 않음, {@link DESIRED_GAP} 상한), 좁으면 음수(겹침)로
 * 한 줄을 정확히 컨테이너 폭에 맞춘다. count<=1 또는 폭<=0 이면 기본 간격.
 */
export function computeHandOverlap(containerW: number, cardW: number, count: number): number {
  if (count <= 1 || containerW <= 0) return DESIRED_GAP;
  const fit = (containerW - count * cardW) / (count - 1);
  return Math.min(DESIRED_GAP, fit);
}

/**
 * 한 줄 겹침을 유지할 수 없을 만큼 좁은가 — 카드당 보이는 폭(cardW+마진)이
 * {@link MIN_VISIBLE_STEP} 미만이면 true(줄바꿈 폴백). 순수 함수(테스트 대상).
 */
export function shouldWrapHand(containerW: number, cardW: number, count: number): boolean {
  if (count <= 1 || containerW <= 0) return false;
  return cardW + computeHandOverlap(containerW, cardW, count) < MIN_VISIBLE_STEP;
}

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
      delete el.dataset.handWrap;
      return;
    }
    const cardW = first.getBoundingClientRect().width;
    // 한 줄을 정확히 컨테이너 폭에 맞추는 카드 간 마진(음수면 겹침). 여유가 있으면
    // DESIRED_GAP 로만 띄우고(겹치지 않음), 좁으면 폭에 맞춰 겹친다 — 어느 경우에도
    // 한 줄을 넘지 않아 가장자리 카드가 잘리거나 화면 밖으로 나가지 않는다.
    // 단, 겹침이 랭크 가독 하한(MIN_VISIBLE_STEP)을 깨는 좁은 폭에서는 한 줄을
    // 포기하고 줄바꿈으로 펼친다(data-hand-wrap, CSS 가 소비) — "10" 잘림 방지.
    const margin = computeHandOverlap(containerW, cardW, count);
    el.style.setProperty('--hand-overlap', `${margin}px`);
    if (shouldWrapHand(containerW, cardW, count)) {
      el.dataset.handWrap = '1';
    } else {
      delete el.dataset.handWrap;
    }
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
