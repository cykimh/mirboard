import { useCallback, useEffect, useRef, useState } from 'react';
import { useArtilleryStore } from './artilleryStore';
import {
  EMPTY_PLAN,
  MOVE_SEND_MS,
  acked,
  addStep,
  hasPendingSteps,
  nextSend,
  predict,
  rejected,
  timedOut,
  type FireRequest,
  type MovePlan,
  type Predicted,
  type Send,
} from './liveMove';
import { walk } from './terrain';

interface Args {
  /** 내 차례이고 손을 쓸 수 있다(연결됨·재생 아님). */
  active: boolean;
  mySeat: number;
  /** 차례가 바뀌면 계획을 버린다. */
  turnKey: string;
  sendAction: (action: Record<string, unknown>) => void;
}

export interface LiveMove {
  /** 화면에 그릴 내 자리·남은 연료(서버 확정 + 아직 확정 안 된 걸음). 내 포대가 없으면 null. */
  predicted: Predicted | null;
  /** 한 걸음 — 더 못 가면 false(벽·연료·낭떠러지 끝). */
  step: (dir: -1 | 1) => boolean;
  /** [발사] — 답을 기다리는 걸음이 있으면 그 뒤에 보낸다. */
  requestFire: (req: FireRequest) => void;
  /** 아직 확정 안 된 걸음이 있다. */
  walking: boolean;
}

/**
 * 걷기 보내기 (D-251) — `liveMove` 의 계획을 소켓에 붙인다. 걸음은 {@link MOVE_SEND_MS} 박자로 한 번에 하나만 보내고(내 `UNIT_MOVED`·`ERROR`
 * 가 와야 다음), 응답은 스토어 구독으로 **같은 순간에** 받아 계획을 고친다 — 그래야 화면이 서버 자리 + 남은 걸음을 한 프레임도 겹쳐 걷지 않는다.
 */
export function useLiveMove({ active, mySeat, turnKey, sendAction }: Args): LiveMove {
  const planRef = useRef<MovePlan>(EMPTY_PLAN);
  const [, setVersion] = useState(0);
  const sendRef = useRef(sendAction);
  sendRef.current = sendAction;

  const commit = useCallback((plan: MovePlan) => {
    planRef.current = plan;
    useArtilleryStore.getState().setMoveInFlight(plan.inflight !== null);
    setVersion((v) => v + 1);
  }, []);

  const emit = useCallback((send: Send | null) => {
    if (!send) return;
    if (send.kind === 'MOVE') {
      sendRef.current({ '@action': 'MOVE', dx: send.dx });
    } else {
      const { moveDx, angle, power, shell, item } = send;
      sendRef.current({ '@action': 'FIRE', moveDx, angle, power, shell, ...(item ? { item } : {}) });
    }
  }, []);

  const pump = useCallback(
    (moveTick: boolean) => {
      const now = Date.now();
      const plan = timedOut(planRef.current, now);
      const r = nextSend(plan, now, moveTick);
      if (r.plan !== planRef.current) commit(r.plan);
      emit(r.send);
    },
    [commit, emit],
  );

  // 차례가 바뀌거나 손을 못 쓰게 되면 버린다.
  useEffect(() => {
    commit(EMPTY_PLAN);
  }, [turnKey, commit]);
  useEffect(() => {
    if (!active && (hasPendingSteps(planRef.current) || planRef.current.fire)) commit(EMPTY_PLAN);
  }, [active, commit]);

  // 응답·거절·resync — 스토어가 바뀌는 그 순간에.
  useEffect(
    () =>
      useArtilleryStore.subscribe((st, prev) => {
        if (st.snapshotNonce !== prev.snapshotNonce) {
          // 재접속 — 로컬에 모아 두고 못 보낸 걸음은 버린다(§6.5).
          if (planRef.current !== EMPTY_PLAN) commit(EMPTY_PLAN);
          return;
        }
        if (st.lastMove && st.lastMove !== prev.lastMove && st.lastMove.seat === mySeat) {
          commit(acked(planRef.current));
          pump(false);
          return;
        }
        if (st.lastError && st.lastError !== prev.lastError && planRef.current.inflight !== null) {
          const r = rejected(planRef.current, st.lastError.code, Date.now());
          commit(r.plan);
          emit(r.resend);
          if (!r.resend) pump(false);
        }
      }),
    [mySeat, commit, emit, pump],
  );

  // 박자 — 보낼 것이 있는 동안만.
  const busy = hasPendingSteps(planRef.current) || planRef.current.fire !== null;
  useEffect(() => {
    if (!active || !busy) return;
    const timer = window.setInterval(() => pump(true), MOVE_SEND_MS);
    return () => window.clearInterval(timer);
  }, [active, busy, pump]);

  const s = useArtilleryStore();
  const me = s.units.find((u) => u.seat === mySeat) ?? null;
  const confirmed: Predicted | null = me ? { x: me.x, footY: me.footY, fuelLeft: s.fuelLeft ?? me.fuel } : null;
  const predicted = confirmed && s.mask ? predict(s.mask, confirmed, planRef.current, s.cloudLevel) : confirmed;

  // 늘 지금 스토어·계획에서 다시 예측한다 — 꾹 누르기 타이머가 몇 번이고 부르는 사이 렌더가 없어도 낭떠러지 앞에서 멈추게.
  const step = (dir: -1 | 1) => {
    const st = useArtilleryStore.getState();
    const mine = st.units.find((u) => u.seat === mySeat);
    if (!active || !mine || !st.mask || planRef.current.fire) return false;
    const from = predict(st.mask, { x: mine.x, footY: mine.footY, fuelLeft: st.fuelLeft ?? mine.fuel }, planRef.current, st.cloudLevel);
    const p = walk(st.mask, from.x, from.footY, dir, from.fuelLeft, st.cloudLevel);
    if (p.dx !== dir) return false;
    commit(addStep(planRef.current, dir));
    return true;
  };

  const requestFire = (req: FireRequest) => {
    if (!active || planRef.current.fire) return;
    useArtilleryStore.getState().setPending(true);
    commit({ ...planRef.current, fire: req });
    pump(false);
  };

  return { predicted, step, requestFire, walking: hasPendingSteps(planRef.current) };
}
