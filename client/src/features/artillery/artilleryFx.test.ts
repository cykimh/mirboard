/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다(artilleryClay.test 와 같은 패턴).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { FIELD_H, FIELD_W, type Shell } from '@/types/artillery';
import { DEBRIS_MAX, DEBRIS_TICKS, SCORCH_DIRS, debrisAt, planDebris } from './debris';
import { drawScene, type Scene } from './draw';
import { FX_TAIL_MS, SHAKE_DAMAGE_CAP, fxEndMs, fxScript, shakeAt } from './effects';
import { frameAt, replayLengthMs, TICK_MS, timeline } from './replay';
import { carve, maskFromRuns } from './terrain';
import { flatRuns, hitReport, hitShot, hitStage } from './testFixtures';
import { poseAt, poseScript, type PoseState } from './unitPose';
import { parseAtlas } from './skins/atlas';
import { createClaySkin } from './skins/claySkin';
import { CLAY_FX, CLAY_FX_CAPS, CLAY_FX_TAIL_MS, FALLEN_MS, HIT_WOBBLE_MS, fallenPose, hitSquash } from './skins/clayFx';
import { vectorSkin } from './skins/vectorSkin';
import { SKIN_DIRS } from './skins';

function read(relative: string, enc?: string): string & Uint8Array {
  const f = readFileSync as unknown as (p: URL, enc?: string) => string & Uint8Array;
  return f(new URL(relative, import.meta.url), enc);
}
const DIR = `../../../public/artillery/skins/${SKIN_DIRS.clay}/`;
const bytes = read(`${DIR}atlas.png`);
const size = {
  w: (bytes[16] << 24) | (bytes[17] << 16) | (bytes[18] << 8) | bytes[19],
  h: (bytes[20] << 24) | (bytes[21] << 16) | (bytes[22] << 8) | bytes[23],
};
const atlas = () => parseAtlas(JSON.parse(read(`${DIR}atlas.json`, 'utf-8')), size);
const clay = () => createClaySkin('clay', atlas(), { image: {} as CanvasImageSource, width: size.w, height: size.h }, () => null);

const before = [
  { seat: 0, x: 100, footY: 299, hp: 100, alive: true, kind: 'SPROUT' as const },
  { seat: 1, x: 500, footY: 299, hp: 100, alive: true, kind: 'ACORN' as const },
];
const koReport = (shell: Shell = 'PEA') =>
  hitReport({
    shell,
    stages: [hitStage({ damages: [{ seat: 1, amount: 100, hpAfter: 0, direct: true }] })],
    eliminated: [{ seat: 1, cause: 'KNOCKED_OUT' }],
    units: [
      { seat: 0, x: 102, footY: 299, hp: 100, alive: true },
      { seat: 1, x: 500, footY: 307, hp: 0, alive: false },
    ],
  });

/** 모든 그리기 호출을 받아 넘기는 가짜 Canvas — 부른 메서드 이름을 센다. */
function fakeCtx(calls: Map<string, number>): CanvasRenderingContext2D {
  return new Proxy({} as Record<string, unknown>, {
    get(_t, k) {
      if (typeof k === 'symbol') return undefined;
      return (..._a: unknown[]) => {
        calls.set(k, (calls.get(k) ?? 0) + 1);
        if (k === 'createLinearGradient' || k === 'createRadialGradient') return { addColorStop() {} };
        if (k === 'measureText') return { width: 20 };
        return undefined;
      };
    },
    set: () => true,
  }) as unknown as CanvasRenderingContext2D;
}

