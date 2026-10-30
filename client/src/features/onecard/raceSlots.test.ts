import { describe, expect, it } from 'vitest';
import {
  RACE_JITTER_RADIUS,
  RACE_SLOT_COUNT,
  RACE_SLOTS,
  raceLeftCss,
  raceTopCss,
  racePosition,
} from './raceSlots';

/**
 * 경쟁 버튼 위치는 프로토콜 상수다 — 슬롯 수가 서버(`RaceSettings` 슬롯 14, D-161)와 같아야 하고, 어떤 지터에서도 버튼 중심이
 * 화면 가장자리를 피해야 한다. D-161 — 아래쪽(손패 위)까지 쓴다(경쟁 중엔 손패를 누를 수 없다).
 */
describe('경쟁 버튼 슬롯', () => {
  it('슬롯은 14개이고 서로 다른 자리다', () => {
    expect(RACE_SLOT_COUNT).toBe(14);
    expect(RACE_SLOTS).toHaveLength(RACE_SLOT_COUNT);
    expect(new Set(RACE_SLOTS.map((s) => `${s.x},${s.y}`)).size).toBe(RACE_SLOT_COUNT);
  });

  it('지터가 0 이면 슬롯 중심이다', () => {
    RACE_SLOTS.forEach((s, slot) => {
      expect(racePosition(slot, 0, 0)).toEqual({ left: s.x, top: s.y });
    });
  });

  it('지터는 슬롯 반경의 백분율로 움직인다', () => {
    const s = RACE_SLOTS[1];
    expect(racePosition(1, 100, -50)).toEqual({
      left: s.x + RACE_JITTER_RADIUS.x,
      top: s.y - RACE_JITTER_RADIUS.y / 2,
    });
  });

  it('어떤 슬롯·지터에서도 화면 가장자리를 피한다', () => {
    for (let slot = 0; slot < RACE_SLOT_COUNT; slot++) {
      for (const jx of [-100, 0, 100]) {
        for (const jy of [-100, 0, 100]) {
          const { left, top } = racePosition(slot, jx, jy);
          expect(left, `slot ${slot} x`).toBeGreaterThanOrEqual(5);
          expect(left, `slot ${slot} x`).toBeLessThanOrEqual(95);
          expect(top, `slot ${slot} y`).toBeGreaterThanOrEqual(5);
          expect(top, `slot ${slot} y`).toBeLessThanOrEqual(88);
        }
      }
    }
  });

  it('범위 밖 슬롯·지터는 잘라 쓴다', () => {
    expect(racePosition(RACE_SLOT_COUNT, 0, 0)).toEqual(racePosition(0, 0, 0));
    expect(racePosition(-1, 0, 0)).toEqual(racePosition(RACE_SLOT_COUNT - 1, 0, 0));
    expect(racePosition(0, 500, -500)).toEqual(racePosition(0, 100, -100));
  });
});

describe('경쟁 버튼 가로 위치 CSS', () => {
  it('가장자리에서 버튼 반폭(--oc-race-half-w)만큼 안쪽으로 보정한다 — 레이어와 연습 칸이 같은 식을 쓴다', () => {
    expect(raceLeftCss(81)).toBe(
      'clamp(var(--oc-race-half-w), 81%, calc(100% - var(--oc-race-half-w)))',
    );
  });

  // D-154 — 세로도 같은 보정(휴대폰에서 위쪽 슬롯 + 지터가 화면 위로 잘렸다).
  it('세로도 가장자리에서 버튼 반높이(--oc-race-half-h)만큼 안쪽으로 보정한다', () => {
    expect(raceTopCss(8)).toBe('clamp(var(--oc-race-half-h), 8%, calc(100% - var(--oc-race-half-h)))');
  });
});
