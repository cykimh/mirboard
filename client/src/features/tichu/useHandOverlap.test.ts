import { describe, expect, it } from 'vitest';
import {
  computeHandOverlap,
  shouldWrapHand,
  DESIRED_GAP,
  MIN_VISIBLE_STEP,
} from './useHandOverlap';

describe('computeHandOverlap', () => {
  it('uses the desired gap for a single card', () => {
    expect(computeHandOverlap(500, 48, 1)).toBe(DESIRED_GAP);
  });

  it('keeps cards apart (capped gap) when there is ample space', () => {
    // 4 × 48 = 192 in a 500px container → plenty of room → capped at DESIRED_GAP
    expect(computeHandOverlap(500, 48, 4)).toBe(DESIRED_GAP);
  });

  it('overlaps (negative margin) when space is tight', () => {
    // 14 × 48 = 672 in a 300px container → must overlap
    expect(computeHandOverlap(300, 48, 14)).toBeLessThan(0);
  });

  it('fits the row exactly to the container when overlapping', () => {
    const count = 14;
    const cardW = 48;
    const containerW = 300;
    const m = computeHandOverlap(containerW, cardW, count);
    expect(count * cardW + (count - 1) * m).toBeCloseTo(containerW, 5);
  });

  it('falls back to the desired gap for a non-positive container width', () => {
    expect(computeHandOverlap(0, 48, 5)).toBe(DESIRED_GAP);
  });
});

describe('shouldWrapHand', () => {
  it('keeps a single row when there is ample space', () => {
    expect(shouldWrapHand(900, 62, 14)).toBe(false);
  });

  it('keeps a single row while the visible step stays readable', () => {
    // step = cardW + margin ≥ MIN_VISIBLE_STEP → 한 줄 겹침 유지.
    const containerW = 54 + 13 * MIN_VISIBLE_STEP; // step 이 정확히 하한
    expect(shouldWrapHand(containerW, 54, 14)).toBe(false);
  });

  it('wraps when overlap would clip the corner rank (401px regression)', () => {
    // 실측 재현: 컨테이너 353px · 카드 54px · 14장 → step 23px < 30 → 줄바꿈.
    expect(shouldWrapHand(353, 54, 14)).toBe(true);
  });

  it('never wraps for a single card or a non-positive width', () => {
    expect(shouldWrapHand(353, 54, 1)).toBe(false);
    expect(shouldWrapHand(0, 54, 14)).toBe(false);
  });
});
