import { beforeEach, describe, expect, it } from 'vitest';
import { carve, maskFromRuns, solid, supported, walk } from './terrain';
import { EXPLOSION_TICKS, TICK_MS, frameAt, replayLengthMs, timeline } from './replay';
import { chargePower, CHARGE_PERIOD_MS } from './ArtilleryControls';
import { angleToward } from './ArtilleryField';
import { canvasLabel, uiBoost } from './draw';
import { useArtilleryStore } from './artilleryStore';
import { flatRuns, hitReport, seedStore } from './testFixtures';

describe('지형 마스크 (서버 Terrain 미러)', () => {
  it('런 → 마스크, 구멍은 서버와 같은 정수 원', () => {
    const m = maskFromRuns(flatRuns(300));
    expect(solid(m, 10, 299)).toBe(false);
    expect(solid(m, 10, 300)).toBe(true);
    const c = carve(m, 300, 300, 10);
    expect(solid(c, 300, 310)).toBe(false);
    expect(solid(c, 300, 311)).toBe(true);
    expect(solid(c, 308, 306)).toBe(false); // 64 + 36 = 100 ≤ 100
    expect(solid(c, 309, 306)).toBe(true);
    expect(solid(m, 300, 305)).toBe(true); // 원본은 그대로
  });

  it('걷기 미리보기 — 평지 1칸에 연료 1, 계단 3칸 오르기, 벽에서 멈춤 (서버 PhysicsTest 와 같은 수)', () => {
    const runs = flatRuns(300);
    for (let x = 110; x < 120; x++) runs[x] = [297, 359];
    for (let x = 130; x < 135; x++) runs[x] = [280, 359];
    const m = maskFromRuns(runs);
    expect(walk(m, 100, 299, 40, 100, 360)).toMatchObject({ x: 129, fuelLeft: 100 - 29 - 3, dx: 29, edge: false });
    expect(walk(m, 100, 299, -30, 100, 360)).toMatchObject({ x: 70, fuelLeft: 70, dx: -30 });
  });

  it('미리보기는 낭떠러지에서 떨어지는 걸음 앞에서 멈춘다', () => {
    const runs = Array.from({ length: 640 }, (_, x) => (x < 200 ? [100, 100, 300, 359] : [300, 359]));
    const m = maskFromRuns(runs);
    const p = walk(m, 190, 99, 30, 100, 360);
    expect(p.edge).toBe(true);
    expect(p.x).toBe(201);
    expect(supported(m, p.x, p.footY)).toBe(true);
  });
});

describe('재생 타임라인', () => {
  it('이동 → 비행 → 폭발 → 낙하 순서, 끝은 대본의 결과값', () => {
    const r = hitReport();
    const tl = timeline(r);
    expect(tl).toEqual({ walkEnd: 4, walkFallEnd: 4, flightEnd: 12, explosionEnd: 12 + EXPLOSION_TICKS, fallEnd: 12 + EXPLOSION_TICKS + 8 });
    const before = [
      { seat: 0, x: 100, footY: 299, hp: 100, alive: true },
      { seat: 1, x: 500, footY: 299, hp: 100, alive: true },
    ];
    const walking = frameAt(r, before, 2 * TICK_MS);
    expect(walking.units[0].x).toBe(101);
    expect(walking.shell).toBeNull();
    const flying = frameAt(r, before, 6 * TICK_MS);
    expect(flying.shell).not.toBeNull();
    expect(flying.craterApplied).toBe(false);
    expect(flying.units[1].hp).toBe(100);
    const boom = frameAt(r, before, 14 * TICK_MS);
    expect(boom.explosion).not.toBeNull();
    expect(boom.craterApplied).toBe(true);
    expect(boom.units[1].hp).toBe(70);
    const end = frameAt(r, before, replayLengthMs(r) + 1);
    expect(end.done).toBe(true);
    expect(end.units[1].footY).toBe(307);
  });

  it('걷다 구름에 빠지면 비행이 없다', () => {
    const r = hitReport({
      move: { path: [101, 299], toX: 101, toY: 360, fuelLeft: 99, fall: { seat: 0, x: 101, fromY: 299, toY: 360, damage: 0, hpAfter: 100, intoCloud: true } },
      shot: null,
      damages: [],
      falls: [],
      eliminated: [{ seat: 0, cause: 'CLOUD_SEA' }],
    });
    expect(timeline(r).flightEnd).toBe(timeline(r).walkFallEnd);
  });
});

