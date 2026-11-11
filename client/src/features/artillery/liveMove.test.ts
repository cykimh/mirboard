import { beforeEach, describe, expect, it } from 'vitest';
import {
  EMPTY_PLAN,
  FOLLOW_MS,
  MOVE_ACK_TIMEOUT_MS,
  PREVIEW_KEEPALIVE_MS,
  PREVIEW_SEND_MS,
  acked,
  addStep,
  easeAngle,
  followPosition,
  nextSend,
  predict,
  previewDue,
  rejected,
  timedOut,
  type MovePlan,
} from './liveMove';
import { maskFromRuns } from './terrain';
import { useArtilleryStore } from './artilleryStore';
import { artilleryRoomSink } from './artilleryRoomSink';
import { flatRuns, hitReport, seedStore } from './testFixtures';

const FIRE = { angle: 60, power: 40, shell: 'PEA', item: null };
const steps = (plan: MovePlan, dir: -1 | 1, n: number) => {
  let p = plan;
  for (let i = 0; i < n; i++) p = addStep(p, dir);
  return p;
};

describe('걷기 보내기 계획 (D-251)', () => {
  it('같은 방향은 한 조각으로 모으고, 방향이 바뀌면 새 조각', () => {
    let p = steps(EMPTY_PLAN, 1, 3);
    expect(p.queued).toEqual([3]);
    p = steps(p, -1, 2);
    expect(p.queued).toEqual([3, -2]);
  });

  it('MOVE 는 박자에만, 한 번에 하나 — 답이 오기 전엔 다음을 보내지 않는다', () => {
    let p = steps(EMPTY_PLAN, 1, 3);
    expect(nextSend(p, 0, false).send).toBeNull();
    const r = nextSend(p, 0, true);
    expect(r.send).toEqual({ kind: 'MOVE', dx: 3 });
    p = steps(r.plan, 1, 2);
    expect(p.inflight).toBe(3);
    expect(nextSend(p, 250, true).send).toBeNull();
    p = acked(p);
    expect(nextSend(p, 500, true).send).toEqual({ kind: 'MOVE', dx: 2 });
  });

  it('[발사]는 답을 기다리는 MOVE 를 앞지르지 않고, 남은 조각 하나를 FIRE.moveDx 로 싣는다', () => {
    let p = nextSend(steps(EMPTY_PLAN, 1, 3), 0, true).plan;
    p = { ...steps(p, 1, 2), fire: FIRE };
    expect(nextSend(p, 10, false).send).toBeNull();
    const r = nextSend(acked(p), 20, false);
    expect(r.send).toEqual({ kind: 'FIRE', moveDx: 2, ...FIRE });
    expect(r.plan).toBe(EMPTY_PLAN);
  });

  it('조각이 둘 이상이면 앞 조각은 MOVE 로 먼저, 마지막 조각만 FIRE 에', () => {
    let p: MovePlan = { ...steps(steps(EMPTY_PLAN, 1, 2), -1, 1), fire: FIRE };
    expect(nextSend(p, 0, false).send).toBeNull();
    const r = nextSend(p, 0, true);
    expect(r.send).toEqual({ kind: 'MOVE', dx: 2 });
    p = acked(r.plan);
    expect(nextSend(p, 1, false).send).toEqual({ kind: 'FIRE', moveDx: -1, ...FIRE });
  });

  it('걷지 않았으면 FIRE.moveDx = 0 으로 바로', () => {
    expect(nextSend({ ...EMPTY_PLAN, fire: FIRE }, 0, false).send).toEqual({ kind: 'FIRE', moveDx: 0, ...FIRE });
  });

  it('BUSY 는 한 번만 다시 보내고, 그 밖의 거절은 모은 걸음을 버린다(발사 요청은 남김)', () => {
    let p = nextSend(steps(EMPTY_PLAN, 1, 3), 0, true).plan;
    p = { ...steps(p, 1, 2), fire: FIRE };
    const busy = rejected(p, 'BUSY', 5);
    expect(busy.resend).toEqual({ kind: 'MOVE', dx: 3 });
    expect(busy.plan.inflight).toBe(3);
    const again = rejected(busy.plan, 'BUSY', 6);
    expect(again.resend).toBeNull();
    expect(again.plan).toEqual({ ...EMPTY_PLAN, fire: FIRE });
    expect(rejected(p, 'CANNOT_MOVE', 7).plan).toEqual({ ...EMPTY_PLAN, fire: FIRE });
    expect(rejected(EMPTY_PLAN, 'BUSY', 8).resend).toBeNull();
  });

  it('답이 너무 늦으면 잃은 것으로 본다', () => {
    const p = nextSend(steps(EMPTY_PLAN, 1, 3), 1000, true).plan;
    expect(timedOut(p, 1000 + MOVE_ACK_TIMEOUT_MS - 1)).toBe(p);
    expect(timedOut(p, 1000 + MOVE_ACK_TIMEOUT_MS)).toEqual(EMPTY_PLAN);
  });

  it('예측 자리 — 서버 자리에서 보낸 조각 → 모은 조각 순서로 걷는다', () => {
    const mask = maskFromRuns(flatRuns());
    let p = nextSend(steps(EMPTY_PLAN, 1, 3), 0, true).plan;
    p = steps(p, -1, 1);
    expect(predict(mask, { x: 100, footY: 299, fuelLeft: 100 }, p, 360)).toEqual({ x: 102, footY: 299, fuelLeft: 96 });
    // 연료 2 — 오른쪽 둘에서 바닥나 왼쪽 조각은 못 걷는다
    expect(predict(mask, { x: 100, footY: 299, fuelLeft: 2 }, p, 360)).toEqual({ x: 102, footY: 299, fuelLeft: 0 });
  });
});

