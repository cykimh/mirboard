import { walk, type Mask } from './terrain';

/**
 * 실시간 걷기·조준 공유 (S6b, D-251 — 설계 `docs/plans/artillery-s6.md` §3·§6). 순수 함수만 — 화면·소켓은 `useLiveMove`·게임판이 붙인다.
 *
 * <p>걷기는 그 자리에서 서버에 확정된다(`MOVE`, D-228). 서버는 같은 세션의 프레임 순서를 보장하지 않으므로 **한 번에 하나만** 보낸다 —
 * 내 좌석의 `UNIT_MOVED`(또는 `ERROR`)가 와야 다음. 그 사이 걸음은 방향이 같은 조각으로 모아 두고(방향이 바뀌면 새 조각), 화면은 서버가
 * 확정한 자리 + 아직 확정 안 된 조각을 로컬로 걸어 예측한다. [발사]는 응답 안 온 `MOVE` 를 앞지르지 않는다 — 그 응답을 기다렸다가 남은
 * 조각 하나를 `FIRE.moveDx` 로 싣는다.
 */

/** 걷기 보내는 간격(ms) — 초당 4번(사용자 결정, D-228). */
export const MOVE_SEND_MS = 250;
/** 미리보기 보내는 최소 간격(ms) — 초당 4번. */
export const PREVIEW_SEND_MS = 250;
/** 바뀌지 않아도 이 간격(ms)마다 미리보기를 다시 보낸다 — resync 로 들어온 사람의 포신을 맞춘다(§6.5). */
export const PREVIEW_KEEPALIVE_MS = 2000;
/** 응답이 이만큼(ms) 안 오면 보낸 `MOVE` 를 잃은 것으로 보고 서버 자리로 돌아간다(끊김·재접속). */
export const MOVE_ACK_TIMEOUT_MS = 3000;
/** 남의 걸음·포신을 따라가는 시간(ms) — 보내는 간격과 같다. */
export const FOLLOW_MS = 250;

export interface FireRequest {
  angle: number;
  power: number;
  shell: string;
  item: string | null;
}

/** 보내기 계획 — `inflight` 는 보내고 답을 기다리는 `MOVE.dx`, `queued` 는 아직 안 보낸 조각(부호가 방향). */
export interface MovePlan {
  inflight: number | null;
  inflightAt: number;
  /** BUSY 로 한 번 다시 보냈다(§6.2 — 한 번만). */
  retried: boolean;
  queued: number[];
  /** [발사]를 눌렀다 — 걸음이 다 확정되면(남은 조각 ≤ 1) 보낸다. */
  fire: FireRequest | null;
}

export const EMPTY_PLAN: MovePlan = { inflight: null, inflightAt: 0, retried: false, queued: [], fire: null };

export type Send =
  | { kind: 'MOVE'; dx: number }
  | ({ kind: 'FIRE'; moveDx: number } & FireRequest);

/** 한 걸음 더(방향이 같으면 마지막 조각에 붙이고, 바뀌면 새 조각). */
export function addStep(plan: MovePlan, dir: -1 | 1): MovePlan {
  const q = plan.queued;
  const last = q[q.length - 1];
  if (last !== undefined && Math.sign(last) === dir) {
    return { ...plan, queued: [...q.slice(0, -1), last + dir] };
  }
  return { ...plan, queued: [...q, dir] };
}

/**
 * 지금 보낼 것. {@code moveTick} 이 참일 때만 `MOVE` 를 보낸다(250ms 박자 — 응답이 빨라도 초당 4번을 넘지 않게). `FIRE` 는 박자를 기다리지
 * 않는다 — 답을 기다리는 `MOVE` 가 없고 남은 조각이 하나 이하면 바로.
 */
export function nextSend(plan: MovePlan, now: number, moveTick: boolean): { plan: MovePlan; send: Send | null } {
  if (plan.inflight !== null) return { plan, send: null };
  if (plan.fire && plan.queued.length <= 1) {
    const moveDx = plan.queued[0] ?? 0;
    return { plan: EMPTY_PLAN, send: { kind: 'FIRE', moveDx, ...plan.fire } };
  }
  if (moveTick && plan.queued.length > 0) {
    const [dx, ...rest] = plan.queued;
    return { plan: { ...plan, inflight: dx, inflightAt: now, retried: false, queued: rest }, send: { kind: 'MOVE', dx } };
  }
  return { plan, send: null };
}

