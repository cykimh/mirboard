import { describe, expect, it } from 'vitest';
import { computeHandOverlap, DESIRED_GAP } from './useHandOverlap';

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