describe('연출 사건 — 탄 종류·쓰러짐 (D-254)', () => {
  it('사건마다 대본의 탄 종류가 실린다', () => {
    for (const shell of ['PEA', 'PUMPKIN', 'VINE', 'CHESTNUT', 'SPORE'] as const) {
      const ev = fxScript(hitReport({ shell }), before);
      expect(ev.length).toBeGreaterThan(0);
      for (const e of ev) expect(e.shell).toBe(shell);
    }
  });

  it('체력이 0 이 된 포대 — 쓰러진 순간·결과 자리·종류·대본에서 사라지는 시각', () => {
    const r = koReport();
    const tl = timeline(r);
    const ev = fxScript(r, before);
    const f = ev.filter((e) => e.kind === 'fallen');
    expect(f).toHaveLength(1);
    expect(f[0]).toMatchObject({ seat: 1, x: 500, y: 307, unitKind: 'ACORN' });
    // 쓰러지는 순간은 피격과 같은 틱(상태 창 `downAt`), 사라지는 시각은 그 단계의 낙하 끝 — 둘 다 대본 안이다.
    expect(f[0].at).toBeCloseTo(poseScript(r).downAt.get(1)! * TICK_MS);
    expect(f[0].until).toBeCloseTo(tl.stages[0].fallEnd * TICK_MS);
    expect(f[0].until).toBeLessThanOrEqual(replayLengthMs(r));
    // 살아남으면 없다. 구름 바다에 빠진 포대도 없다(풍덩이 따로 있다).
    expect(fxScript(hitReport(), before).some((e) => e.kind === 'fallen')).toBe(false);
    const drowned = hitReport({
      stages: [hitStage({ damages: [], falls: [{ seat: 1, x: 500, fromY: 299, toY: 340, damage: 0, hpAfter: 0, intoCloud: true }] })],
      eliminated: [{ seat: 1, cause: 'CLOUD_SEA' }],
    });
    expect(fxScript(drowned, before).some((e) => e.kind === 'fallen')).toBe(false);
  });

  it('걷다 떨어져 쓰러진 포대 — 발사 없이도 쓰러짐이 있다', () => {
    const r = hitReport({
      move: { path: [101, 299], toX: 101, toY: 320, fuelLeft: 99, fall: { seat: 0, x: 101, fromY: 299, toY: 320, damage: 100, hpAfter: 0, intoCloud: false } },
      stages: [],
      eliminated: [{ seat: 0, cause: 'KNOCKED_OUT' }],
      units: [
        { seat: 0, x: 101, footY: 320, hp: 0, alive: false },
        { seat: 1, x: 500, footY: 299, hp: 100, alive: true },
      ],
    });
    const f = fxScript(r, before).find((e) => e.kind === 'fallen')!;
    expect(f).toMatchObject({ seat: 0, x: 101, y: 320, unitKind: 'SPROUT' });
    expect(f.until).toBeCloseTo(replayLengthMs(r));
  });

  it('꼬리 길이는 스킨이 정할 수 있고, 재생 길이는 그대로다', () => {
    const r = koReport();
    const ev = fxScript(r, before);
    expect(fxEndMs(ev)).toBeLessThanOrEqual(replayLengthMs(r) + FX_TAIL_MS);
    expect(fxEndMs(ev, CLAY_FX_TAIL_MS) - fxEndMs(ev)).toBe(CLAY_FX_TAIL_MS - FX_TAIL_MS);
    // 쓰러진 포대가 퐁 사라지고 잎이 다 떨어질 때까지 꼬리 안이다.
    expect(FALLEN_MS + 80 * TICK_MS).toBeLessThanOrEqual(CLAY_FX_TAIL_MS);
    expect(replayLengthMs(r)).toBe(timeline(r).end * TICK_MS);
  });
});

describe('화면 흔들림은 피해량에 비례한다 (D-254)', () => {
  const peak = (amount: number, damageAmp: number) => {
    const ev = fxScript(hitReport({ stages: [hitStage({ damages: [{ seat: 1, amount, hpAfter: 100 - amount, direct: true }] })] }), before);
    const at = ev.find((e) => e.kind === 'damage')!.at;
    let max = 0;
    const o = { x: 0, y: 0 };
    for (let t = 0; t < 200; t += 4) {
      shakeAt(ev, at + t, 1.4, o, damageAmp);
      max = Math.max(max, Math.hypot(o.x, o.y));
    }
    return max;
  };

  it('크게 맞을수록 크게 흔들리고 상한이 있다', () => {
    expect(peak(10, 4)).toBeLessThan(peak(30, 4));
    expect(peak(30, 4)).toBeLessThan(peak(60, 4));
    expect(peak(300, 4)).toBeLessThanOrEqual((1.4 * 1.8 + 4 * SHAKE_DAMAGE_CAP) * 1.3);
    expect(peak(300, 4)).toBeCloseTo(peak(30 * SHAKE_DAMAGE_CAP, 4));
  });

  it('피해 몫을 주지 않으면(다른 스킨) 예전과 같다', () => {
    expect(peak(10, 0)).toBe(peak(90, 0));
  });
});

