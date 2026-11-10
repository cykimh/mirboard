import { beforeEach, describe, expect, it } from 'vitest';
import { applyOp, carve, fill, maskFromRuns, solid, supported, walk } from './terrain';
import { EXPLOSION_TICKS, TICK_MS, frameAt, replayLengthMs, terrainOps, timeline } from './replay';
import { chargePower, CHARGE_PERIOD_MS } from './ArtilleryControls';
import { angleToward } from './ArtilleryField';
import { canvasLabel, uiBoost } from './draw';
import { useArtilleryStore } from './artilleryStore';
import { DELAY_TABLE, flatRuns, hitReport, hitShot, hitStage, seedStore, unit } from './testFixtures';
import { minDelay, teamName, turnDelay } from './delay';

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

  it('둔덕 채우기 — 비운 몸 자리(반경 7)는 그대로, 서버 Terrain.fill 과 같은 수 (D-224)', () => {
    const m = maskFromRuns(Array.from({ length: 640 }, () => []));
    const f = fill(m, 100, 100, 16, [100, 100]);
    expect(solid(f, 100, 100)).toBe(false);
    expect(solid(f, 107, 100)).toBe(false);
    expect(solid(f, 108, 100)).toBe(true);
    expect(solid(f, 100, 116)).toBe(true);
    expect(solid(f, 100, 117)).toBe(false);
    expect(solid(applyOp(f, { x: 100, y: 116, r: 3 }), 100, 116)).toBe(false);
    expect(solid(applyOp(m, { x: 50, y: 50, r: 3, fill: true }), 50, 50)).toBe(true);
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
    expect(tl).toEqual({
      walkEnd: 4,
      walkFallEnd: 4,
      stages: [{ start: 4, flightEnd: 12, explosionEnd: 12 + EXPLOSION_TICKS, fallEnd: 12 + EXPLOSION_TICKS + 8 }],
      end: 12 + EXPLOSION_TICKS + 8,
    });
    const before = [
      { seat: 0, x: 100, footY: 299, hp: 100, alive: true },
      { seat: 1, x: 500, footY: 299, hp: 100, alive: true },
    ];
    const walking = frameAt(r, before, 2 * TICK_MS);
    expect(walking.units[0].x).toBe(101);
    expect(walking.shells).toEqual([]);
    const flying = frameAt(r, before, 6 * TICK_MS);
    expect(flying.shells).toHaveLength(1);
    expect(flying.opsApplied).toBe(0);
    expect(flying.units[1].hp).toBe(100);
    const boom = frameAt(r, before, 14 * TICK_MS);
    expect(boom.explosions).toHaveLength(1);
    expect(boom.opsApplied).toBe(1);
    expect(boom.units[1].hp).toBe(70);
    const end = frameAt(r, before, replayLengthMs(r) + 1);
    expect(end.done).toBe(true);
    expect(end.units[1].footY).toBe(307);
  });

  it('걷다 구름에 빠지면 비행이 없다', () => {
    const r = hitReport({
      move: { path: [101, 299], toX: 101, toY: 360, fuelLeft: 99, fall: { seat: 0, x: 101, fromY: 299, toY: 360, damage: 0, hpAfter: 100, intoCloud: true } },
      stages: [],
      eliminated: [{ seat: 0, cause: 'CLOUD_SEA' }],
    });
    expect(timeline(r).stages).toEqual([]);
    expect(timeline(r).end).toBe(timeline(r).walkFallEnd);
  });

  it('홀씨탄 — 갈라진 씨앗 셋이 같이 날고, 내려앉는 대로 구멍이 앞에서부터 반영된다 (D-224)', () => {
    const parent = hitShot({ end: 'SPLIT', crater: null, hitSeat: -1, path: [109, 287, 150, 250, 200, 240], ticks: 6 });
    const child = (dx: number, ticks: number) =>
      hitShot({ startTick: 6, ticks, hitSeat: -1, end: 'TERRAIN', path: [200, 240, 250 + dx, 280], crater: { x: 300 + dx, y: 300, r: 10 } });
    const r = hitReport({
      shell: 'SPORE',
      stages: [hitStage({ shots: [parent, child(-30, 4), child(0, 6), child(30, 8)], damages: [], falls: [] })],
    });
    const tl = timeline(r);
    expect(tl.stages[0].flightEnd - tl.stages[0].start).toBe(14);
    const before = [
      { seat: 0, x: 100, footY: 299, hp: 100, alive: true },
      { seat: 1, x: 500, footY: 299, hp: 100, alive: true },
    ];
    expect(frameAt(r, before, (4 + 3) * TICK_MS).shells).toHaveLength(1);
    const spread = frameAt(r, before, (4 + 7) * TICK_MS);
    expect(spread.shells).toHaveLength(3);
    expect(spread.shells[0].shell).toBe('SPORE');
    expect(frameAt(r, before, (4 + 10.5) * TICK_MS).opsApplied).toBe(1);
    expect(frameAt(r, before, (4 + 12.5) * TICK_MS).opsApplied).toBe(2);
    expect(frameAt(r, before, (4 + 14.5) * TICK_MS).opsApplied).toBe(3);
    expect(terrainOps(r)).toHaveLength(3);
  });

  it('겹씨앗 — 두 단계가 이어지고, 두 번째 피해는 두 번째 단계에서 (D-224)', () => {
    const second = hitStage({
      shots: [hitShot({ crater: { x: 480, y: 300, r: 14 } })],
      damages: [{ seat: 1, amount: 20, hpAfter: 50, direct: false }],
      falls: [],
    });
    const r = hitReport({ item: 'TWIN_SEED', stages: [hitStage(), second] });
    const tl = timeline(r);
    expect(tl.stages).toHaveLength(2);
    expect(tl.stages[1].start).toBe(tl.stages[0].fallEnd);
    const before = [
      { seat: 0, x: 100, footY: 299, hp: 100, alive: true },
      { seat: 1, x: 500, footY: 299, hp: 100, alive: true },
    ];
    expect(frameAt(r, before, (tl.stages[0].fallEnd + 1) * TICK_MS).units[1].hp).toBe(70);
    expect(frameAt(r, before, (tl.stages[1].flightEnd + 1) * TICK_MS).units[1].hp).toBe(50);
    expect(frameAt(r, before, (tl.stages[1].flightEnd + 1) * TICK_MS).opsApplied).toBe(2);
  });

  it('물뿌리개 회복은 재생 시작에 바로 보인다', () => {
    const r = hitReport({ item: 'WATERING_CAN', heal: { amount: 20, hpAfter: 80 } });
    const before = [
      { seat: 0, x: 100, footY: 299, hp: 60, alive: true },
      { seat: 1, x: 500, footY: 299, hp: 100, alive: true },
    ];
    expect(frameAt(r, before, 0).units[0].hp).toBe(80);
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

  it('SHOT_RESOLVED — 특수탄·아이템을 쓰면 수가 줄고, 연산이 여럿이면 재생 지형도 여럿 (D-224)', () => {
    const r = hitReport({
      shell: 'VINE',
      item: 'TWIN_SEED',
      stages: [
        hitStage({ shots: [hitShot({ crater: { x: 300, y: 290, r: 5, fill: true, spare: [] } })], damages: [], falls: [] }),
        hitStage(),
      ],
    });
    useArtilleryStore.getState().applyEvent({ type: 'SHOT_RESOLVED', payload: { report: r } });
    const s = useArtilleryStore.getState();
    expect(s.units[0].specials).toBe(1);
    expect(s.units[0].pumpkins).toBe(2);
    expect(s.units[0].items).toEqual(['WATERING_CAN', 'PINWHEEL']);
    expect(s.replay!.masks).toHaveLength(3);
    expect(solid(s.mask!, 300, 288)).toBe(true);
    expect(solid(s.mask!, 500, 294)).toBe(false);
  });

  it('고르기 단계 — 스냅샷·UNIT_PICKED·UNITS_REVEALED (D-224)', () => {
    seedStore(
      {
        phase: 'PICKING',
        turnSeat: -1,
        pickRemainingMs: 9_000,
        units: [unit(0, 100, { kind: null, picked: true, special: null, specials: 0, items: [] }), unit(1, 500, { kind: null, picked: false, special: null, specials: 0, items: [] })],
      },
      null,
      { pickedKind: 'ACORN' },
    );
    let s = useArtilleryStore.getState();
    expect(s.phase).toBe('PICKING');
    expect(s.myPick).toBe('ACORN');
    expect(s.pickClock?.remainingMs).toBe(9_000);
    s.applyEvent({ type: 'UNIT_PICKED', payload: { seat: 1 } });
    expect(useArtilleryStore.getState().units[1].picked).toBe(true);
    s.applyEvent({
      type: 'UNITS_REVEALED',
      payload: { units: [unit(0, 100, { kind: 'ACORN', hp: 120, maxHp: 120, fuel: 70, special: 'CHESTNUT' }), unit(1, 500, { kind: 'DANDELION', hp: 85, maxHp: 85, fuel: 130, special: 'SPORE' })] },
    });
    s = useArtilleryStore.getState();
    expect(s.units.map((u) => u.kind)).toEqual(['ACORN', 'DANDELION']);
    expect(s.myPick).toBeNull();
    expect(s.pickClock).toBeNull();
  });

  it('스냅샷은 재생을 끊고 서버의 남은 턴 시간을 쓴다', () => {
    useArtilleryStore.getState().applyEvent({ type: 'SHOT_RESOLVED', payload: { report: hitReport() } });
    seedStore({ turnSeat: 1 }, 12_000);
    const s = useArtilleryStore.getState();
    expect(s.replay).toBeNull();
    expect(s.turnClock?.remainingMs).toBe(12_000);
  });
});