describe('남의 걸음·포신 따라가기 (D-251)', () => {
  const follow = { seat: 1, fromX: 500, fromY: 299, path: [501, 299, 502, 299, 503, 299, 504, 299], startedAt: 1000, nonce: 1 };

  it('경로를 FOLLOW_MS 동안 걷고 끝나면 null(도착 자리는 권위 상태)', () => {
    expect(followPosition(follow, 1000)).toEqual({ x: 500, footY: 299 });
    expect(followPosition(follow, 1000 + FOLLOW_MS / 4)).toEqual({ x: 501, footY: 299 });
    expect(followPosition(follow, 1000 + FOLLOW_MS / 2)).toEqual({ x: 502, footY: 299 });
    expect(followPosition(follow, 1000 + FOLLOW_MS - 1)).toEqual({ x: 503, footY: 299 });
    expect(followPosition(follow, 1000 + FOLLOW_MS)).toBeNull();
    expect(followPosition(null, 0)).toBeNull();
  });

  it('포신은 받은 각도로 FOLLOW_MS 동안 돈다', () => {
    expect(easeAngle(40, 120, 0, 0)).toBe(40);
    expect(easeAngle(40, 120, 0, FOLLOW_MS / 2)).toBe(80);
    expect(easeAngle(40, 120, 0, FOLLOW_MS * 3)).toBe(120);
  });

  it('미리보기 — 바뀌면 250ms 간격, 안 바뀌어도 2초마다', () => {
    expect(previewDue(true, 0, 5)).toBe(true);
    expect(previewDue(true, 1000, 1000 + PREVIEW_SEND_MS - 1)).toBe(false);
    expect(previewDue(true, 1000, 1000 + PREVIEW_SEND_MS)).toBe(true);
    expect(previewDue(false, 1000, 1000 + PREVIEW_KEEPALIVE_MS - 1)).toBe(false);
    expect(previewDue(false, 1000, 1000 + PREVIEW_KEEPALIVE_MS)).toBe(true);
  });
});