describe('폭발 파편 경로 (D-254)', () => {
  const ground = carve(maskFromRuns(flatRuns(300)), 320, 300, 14);
  const solidAt = (x: number, y: number) => {
    const xi = Math.round(x);
    const yi = Math.round(y);
    return xi >= 0 && xi < FIELD_W && yi >= 0 && yi < FIELD_H && ground[yi * FIELD_W + xi] === 1;
  };

  it('덩어리 수는 상한을 넘지 않고, 같은 씨앗이면 같은 경로다', () => {
    const a = planDebris(77, 320, 300, 14, 99, ground);
    const b = planDebris(77, 320, 300, 14, 99, ground);
    expect(a.n).toBe(DEBRIS_MAX);
    expect(a.path.length).toBe(DEBRIS_MAX * DEBRIS_TICKS * 2);
    expect(Array.from(a.path)).toEqual(Array.from(b.path));
    expect(planDebris(78, 320, 300, 14, 12, ground).path[2]).not.toBe(planDebris(77, 320, 300, 14, 12, ground).path[2]);
    expect(planDebris(1, 320, 300, 14, -3, ground).n).toBe(0);
  });

  it('위로 튀어 올랐다가 땅에 닿아 굴러 멈춘다 — 흙 속으로 들어가지 않는다', () => {
    const plan = planDebris(5, 320, 300, 14, 12, ground);
    const p = { x: 0, y: 0 };
    let rested = 0;
    for (let i = 0; i < plan.n; i++) {
      debrisAt(plan, i, 0, p);
      const startY = p.y;
      let top = startY;
      for (let t = 0; t < DEBRIS_TICKS; t++) {
        debrisAt(plan, i, t, p);
        top = Math.min(top, p.y);
        expect(solidAt(p.x, p.y)).toBe(false);
      }
      expect(top).toBeLessThan(startY); // 올라갔다
      expect(plan.firstHit[i]).toBeLessThan(DEBRIS_TICKS); // 평평한 땅이라 모두 닿는다
      if (plan.rest[i] < DEBRIS_TICKS) {
        rested++;
        // 멈춘 뒤에는 그 자리 그대로, 바로 아래가 흙이다
        debrisAt(plan, i, plan.rest[i], p);
        const rx = p.x;
        const ry = p.y;
        debrisAt(plan, i, DEBRIS_TICKS - 1, p);
        expect(p.x).toBeCloseTo(rx);
        expect(p.y).toBeCloseTo(ry);
        expect(solidAt(p.x, p.y + 1.5)).toBe(true);
      }
    }
    expect(rested).toBeGreaterThanOrEqual(plan.n - 2);
    // 경로 밖 시각은 끝 자리로 붙는다
    debrisAt(plan, 0, 9999, p);
    const end = { x: 0, y: 0 };
    debrisAt(plan, 0, DEBRIS_TICKS - 1, end);
    expect(p).toEqual(end);
  });

  it('그을음은 흙이 남은 테두리에만 — 평지 구멍이면 아래쪽 반', () => {
    const plan = planDebris(5, 320, 300, 14, 8, ground);
    for (let i = 0; i < SCORCH_DIRS; i++) {
      const sin = Math.sin((i / SCORCH_DIRS) * Math.PI * 2);
      if (sin > 0.3) expect(plan.scorch & (1 << i)).not.toBe(0);
      if (sin < -0.3) expect(plan.scorch & (1 << i)).toBe(0);
    }
  });

  it('마스크가 없으면 착탄 높이 아래 평평한 바닥으로 친다', () => {
    const plan = planDebris(5, 320, 200, 10, 8, null);
    const p = { x: 0, y: 0 };
    for (let i = 0; i < plan.n; i++) {
      debrisAt(plan, i, DEBRIS_TICKS - 1, p);
      expect(p.y).toBeLessThan(211);
    }
  });
});

describe('피격·쓰러짐 움직임 (D-254)', () => {
  it('피격 — 탁 눌렸다가 출렁이며 잦아들고, 창(400ms)이 끝난 뒤에도 이어진다', () => {
    expect(hitSquash(-1)).toBe(0);
    expect(hitSquash(60)).toBeGreaterThan(1);
    let min = 0;
    for (let t = 70; t < 400; t += 5) min = Math.min(min, hitSquash(t));
    expect(min).toBeLessThan(-0.3); // 길쭉하게 튕겨 오른다
    expect(Math.abs(hitSquash(500))).toBeGreaterThan(0);
    expect(Math.abs(hitSquash(1000))).toBeLessThan(0.05);
    expect(hitSquash(HIT_WOBBLE_MS)).toBe(0);
  });

  it('쓰러짐 — 기우뚱(반대쪽) → 넘어감 → 통 튐 → 잎이 시든다', () => {
    const o = { tilt: 0, lift: 0, wilt: 0 };
    fallenPose(100, o);
    expect(o.tilt).toBeLessThan(0);
    fallenPose(400, o);
    expect(o.tilt).toBeGreaterThan(0);
    expect(o.tilt).toBeLessThan(1);
    expect(o.wilt).toBe(0);
    fallenPose(680, o);
    expect(o.lift).toBeGreaterThan(1);
    fallenPose(FALLEN_MS, o);
    expect(o).toEqual({ tilt: 1, lift: 0, wilt: 1 });
  });
});

