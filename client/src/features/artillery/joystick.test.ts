import { describe, expect, it } from 'vitest';
import {
  AXIS_STICKY,
  STICK_DEAD,
  aimRate,
  elevation,
  faceToward,
  facingOf,
  nudgeAngle,
  stickIntent,
  stickVector,
} from './joystick';

describe('조이스틱 판정 (D-253)', () => {
  it('stickVector — 반지름 밖은 테두리로 자르고 방향은 그대로', () => {
    expect(stickVector(30, 0, 60)).toEqual({ nx: 0.5, ny: 0 });
    const far = stickVector(120, 0, 60);
    expect(far.nx).toBeCloseTo(1);
    const diag = stickVector(100, 100, 60);
    expect(Math.hypot(diag.nx, diag.ny)).toBeCloseTo(1);
    expect(diag.nx).toBeCloseTo(diag.ny);
  });

  it('죽은 영역 안은 아무것도 아니다', () => {
    expect(stickIntent(STICK_DEAD * 0.9, 0)).toEqual({ axis: 'none', dir: 0, strength: 0 });
    expect(stickIntent(0, 0)).toEqual({ axis: 'none', dir: 0, strength: 0 });
  });

  it('더 크게 민 축 하나만 — 좌우 = 걷기, 위(화면 y −) = 올리기', () => {
    expect(stickIntent(0.8, 0.2)).toMatchObject({ axis: 'walk', dir: 1 });
    expect(stickIntent(-0.8, -0.3)).toMatchObject({ axis: 'walk', dir: -1 });
    expect(stickIntent(0.2, -0.9)).toMatchObject({ axis: 'aim', dir: 1 });
    expect(stickIntent(-0.1, 0.9)).toMatchObject({ axis: 'aim', dir: -1 });
  });

  it('세기 — 죽은 영역 끝 0, 테두리 1', () => {
    expect(stickIntent(1, 0).strength).toBeCloseTo(1);
    expect(stickIntent(STICK_DEAD + 0.0001, 0).strength).toBeCloseTo(0, 3);
    expect(stickIntent(0, -(STICK_DEAD + (1 - STICK_DEAD) / 2)).strength).toBeCloseTo(0.5);
  });

  it('대각선 떨림 막기 — 지금 축을 1.25배 우대한다', () => {
    // 세로가 조금 더 크지만(0.6 > 0.55) 걷던 중이면 그대로 걷기
    expect(stickIntent(0.55, -0.6, 'walk').axis).toBe('walk');
    expect(stickIntent(0.55, -0.6, 'none').axis).toBe('aim');
    // 우대를 넘으면 바뀐다
    expect(stickIntent(0.4, -0.4 * AXIS_STICKY - 0.05, 'walk').axis).toBe('aim');
    expect(stickIntent(0.6, 0.55, 'aim').axis).toBe('aim');
    expect(stickIntent(0.6 * AXIS_STICKY + 0.05, 0.6, 'aim').axis).toBe('walk');
  });

  it('바라보는 쪽 — 90 미만 오른쪽, 넘으면 왼쪽, 정확히 90 이면 알던 쪽', () => {
    expect(facingOf(45, -1)).toBe(1);
    expect(facingOf(135, 1)).toBe(-1);
    expect(facingOf(90, -1)).toBe(-1);
    expect(facingOf(90, 1)).toBe(1);
  });

  it('faceToward — 반대쪽을 보면 거울 각도, 같은 쪽이면 그대로', () => {
    expect(faceToward(45, -1)).toBe(135);
    expect(faceToward(135, 1)).toBe(45);
    expect(faceToward(45, 1)).toBe(45);
    expect(faceToward(0, -1)).toBe(180);
    expect(faceToward(90, -1)).toBe(90);
  });

  it('nudgeAngle — 오른쪽을 보면 올리기 = 각도 +, 왼쪽을 보면 올리기 = 각도 −, 높이는 0~90', () => {
    expect(nudgeAngle(45, 1, 1)).toBe(46);
    expect(nudgeAngle(45, 1, -1)).toBe(44);
    expect(nudgeAngle(135, -1, 1)).toBe(134);
    expect(nudgeAngle(135, -1, -1)).toBe(136);
    expect(nudgeAngle(89, 1, 1, 5)).toBe(90);
    expect(nudgeAngle(91, -1, 1, 5)).toBe(90);
    expect(nudgeAngle(2, 1, -1, 5)).toBe(0);
    expect(nudgeAngle(178, -1, -1, 5)).toBe(180);
    // 정확히 위에서 내리면 알던 쪽으로
    expect(nudgeAngle(90, -1, -1)).toBe(91);
    expect(nudgeAngle(90, 1, -1)).toBe(89);
  });

  it('elevation — 바라보는 쪽 기준 높이', () => {
    expect(elevation(30)).toBe(30);
    expect(elevation(150)).toBe(30);
    expect(elevation(90)).toBe(90);
  });

  it('aimRate — 조금 밀면 천천히, 끝까지 밀면 빠르게(단조)', () => {
    expect(aimRate(0)).toBe(8);
    expect(aimRate(1)).toBe(70);
    expect(aimRate(0.5)).toBeGreaterThan(aimRate(0.25));
    expect(aimRate(2)).toBe(70);
  });
});
