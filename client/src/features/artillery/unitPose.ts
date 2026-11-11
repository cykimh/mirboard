import type { ShotReport } from '@/types/artillery';
import { TICK_MS, timeline, type FrameUnit } from './replay';

/**
 * 포대 상태 판정 (순수 함수 — 스킨 밖, D-227). 스킨은 "지금 어떤 상태고 그 상태가 된 지 몇 ms 인가"만 받아 그린다.
 *
 * <p>상태는 화면 연출일 뿐이다 — 맞는 범위·피해·차례는 서버 대본 그대로이고, 창은 모두 대본 타임라인({@link timeline}) 안에 있어
 * 재생 길이(`replayLengthMs`)를 늘리지 않는다. 우선순위: 쓰러짐 > 피격 > 발사 반동 > 조준 > 대기.
 *
 * <ul>
 *   <li>쓰러짐 — 살아 있지만 체력 0(마지막 단계가 끝나 사라지기 전까지), 또는 이미 탈락.</li>
 *   <li>피격 — 대본의 피해(씨앗이 다 내려앉은 순간)·낙하 피해(땅에 닿은 순간)부터 {@link HIT_TICKS}.</li>
 *   <li>발사 반동 — 쏜 포대, 단계마다 첫 씨앗이 나가는 순간부터 {@link RECOIL_TICKS}.</li>
 *   <li>조준 — 재생 밖에서는 차례 주인, 재생 중에는 쏜 포대가 첫 발을 쏘기 전(걷는 동안 포함).</li>
 * </ul>
 */
export type UnitPose = 'idle' | 'aim' | 'recoil' | 'hit' | 'down';

export const UNIT_POSES: readonly UnitPose[] = ['idle', 'aim', 'recoil', 'hit', 'down'];

/** 발사 반동 길이(틱) — 200ms. */
export const RECOIL_TICKS = 12;
/** 피격 길이(틱) — 400ms. */
export const HIT_TICKS = 24;

export interface PoseState {
  pose: UnitPose;
  /** 이 상태가 된 지 지난 시간(ms). 대기·조준은 계속 도는 애니메이션이라 좌석마다 어긋난 시계를 준다. */
  elapsedMs: number;
}

interface Window {
  seat: number;
  start: number;
  end: number;
}

/** 대본 한 개의 상태 창 — 대본이 바뀔 때 한 번 만든다(프레임마다 만들지 않는다). */
export interface PoseScript {
  shooter: number;
  /** 쏜 포대가 첫 씨앗을 내기 전까지(틱). 발사가 없으면 대본 끝. */
  firstShot: number;
  recoils: Window[];
  hits: Window[];
  /** 좌석 → 체력이 0 이 된 틱. */
  downAt: Map<number, number>;
  /** 대본 끝(틱) — 이 뒤로는 재생 밖과 같다. */
  end: number;
}

export function poseScript(r: ShotReport): PoseScript {
  const tl = timeline(r);
  const recoils: Window[] = [];
  const hits: Window[] = [];
  const downAt = new Map<number, number>();
  const hit = (seat: number, at: number, hpAfter: number) => {
    hits.push({ seat, start: at, end: Math.min(tl.end, at + HIT_TICKS) });
    if (hpAfter <= 0 && !downAt.has(seat)) downAt.set(seat, at);
  };
  const fall = r.move.fall;
  if (fall && fall.damage > 0) hit(r.seat, tl.walkFallEnd, fall.hpAfter);
  let firstShot = tl.end;
  r.stages.forEach((st, si) => {
    const times = tl.stages[si];
    if (st.shots.length > 0) {
      const first = st.shots.reduce((m, sh) => Math.min(m, sh.startTick), Number.POSITIVE_INFINITY);
      const at = times.start + first;
      firstShot = Math.min(firstShot, at);
      recoils.push({ seat: r.seat, start: at, end: Math.min(tl.end, at + RECOIL_TICKS) });
    }
    for (const d of st.damages) if (d.amount > 0) hit(d.seat, times.flightEnd, d.hpAfter);
    for (const f of st.falls) if (f.damage > 0) hit(f.seat, times.explosionEnd + (f.toY - f.fromY), f.hpAfter);
  });
  return { shooter: r.seat, firstShot, recoils, hits, downAt, end: tl.end };
}

/** 좌석마다 대기 애니메이션이 같이 움직이지 않게 어긋내는 값(ms). */
const SEAT_PHASE_MS = 137;

/**
 * 한 포대의 상태를 {@code out} 에 쓴다(할당 없음). {@code script}·{@code replayMs} 는 재생 중일 때만, {@code time} 은 화면 시계(ms).
 */
export function poseAt(
  u: FrameUnit,
  script: PoseScript | null,
  replayMs: number | null,
  turnSeat: number,
  time: number,
  out: PoseState,
): PoseState {
  const loop = time + u.seat * SEAT_PHASE_MS;
  const t = script && replayMs !== null ? replayMs / TICK_MS : null;
  const playing = script !== null && t !== null && t < script.end;
  if (!u.alive || u.hp <= 0) {
    const at = playing ? script.downAt.get(u.seat) : undefined;
    out.pose = 'down';
    out.elapsedMs = at !== undefined ? Math.max(0, (t! - at) * TICK_MS) : loop;
    return out;
  }
  if (playing) {
    if (latest(script.hits, u.seat, t!, out, 'hit')) return out;
    if (latest(script.recoils, u.seat, t!, out, 'recoil')) return out;
    out.elapsedMs = loop;
    out.pose = u.seat === script.shooter && t! < script.firstShot ? 'aim' : 'idle';
    return out;
  }
  out.pose = u.seat === turnSeat ? 'aim' : 'idle';
  out.elapsedMs = loop;
  return out;
}

/** 지금 열려 있는 창 중 가장 늦게 열린 것(겹씨앗·연속 피해는 새 창이 이긴다). */
function latest(windows: Window[], seat: number, t: number, out: PoseState, pose: UnitPose): boolean {
  let best = -1;
  for (const w of windows) if (w.seat === seat && t >= w.start && t < w.end && w.start > best) best = w.start;
  if (best < 0) return false;
  out.pose = pose;
  out.elapsedMs = (t - best) * TICK_MS;
  return true;
}