describe('조작 계산', () => {
  it('꾹 누르기 힘은 0→100→0 삼각파', () => {
    expect(chargePower(0)).toBe(0);
    expect(chargePower(CHARGE_PERIOD_MS / 2)).toBe(100);
    expect(chargePower(CHARGE_PERIOD_MS / 4)).toBe(50);
    expect(chargePower(CHARGE_PERIOD_MS)).toBe(0);
  });

  it('작은 화면에서는 글씨·포대를 키우고 이름을 줄인다', () => {
    expect(uiBoost(1.2).label).toBeCloseTo(9.1667, 3);
    expect(uiBoost(1.2).unit).toBe(1);
    expect(uiBoost(0.53).label).toBeGreaterThan(20);
    expect(uiBoost(0.53).unit).toBeCloseTo(1.415, 2);
    expect(canvasLabel('guest-seq8qe4j', false, true)).toBe('guest…');
    expect(canvasLabel('guest-seq8qe4j', true, true)).toBe('나');
    expect(canvasLabel('보라', true, false)).toBe('보라 (나)');
  });

  it('끌기 각도 — 오른쪽 0°, 위 90°, 아래쪽은 가까운 끝', () => {
    expect(angleToward(100, 100, 150, 100)).toBe(0);
    expect(angleToward(100, 100, 100, 50)).toBe(90);
    expect(angleToward(100, 100, 50, 50)).toBe(135);
    expect(angleToward(100, 100, 150, 140)).toBe(0);
    expect(angleToward(100, 100, 40, 140)).toBe(180);
  });
});

describe('스토어 (순수 리듀서)', () => {
  beforeEach(() => seedStore());

  it('SHOT_RESOLVED — 권위 상태는 즉시 결과값, 재생은 직전 지형으로', () => {
    const r = hitReport({ shell: 'PUMPKIN' });
    expect(useArtilleryStore.getState().applyEvent({ type: 'SHOT_RESOLVED', payload: { report: r } })).toBe('applied');
    const s = useArtilleryStore.getState();
    expect(s.phase).toBe('RESOLVING');
    expect(s.turnSeat).toBe(-1);
    expect(s.units[1]).toMatchObject({ hp: 70, footY: 307 });
    expect(s.units[0].pumpkins).toBe(1);
    expect(solid(s.mask!, 500, 294)).toBe(false);
    expect(solid(s.replay!.maskBefore, 500, 300)).toBe(true);
    expect(s.replay!.before[1].hp).toBe(100);
  });

  it('두 번 적용해도 포대 값은 같다(결과값)', () => {
    const r = hitReport();
    useArtilleryStore.getState().applyEvent({ type: 'SHOT_RESOLVED', payload: { report: r } });
    useArtilleryStore.getState().applyEvent({ type: 'SHOT_RESOLVED', payload: { report: r } });
    expect(useArtilleryStore.getState().units[1].hp).toBe(70);
  });

  it('TURN_CHANGED·UNIT_ELIMINATED·MATCH_ENDED', () => {
    const st = useArtilleryStore.getState();
    st.applyEvent({ type: 'TURN_CHANGED', payload: { seat: 1, turnNo: 1, wind: -4, cloudLevel: 345 } });
    expect(useArtilleryStore.getState()).toMatchObject({ phase: 'AIMING', turnSeat: 1, wind: -4, cloudLevel: 345 });
    st.applyEvent({ type: 'UNIT_ELIMINATED', payload: { seat: 0, cause: 'CLOUD_SEA', cloudLevel: 330 } });
    expect(useArtilleryStore.getState().units[0]).toMatchObject({ alive: false, outCause: 'CLOUD_SEA' });
    st.applyEvent({
      type: 'MATCH_ENDED',
      payload: { reason: 'LAST_STANDING', placements: [{ seat: 1, rank: 1, hp: 80, deserted: false }, { seat: 0, rank: 2, hp: 0, deserted: false }] },
    });
    expect(useArtilleryStore.getState().phase).toBe('ENDED');
    expect(st.applyEvent({ type: 'TURN_CHANGED', payload: { seat: 0, turnNo: 2, wind: 0, cloudLevel: 330 } })).toBe('ignored');
  });

  it('스냅샷은 재생을 끊고 서버의 남은 턴 시간을 쓴다', () => {
    useArtilleryStore.getState().applyEvent({ type: 'SHOT_RESOLVED', payload: { report: hitReport() } });
    seedStore({ turnSeat: 1 }, 12_000);
    const s = useArtilleryStore.getState();
    expect(s.replay).toBeNull();
    expect(s.turnClock?.remainingMs).toBe(12_000);
  });
});