describe('딜레이·팀 (D-225·D-226)', () => {
  it('이번 차례 딜레이 = 탄 + 쓴 연료/2(버림) + 아이템 — 서버 표 그대로', () => {
    expect(turnDelay(DELAY_TABLE, 'PEA', null, 0)).toBe(100);
    expect(turnDelay(DELAY_TABLE, 'PUMPKIN', null, 7)).toBe(133);
    expect(turnDelay(DELAY_TABLE, 'SPORE', 'TWIN_SEED', 20)).toBe(210);
    expect(turnDelay(DELAY_TABLE, 'PEA', 'PINWHEEL', 1)).toBe(130);
    expect(turnDelay(null, 'PEA', null, 0)).toBeNull();
  });

  it('좌석 딜레이는 살아 있는 가장 작은 값 기준, 팀 이름은 좌석 홀짝', () => {
    expect(minDelay([unit(0, 1, { delay: 300 }), unit(1, 2, { delay: 120, alive: false }), unit(2, 3, { delay: 200 })])).toBe(200);
    expect(minDelay([unit(0, 1)])).toBe(0);
    expect(teamName(0)).toBe('A팀');
    expect(teamName(1)).toBe('B팀');
  });

  it('스냅샷이 팀·순서·딜레이 표를 담고, TURN_CHANGED 가 좌석 딜레이와 순서를 바꾼다', () => {
    seedStore({ teams: true, queue: [0, 1], delays: DELAY_TABLE, units: [unit(0, 100, { delay: 0 }), unit(1, 500, { delay: 1 })] });
    const st = useArtilleryStore.getState();
    expect(st.teams).toBe(true);
    expect(st.queue).toEqual([0, 1]);
    expect(st.delayTable?.skip).toBe(80);
    st.applyEvent({ type: 'TURN_CHANGED', payload: { seat: 1, turnNo: 1, wind: 0, cloudLevel: 360, delays: [100, 1], queue: [1, 0] } });
    const after = useArtilleryStore.getState();
    expect(after.units.map((u) => u.delay)).toEqual([100, 1]);
    expect(after.queue).toEqual([1, 0]);
    // 구 서버(딜레이 없음)면 그대로 둔다
    after.applyEvent({ type: 'TURN_CHANGED', payload: { seat: 0, turnNo: 2, wind: 0, cloudLevel: 360 } });
    expect(useArtilleryStore.getState().units.map((u) => u.delay)).toEqual([100, 1]);
  });
});

