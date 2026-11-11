/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다(artillerySkin.test 와 같은 패턴).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { drawScene, type Scene } from './draw';
import { FX_TAIL_MS, fxEndMs, fxScript, shakeAt } from './effects';
import { frameAt, replayLengthMs, TICK_MS, timeline } from './replay';
import { hitReport, hitStage } from './testFixtures';
import { poseAt, poseScript, UNIT_POSES, type PoseState } from './unitPose';
import { parseAtlas } from './skins/atlas';
import { CLAY_UNIT_SCALE, createClaySkin } from './skins/claySkin';
import { vectorSkin } from './skins/vectorSkin';
import { ACTIVE_SKIN, SKIN_DIRS } from './skins';

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

const before = [
  { seat: 0, x: 100, footY: 299, hp: 100, alive: true },
  { seat: 1, x: 500, footY: 299, hp: 100, alive: true },
];
const fresh = (): PoseState => ({ pose: 'idle', elapsedMs: 0 });

describe('클레이 시트 (D-252)', () => {
  it('기본 스킨은 clay 이고 시트는 150KB 를 넘지 않는다', () => {
    expect(ACTIVE_SKIN).toBe('clay');
    expect(bytes.length).toBeLessThanOrEqual(150 * 1024);
  });

  it('형식 v1 그대로 — 포대 3종 × 상태 5개·포신·씨앗 5종·폭발·둔덕 + clay 전용 묶음(잎·처진 포신·번쩍)', () => {
    const a = atlas();
    for (const kind of ['SPROUT', 'ACORN', 'DANDELION']) {
      for (const pose of UNIT_POSES) {
        const anim = a.anims.get(`unit.${kind}.${pose}`)!;
        expect(anim.tint).not.toBeNull();
        expect(anim.mount).not.toBeNull();
      }
      expect(a.anims.get(`unit.${kind}.idle`)!.frames).toHaveLength(2); // 뜬 눈·깜빡
      expect(a.anims.get(`unit.${kind}.down`)!.barrel).toBe(false);
      expect(a.anims.get(`leaves.${kind}`)!.frames).toHaveLength(2); // 싱싱·시듦
      expect(a.anims.has(`barrel.${kind}`)).toBe(true);
      expect(a.anims.has(`droop.${kind}`)).toBe(true);
    }
    for (const shell of ['PEA', 'PUMPKIN', 'VINE', 'CHESTNUT', 'SPORE']) expect(a.anims.has(`seed.${shell}`)).toBe(true);
    expect(a.anims.has('flash')).toBe(true);
    expect(a.anims.get('explosion')!.radius).toBeGreaterThan(0);
    expect(a.anims.get('mound')!.radius).toBeGreaterThan(0);
  });
});