describe('클레이 연출 층으로 장면 그리기 (D-254)', () => {
  const fresh = (): PoseState => ({ pose: 'idle', elapsedMs: 0 });

  it('켜져 있으면 피해 비례 흔들림·긴 꼬리를 선언한다 — 도형 스킨에는 없다', () => {
    const skin = clay();
    expect(CLAY_FX).toBe(true);
    expect(skin.shakeDamageCells).toBeGreaterThan(0);
    expect(skin.fxTailMs).toBe(CLAY_FX_TAIL_MS);
    expect(vectorSkin.shakeDamageCells ?? vectorSkin.fxTailMs).toBeUndefined();
  });

  it('꼬리에 탄 종류·바람이 넘어간다', () => {
    const seen: unknown[][] = [];
    const spy = { ...vectorSkin, drawTrail: (_c: unknown, _t: number[], ...rest: unknown[]) => void seen.push(rest) };
    const r = hitReport({ shell: 'CHESTNUT', stages: [hitStage({ shots: [hitShot({ ticks: 8 })] })] });
    const t = (timeline(r).stages[0].start + 4) * TICK_MS;
    const frame = frameAt(r, before, t);
    const scene: Scene = {
      terrain: null,
      units: frame.units,
      angles: {},
      turnSeat: 0,
      mySeat: 0,
      cloudLevel: 340,
      names: {},
      aim: null,
      ghost: null,
      frame,
      palette: vectorSkin.fallbackPalette,
      time: 500,
      wind: -7,
    };
    drawScene(fakeCtx(new Map()), 1, scene, 1.2, spy);
    expect(seen).toEqual([['CHESTNUT', -7, 500]]);
  });

  it('탄 다섯 종 — 재생 + 꼬리 전 구간을 그리고 save/restore 가 맞으며, 한 장면의 그리기 호출이 상한 안이다', () => {
    const mask = maskFromRuns(flatRuns(300));
    for (const shell of ['PEA', 'PUMPKIN', 'VINE', 'CHESTNUT', 'SPORE'] as const) {
      const r = koReport(shell);
      if (shell === 'VINE') r.stages[0].shots[0].crater = { x: 500, y: 294, r: 14, fill: true };
      if (shell === 'SPORE') {
        r.stages[0].shots = [hitShot({ end: 'SPLIT', crater: null, ticks: 4 }), hitShot({ startTick: 4, ticks: 6 }), hitShot({ startTick: 4, ticks: 8 })];
      }
      const script = poseScript(r);
      const events = fxScript(r, before);
      const skin = clay();
      const end = replayLengthMs(r);
      let worst = 0;
      const total = new Map<string, number>();
      for (let t = 0; t <= end + CLAY_FX_TAIL_MS; t += 33) {
        const calls = new Map<string, number>();
        const ctx = fakeCtx(calls);
        const frame = frameAt(r, before, t);
        const poses = frame.units.map((u) => poseAt(u, t < end ? script : null, t < end ? t : null, 0, 1000 + t, fresh()));
        const scene: Scene = {
          terrain: null,
          units: frame.units,
          angles: { 0: 40, 1: 150 },
          turnSeat: 0,
          mySeat: 0,
          cloudLevel: 340,
          names: { 0: '나', 1: '상대' },
          aim: null,
          ghost: null,
          frame: t < end ? frame : null,
          palette: { ...skin.fallbackPalette, night: shell === 'SPORE' },
          time: 1000 + t,
          poses,
          wind: shell === 'PEA' ? 0 : shell === 'VINE' ? -10 : 10,
          teams: shell === 'CHESTNUT',
          fx: { events, nowMs: t },
          mask,
          celebrate: t > end ? { seats: [0], elapsedMs: (t - end) * 3 } : null,
        };
        drawScene(ctx, 1, scene, 0.586, skin);
        expect(calls.get('save')).toBe(calls.get('restore'));
        let n = 0;
        for (const k of ['arc', 'ellipse', 'lineTo', 'fill', 'stroke', 'drawImage']) n += calls.get(k) ?? 0;
        worst = Math.max(worst, n);
        for (const [k, v] of calls) total.set(k, (total.get(k) ?? 0) + v);
      }
      expect(total.get('strokeText')).toBeGreaterThan(0); // 피해 숫자
      // 쓰러진 포대를 꼬리에서 이어 그렸다(시트 그림) — 재생이 끝난 뒤에도 drawImage 가 좌석 0 한 대 몫보다 많다
      expect(total.get('drawImage')).toBeGreaterThan(0);
      // 입자 상한 — 축포·폭발·파편·꽃가루가 한꺼번에 겹쳐도 한 장면의 도형 호출은 수천을 넘지 않는다
      expect(worst).toBeLessThan(4000);
    }
    expect(CLAY_FX_CAPS.debris).toBeLessThanOrEqual(DEBRIS_MAX);
    expect(CLAY_FX_CAPS.motes).toBeLessThanOrEqual(30);
  });
});
