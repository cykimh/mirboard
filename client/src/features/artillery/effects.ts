import type { ShotReport } from '@/types/artillery';
import { TICK_MS, timeline, type FrameUnit } from './replay';

/**
 * 대본 연출 사건 (순수 함수 — 스킨 밖, D-252). 서버 대본에서 "언제·어디서 무슨 일이 났나"만 뽑는다 — 포구 섬광, 착탄, 홀씨 갈라짐,
 * 피해·회복 숫자, 구름 바다 풍덩, 쓰러져 사라짐. 스킨(`drawEffects`)은 사건 시각부터 지난 시간의 **닫힌 식**으로 입자를 그린다
 * (입자 상태·할당 없음 — 같은 시각이면 같은 그림).
 *
 * <p>사건 시각은 모두 대본 타임라인 안이다. 입자·숫자의 꼬리({@link FX_TAIL_MS})는 재생이 끝난 뒤 화면에서만 사라지고 재생 길이
 * (`replayLengthMs`)·조작 가능 시점을 바꾸지 않는다.
 */
export type FxKind = 'muzzle' | 'impact' | 'split' | 'damage' | 'heal' | 'splash' | 'poof';

export interface FxEvent {
  kind: FxKind;
  /** 재생 시작부터(ms). */
  at: number;
  x: number;
  y: number;
  /** 착탄 = 구멍 반경(칸). */
  r: number;
  /** 착탄 = 둔덕(덩굴탄). */
  fill: boolean;
  /** 포구 = 발사 각도(도). */
  angle: number;
  /** 피해·회복 양. */
  amount: number;
  seat: number;
  /** 입자 난수 씨앗(사건마다 다르고 결정적). */
  seed: number;
}

/** 재생이 끝난 뒤 입자·숫자가 사라질 때까지(ms). */
export const FX_TAIL_MS = 900;

const UNIT_MID = 5;

export function fxScript(r: ShotReport, before: FrameUnit[]): FxEvent[] {
  const tl = timeline(r);
  const out: FxEvent[] = [];
  const push = (kind: FxKind, tick: number, x: number, y: number, o: Partial<FxEvent> = {}) =>
    out.push({ kind, at: tick * TICK_MS, x, y, r: 0, fill: false, angle: 0, amount: 0, seat: -1, seed: out.length * 7919 + 13, ...o });
  // 포대 자리 — 걸음·낙하를 따라 고친다(피해 숫자가 뜰 자리).
  const pos = new Map<number, { x: number; footY: number }>();
  for (const u of before) pos.set(u.seat, { x: u.x, footY: u.footY });
  const shooter = pos.get(r.seat);
  if (shooter) {
    const n = r.move.path.length;
    if (n >= 2) {
      shooter.x = r.move.path[n - 2];
      shooter.footY = r.move.path[n - 1];
    }
    if (r.heal && r.heal.amount > 0) push('heal', 0, shooter.x + 0.5, shooter.footY - UNIT_MID, { amount: r.heal.amount, seat: r.seat });
    const fall = r.move.fall;
    if (fall) {
      shooter.footY = fall.toY;
      if (fall.intoCloud) push('splash', tl.walkFallEnd, fall.x + 0.5, fall.toY, { seat: r.seat });
      else if (fall.damage > 0) push('damage', tl.walkFallEnd, fall.x + 0.5, fall.toY - UNIT_MID, { amount: fall.damage, seat: r.seat });
    }
  }
  r.stages.forEach((st, si) => {
    const times = tl.stages[si];
    let first = -1;
    for (let i = 0; i < st.shots.length; i++) if (first < 0 || st.shots[i].startTick < st.shots[first].startTick) first = i;
    if (first >= 0 && st.shots[first].path.length >= 2) {
      const sh = st.shots[first];
      push('muzzle', times.start + sh.startTick, sh.path[0] + 0.5, sh.path[1] + 0.5, { angle: r.angle, seat: r.seat });
    }
    for (const sh of st.shots) {
      const land = times.start + sh.startTick + sh.ticks;
      if (sh.end === 'SPLIT') push('split', land, sh.impactX + 0.5, sh.impactY + 0.5);
      else if (sh.crater) push('impact', land, sh.crater.x + 0.5, sh.crater.y + 0.5, { r: sh.crater.r, fill: !!sh.crater.fill });
      else if (sh.end === 'CLOUD') push('splash', land, sh.impactX + 0.5, sh.impactY);
    }
    for (const d of st.damages) {
      const p = pos.get(d.seat);
      if (p && d.amount > 0) push('damage', times.flightEnd, p.x + 0.5, p.footY - UNIT_MID, { amount: d.amount, seat: d.seat, fill: d.direct });
    }
    for (const f of st.falls) {
      const p = pos.get(f.seat);
      if (p) p.footY = f.toY;
      const at = times.explosionEnd + (f.toY - f.fromY);
      if (f.intoCloud) push('splash', at, f.x + 0.5, f.toY, { seat: f.seat });
      else if (f.damage > 0) push('damage', at, f.x + 0.5, f.toY - UNIT_MID, { amount: f.damage, seat: f.seat });
    }
    // 이 단계에서 시든 포대 — 단계가 끝나 사라지는 순간 퐁.
    for (const e of r.eliminated) {
      if (e.cause !== 'KNOCKED_OUT') continue;
      const p = pos.get(e.seat);
      const downHere =
        st.damages.some((d) => d.seat === e.seat && d.hpAfter <= 0) || st.falls.some((f) => f.seat === e.seat && !f.intoCloud && f.hpAfter <= 0);
      if (p && downHere) push('poof', times.fallEnd, p.x + 0.5, p.footY - 3, { seat: e.seat });
    }
  });
  return out;
}

/** 마지막 사건의 꼬리가 끝나는 시각(ms) — 이 뒤로는 그릴 것이 없다. */
export function fxEndMs(events: readonly FxEvent[]): number {
  let end = 0;
  for (const e of events) if (e.at > end) end = e.at;
  return events.length > 0 ? end + FX_TAIL_MS : 0;
}

const SHAKE_MS = 380;

/**
 * 화면 흔들림(칸) — 착탄마다 구멍 크기에 비례해 흔들고 {@link SHAKE_MS} 안에 잦아든다. {@code out} 에 쓴다(할당 없음).
 * 난수 대신 시각의 사인이라 같은 시각이면 같은 값이다.
 */
export function shakeAt(events: readonly FxEvent[], nowMs: number, amp: number, out: { x: number; y: number }): void {
  let a = 0;
  for (const e of events) {
    if (e.kind !== 'impact') continue;
    const age = nowMs - e.at;
    if (age < 0 || age >= SHAKE_MS) continue;
    const k = 1 - age / SHAKE_MS;
    a = Math.max(a, amp * Math.min(1.8, e.r / 14) * (e.fill ? 0.4 : 1) * k * k);
  }
  out.x = a === 0 ? 0 : Math.sin(nowMs * 0.093) * a;
  out.y = a === 0 ? 0 : Math.cos(nowMs * 0.127 + 1.3) * a * 0.8;
}