describe('대본 연출 사건 (D-252)', () => {
  it('직격 대본 — 포구 → 착탄 → 피해 숫자 순서, 시각은 모두 대본 안', () => {
    const r = hitReport();
    const tl = timeline(r);
    const ev = fxScript(r, before);
    expect(ev.map((e) => e.kind)).toEqual(['muzzle', 'impact', 'damage']);
    expect(ev[0].at).toBeCloseTo(tl.stages[0].start * TICK_MS);
    expect(ev[0].angle).toBe(45);
    expect(ev[1]).toMatchObject({ r: 14, fill: false });
    expect(ev[2]).toMatchObject({ amount: 30, seat: 1, x: 500.5 });
    for (const e of ev) expect(e.at).toBeLessThanOrEqual(replayLengthMs(r));
    expect(fxEndMs(ev)).toBeLessThanOrEqual(replayLengthMs(r) + FX_TAIL_MS);
    expect(fxEndMs([])).toBe(0);
  });

  it('회복·낙하 피해·구름 풍덩·시듦', () => {
    const r = hitReport({
      heal: { amount: 25, hpAfter: 100 },
      stages: [
        hitStage({
          damages: [{ seat: 1, amount: 100, hpAfter: 0, direct: true }],
          falls: [{ seat: 0, x: 102, fromY: 299, toY: 340, damage: 0, hpAfter: 0, intoCloud: true }],
        }),
      ],
      eliminated: [
        { seat: 1, cause: 'KNOCKED_OUT' },
        { seat: 0, cause: 'CLOUD_SEA' },
      ],
    });
    const kinds = fxScript(r, before).map((e) => e.kind);
    expect(kinds).toEqual(expect.arrayContaining(['heal', 'splash', 'poof', 'damage']));
    const falling = hitReport({
      stages: [hitStage({ damages: [], falls: [{ seat: 1, x: 500, fromY: 299, toY: 330, damage: 12, hpAfter: 88, intoCloud: false }] })],
    });
    expect(fxScript(falling, before).find((e) => e.kind === 'damage')).toMatchObject({ amount: 12, y: 325 });
  });

  it('화면 흔들림 — 착탄 직후에만, 금방 잦아들고 결정적이다', () => {
    const ev = fxScript(hitReport(), before);
    const at = ev.find((e) => e.kind === 'impact')!.at;
    const a = { x: 0, y: 0 };
    const b = { x: 0, y: 0 };
    shakeAt(ev, at - 1, 3, a);
    expect(a).toEqual({ x: 0, y: 0 });
    shakeAt(ev, at + 30, 3, a);
    shakeAt(ev, at + 30, 3, b);
    expect(Math.abs(a.x) + Math.abs(a.y)).toBeGreaterThan(0);
    expect(a).toEqual(b);
    expect(Math.hypot(a.x, a.y)).toBeLessThanOrEqual(3 * 1.8 * 1.3);
    shakeAt(ev, at + 1000, 3, a);
    expect(a).toEqual({ x: 0, y: 0 });
  });
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

describe('클레이 스킨으로 장면 그리기 (D-252)', () => {
  const sheet = { image: {} as CanvasImageSource, width: size.w, height: size.h };
  const clay = () => createClaySkin('clay', atlas(), sheet, () => null);

  it('선택 계약 — 포대 1.25배·매끄러운 확대·흔들림·연출, 도형 스킨에는 없다', () => {
    const skin = clay();
    expect(skin.unitScale).toBe(CLAY_UNIT_SCALE);
    expect(CLAY_UNIT_SCALE).toBe(1.25);
    expect(skin.smooth).toBe(true);
    expect(skin.shakeCells).toBeGreaterThan(0);
    expect(skin.drawEffects && skin.drawCelebration && skin.drawWind && skin.terrainCanvas).toBeTruthy();
    expect(vectorSkin.unitScale ?? vectorSkin.shakeCells ?? vectorSkin.drawEffects ?? vectorSkin.drawWind).toBeUndefined();
    // 캔버스를 못 만들면(테스트·옛 환경) 흙은 예전 방식으로 대체된다
    expect(skin.terrainCanvas!(new Uint8Array(640 * 360))).toBeNull();
  });

  it('재생 전 구간 + 꼬리 + 축포를 그린다 — 팀전·바람·움직임 줄이기(시계 0)까지, save 와 restore 가 맞는다', () => {
    const r = hitReport({
      stages: [hitStage({ damages: [{ seat: 1, amount: 100, hpAfter: 0, direct: true }] })],
      eliminated: [{ seat: 1, cause: 'KNOCKED_OUT' }],
    });
    const script = poseScript(r);
    const events = fxScript(r, before);
    const skin = clay();
    const calls = new Map<string, number>();
    const ctx = fakeCtx(calls);
    const end = replayLengthMs(r);
    for (let t = 0; t <= end + FX_TAIL_MS; t += 40) {
      const frame = frameAt(r, before, t);
      const poses = frame.units.map((u) => poseAt(u, t < end ? script : null, t < end ? t : null, 0, t, fresh()));
      const scene: Scene = {
        terrain: null,
        units: frame.units.map((u, i) => ({ ...u, kind: (['SPROUT', 'ACORN', 'DANDELION'] as const)[i % 3] })),
        angles: { 0: 40, 1: 150 },
        turnSeat: 0,
        mySeat: 0,
        cloudLevel: 340,
        names: { 0: '나', 1: '상대' },
        aim: t === 0 ? { seat: 0, angle: 40, power: 60 } : null,
        ghost: null,
        frame: t < end ? frame : null,
        palette: { ...skin.fallbackPalette, night: t % 80 === 0 },
        time: t % 120 === 0 ? 0 : 1000 + t,
        poses,
        wind: t % 80 === 0 ? -6 : 4,
        teams: true,
        fx: { events, nowMs: t },
        celebrate: t > end ? { seats: [0], elapsedMs: (t - end) * 4 } : null,
      };
      drawScene(ctx, 2, scene, t % 80 === 0 ? 0.5 : 1.4, skin);
    }
    expect(calls.get('drawImage')).toBeGreaterThan(0);
    expect(calls.get('strokeText')).toBeGreaterThan(0); // 피해 숫자
    expect(calls.get('save')).toBe(calls.get('restore'));
  });
});