describe('스토어 — UNIT_MOVED·AIM_PREVIEW·차례 시계 (D-228·D-229·D-251)', () => {
  beforeEach(() => seedStore({ turnSeat: 1, turnNo: 4 }));
  const moved = (over: Record<string, unknown> = {}) => ({
    type: 'UNIT_MOVED',
    seq: 2,
    payload: { seat: 1, turn: 4, path: [499, 299, 498, 299], toX: 498, toY: 299, fuelLeft: 98, fall: null, hpAfter: 100, eliminated: null, ...over },
  });

  it('UNIT_MOVED — 포대·남은 연료를 결과값으로, 출발점부터 따라가기', () => {
    expect(useArtilleryStore.getState().applyEvent(moved())).toBe('applied');
    const st = useArtilleryStore.getState();
    expect(st.units[1]).toMatchObject({ x: 498, footY: 299, hp: 100, alive: true });
    expect(st.fuelLeft).toBe(98);
    expect(st.follow).toMatchObject({ seat: 1, fromX: 500, fromY: 299, path: [499, 299, 498, 299] });
    expect(st.lastMove?.seat).toBe(1);
    expect(st.phase).toBe('AIMING');
    expect(st.turnSeat).toBe(1);
  });

  it('UNIT_MOVED 탈락 — 대본 없는 연출 대기(재생 없음, 차례 없음), 떨어진 자리까지 따라가기', () => {
    useArtilleryStore.getState().applyEvent(
      moved({
        path: [501, 299],
        toX: 501,
        toY: 360,
        fall: { seat: 1, x: 501, fromY: 299, toY: 360, damage: 0, hpAfter: 100, intoCloud: true },
        eliminated: 'CLOUD_SEA',
      }),
    );
    const st = useArtilleryStore.getState();
    expect(st.phase).toBe('RESOLVING');
    expect(st.turnSeat).toBe(-1);
    expect(st.replay).toBeNull();
    expect(st.turnClock).toBeNull();
    expect(st.units[1]).toMatchObject({ alive: false, outCause: 'CLOUD_SEA' });
    expect(st.follow?.path).toEqual([501, 299, 501, 360]);
    expect(st.notice?.text).toBe('FELL');
  });

  it('AIM_PREVIEW — 지금 차례 주인·지금 차례·더 새 번호만', () => {
    const aim = (over: Record<string, unknown>) => ({ type: 'AIM_PREVIEW', payload: { seat: 1, turn: 4, angle: 120, shell: 'PUMPKIN', n: 3, ...over } });
    const apply = (e: ReturnType<typeof aim>) => useArtilleryStore.getState().applyEvent(e);
    expect(apply(aim({}))).toBe('applied');
    expect(useArtilleryStore.getState().aimPreview).toMatchObject({ seat: 1, angle: 120, shell: 'PUMPKIN', n: 3 });
    expect(apply(aim({ n: 2, angle: 10 }))).toBe('ignored');
    expect(apply(aim({ seat: 0, n: 9 }))).toBe('ignored');
    expect(apply(aim({ turn: 3, n: 9 }))).toBe('ignored');
    expect(apply(aim({ n: 4, angle: 100 }))).toBe('applied');
    expect(useArtilleryStore.getState().aimPreview?.angle).toBe(100);
    // 발사가 해소되면(재생 중) 늦게 온 미리보기는 버린다
    useArtilleryStore.getState().applyEvent({ type: 'SHOT_RESOLVED', seq: 3, payload: { report: hitReport({ seat: 1 }) } });
    expect(useArtilleryStore.getState().aimPreview).toBeNull();
    expect(apply(aim({ n: 9 }))).toBe('ignored');
  });

  it('TURN_CHANGED 는 남은 연료·따라가기·미리보기를 비운다', () => {
    useArtilleryStore.getState().applyEvent(moved());
    useArtilleryStore.getState().applyEvent({ type: 'AIM_PREVIEW', payload: { seat: 1, turn: 4, angle: 1, shell: null, n: 1 } });
    useArtilleryStore.getState().applyEvent({ type: 'TURN_CHANGED', seq: 3, payload: { seat: 0, turnNo: 5, wind: 0, cloudLevel: 360 } });
    const st = useArtilleryStore.getState();
    expect(st.fuelLeft).toBeNull();
    expect(st.follow).toBeNull();
    expect(st.aimPreview).toBeNull();
  });

  it('resync — 엔진이 센 차례 남은 시간·남은 연료를 쓴다(바깥 값은 마지막 액션 기준)', () => {
    seedStore({ turnSeat: 0, fuel: 61, turnRemainingMs: 12_000 }, 29_000);
    const st = useArtilleryStore.getState();
    expect(st.turnClock?.remainingMs).toBe(12_000);
    expect(st.fuelLeft).toBe(61);
    seedStore({ turnSeat: 0 }, 29_000);   // 구 서버 — 엔진 값 없음
    expect(useArtilleryStore.getState().turnClock?.remainingMs).toBe(29_000);
  });

  it('sink — 답을 기다리는 MOVE 의 BUSY·CANNOT_MOVE 는 띠를 띄우지 않고 기록만', () => {
    const st = useArtilleryStore.getState();
    st.setMoveInFlight(true);
    artilleryRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'BUSY', message: 'x' } } as never);
    expect(useArtilleryStore.getState().errorMessage).toBeNull();
    expect(useArtilleryStore.getState().lastError?.code).toBe('BUSY');
    artilleryRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'NOT_YOUR_TURN', message: 'x' } } as never);
    expect(useArtilleryStore.getState().errorMessage).toBe('아직 당신의 차례가 아닙니다.');
    st.setMoveInFlight(false);
    artilleryRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'CANNOT_MOVE', message: 'x' } } as never);
    expect(useArtilleryStore.getState().errorMessage).toBe('더 걸어갈 수 없어요.');
  });

  it('새 type 둘은 unhandled 가 아니다(resync 를 부르지 않는다)', () => {
    expect(useArtilleryStore.getState().applyEvent(moved())).not.toBe('unhandled');
    expect(
      useArtilleryStore.getState().applyEvent({ type: 'AIM_PREVIEW', payload: { seat: 0, turn: 0, angle: 1, shell: null, n: 1 } }),
    ).not.toBe('unhandled');
  });
});