/** 내 `UNIT_MOVED` — 보낸 조각이 확정됐다. */
export function acked(plan: MovePlan): MovePlan {
  return plan.inflight === null ? plan : { ...plan, inflight: null, retried: false };
}

/**
 * 보낸 `MOVE` 가 거절됐다. `BUSY` 는 한 번 다시 보낸다(같은 조각). 그 밖(벽·연료 `CANNOT_MOVE`·차례 끝)이면 모아 둔 조각도 버린다 — 서버 자리로
 * 돌아간다. 걸어 두고 누른 [발사]는 남긴다(이동 없이 쏜다).
 */
export function rejected(plan: MovePlan, code: string, now: number): { plan: MovePlan; resend: Send | null } {
  if (plan.inflight === null) return { plan, resend: null };
  if (code === 'BUSY' && !plan.retried) {
    return { plan: { ...plan, retried: true, inflightAt: now }, resend: { kind: 'MOVE', dx: plan.inflight } };
  }
  return { plan: { ...EMPTY_PLAN, fire: plan.fire }, resend: null };
}

/** 답이 너무 늦다 — 잃은 것으로 보고 서버 자리로. */
export function timedOut(plan: MovePlan, now: number): MovePlan {
  if (plan.inflight === null || now - plan.inflightAt < MOVE_ACK_TIMEOUT_MS) return plan;
  return { ...EMPTY_PLAN, fire: plan.fire };
}

export function hasPendingSteps(plan: MovePlan): boolean {
  return plan.inflight !== null || plan.queued.length > 0;
}

export interface Predicted {
  x: number;
  footY: number;
  fuelLeft: number;
}

/** 서버가 확정한 자리에서 아직 확정 안 된 조각(보낸 것 → 모은 것)을 차례로 걸은 예측 자리. */
export function predict(mask: Mask, from: Predicted, plan: MovePlan, cloudLevel: number): Predicted {
  let { x, footY, fuelLeft } = from;
  const segs = plan.inflight === null ? plan.queued : [plan.inflight, ...plan.queued];
  for (const dx of segs) {
    const p = walk(mask, x, footY, dx, fuelLeft, cloudLevel);
    x = p.x;
    footY = p.footY;
    fuelLeft = p.fuelLeft;
  }
  return { x, footY, fuelLeft };
}

// ---------- 남의 걸음·포신 따라가기 ----------

/** 남의 `UNIT_MOVED` 한 번 — 출발점에서 경로를 {@link FOLLOW_MS} 동안 걷고, 떨어졌으면 마지막에 내려간다. */
export interface WalkFollow {
  seat: number;
  fromX: number;
  fromY: number;
  /** [x, footY, …] — 걸음마다 발 칸(출발점 제외). 떨어졌으면 끝에 낙하 도착 칸을 붙인다. */
  path: number[];
  startedAt: number;
  nonce: number;
}

/** 따라가는 중이면 지금 자리, 끝났으면 null. */
export function followPosition(f: WalkFollow | null, now: number): { x: number; footY: number } | null {
  if (!f) return null;
  const steps = f.path.length / 2;
  const t = now - f.startedAt;
  if (steps === 0 || t >= FOLLOW_MS || t < 0) return null;
  const i = Math.floor((t / FOLLOW_MS) * steps);
  if (i <= 0) return { x: f.fromX, footY: f.fromY };
  return { x: f.path[2 * (i - 1)], footY: f.path[2 * (i - 1) + 1] };
}

/** 포신 각도 보간 — {@code from} 에서 {@code to} 로 {@link FOLLOW_MS} 동안. */
export function easeAngle(from: number, to: number, startedAt: number, now: number): number {
  const t = Math.min(1, Math.max(0, (now - startedAt) / FOLLOW_MS));
  return from + (to - from) * t;
}

/** 미리보기 보내기 판단 — 바뀌었으면 간격을 지켜서, 안 바뀌었어도 유지 간격마다. */
export function previewDue(changed: boolean, lastSentAt: number, now: number): boolean {
  if (lastSentAt <= 0) return true;
  const since = now - lastSentAt;
  return changed ? since >= PREVIEW_SEND_MS : since >= PREVIEW_KEEPALIVE_MS;
}
