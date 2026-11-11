/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다(ogImage.test 와 같은 패턴).
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { FIELD_W } from '@/types/artillery';
import { drawScene, type Scene } from './draw';
import { frameAt, replayLengthMs, TICK_MS, timeline } from './replay';
import { hitReport, hitStage } from './testFixtures';
import { HIT_TICKS, poseAt, poseScript, RECOIL_TICKS, UNIT_POSES, type PoseState } from './unitPose';
import {
  animFrameIndex,
  AtlasError,
  explosionAnim,
  parseAtlas,
  progressFrameIndex,
  seedAnim,
  unitAnim,
  type SpriteAtlas,
} from './skins/atlas';
import { createSpriteSkin } from './skins/spriteSkin';
import { vectorSkin } from './skins/vectorSkin';
import { SkinSource, SPRITE_SKIN_DIR } from './skins';
import type { ArtillerySkin } from './skins/types';

/** 이 파일 기준 상대 경로의 원문(리터럴 `new URL('…', import.meta.url)` 은 Vite 가 자산 주소로 바꾼다 — 인자로 받는다). */
function readText(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}
function readBytes(relative: string): Uint8Array {
  const read = readFileSync as unknown as (p: URL) => Uint8Array;
  return read(new URL(relative, import.meta.url));
}

/** 지금 고른 시트 폴더(`skins/index.ts`) — 새 시트로 바꾸면 이 검사도 따라간다. */
const SHEET_DIR = `../../../public/artillery/skins/${SPRITE_SKIN_DIR}/`;

const before = [
  { seat: 0, x: 100, footY: 299, hp: 100, alive: true },
  { seat: 1, x: 500, footY: 299, hp: 100, alive: true },
];
const fresh = (): PoseState => ({ pose: 'idle', elapsedMs: 0 });

describe('포대 상태 판정 (스킨 밖 순수 함수, D-227)', () => {
  it('재생 밖 — 차례 주인은 조준, 나머지는 대기, 체력 0 은 쓰러짐', () => {
    expect(poseAt(before[0], null, null, 0, 1000, fresh()).pose).toBe('aim');
    expect(poseAt(before[1], null, null, 0, 1000, fresh()).pose).toBe('idle');
    expect(poseAt({ ...before[1], hp: 0 }, null, null, 0, 1000, fresh()).pose).toBe('down');
    expect(poseAt({ ...before[1], alive: false }, null, null, 1, 1000, fresh()).pose).toBe('down');
  });

  it('직격 대본 — 걷는 동안 조준 → 첫 씨앗에 반동 → 맞은 포대는 피해 순간부터 피격 → 끝나면 재생 밖 규칙', () => {
    const r = hitReport();
    const tl = timeline(r);
    const s = poseScript(r);
    const at = (seat: number, ticks: number) => {
      const f = frameAt(r, before, ticks * TICK_MS);
      return poseAt(f.units[seat], s, ticks * TICK_MS, 1, 0, fresh());
    };
    expect(at(0, 2).pose).toBe('aim');
    expect(at(0, tl.stages[0].start).pose).toBe('recoil');
    expect(at(0, tl.stages[0].start + RECOIL_TICKS - 1).pose).toBe('recoil');
    expect(at(0, tl.stages[0].start + RECOIL_TICKS).pose).toBe('idle');
    expect(at(1, tl.stages[0].flightEnd - 1).pose).toBe('idle');
    const hit = at(1, tl.stages[0].flightEnd + 3);
    expect(hit.pose).toBe('hit');
    expect(hit.elapsedMs).toBeCloseTo(3 * TICK_MS);
    expect(at(1, tl.stages[0].flightEnd + HIT_TICKS).pose).toBe('idle');
    // 대본 끝 이후 — 재생 밖과 같다(재생 길이를 늘리지 않는다)
    expect(poseAt(before[1], s, replayLengthMs(r) + 1, 1, 0, fresh()).pose).toBe('aim');
  });

  it('창은 모두 대본 타임라인 안 — 피격 창이 대본 끝을 넘지 않는다', () => {
    const r = hitReport({ stages: [hitStage({ falls: [] })] });
    const s = poseScript(r);
    for (const w of [...s.hits, ...s.recoils]) expect(w.end).toBeLessThanOrEqual(s.end);
    expect(s.end).toBe(timeline(r).end);
  });

  it('치명타 — 체력 0 이 된 순간부터 쓰러짐, 낙하 피해도 피격', () => {
    const lethal = hitReport({
      stages: [hitStage({ damages: [{ seat: 1, amount: 100, hpAfter: 0, direct: true }], falls: [] })],
      eliminated: [{ seat: 1, cause: 'KNOCKED_OUT' }],
    });
    const tl = timeline(lethal);
    const s = poseScript(lethal);
    const f = frameAt(lethal, before, (tl.stages[0].flightEnd + 2) * TICK_MS);
    const down = poseAt(f.units[1], s, (tl.stages[0].flightEnd + 2) * TICK_MS, 0, 0, fresh());
    expect(down.pose).toBe('down');
    expect(down.elapsedMs).toBeCloseTo(2 * TICK_MS);

    const falling = hitReport({
      stages: [hitStage({ damages: [], falls: [{ seat: 1, x: 500, fromY: 299, toY: 330, damage: 12, hpAfter: 88, intoCloud: false }] })],
    });
    const fs = poseScript(falling);
    expect(fs.hits).toEqual([expect.objectContaining({ seat: 1, start: timeline(falling).stages[0].explosionEnd + 31 })]);
  });

  it('겹씨앗 — 단계마다 반동이 한 번씩', () => {
    const r = hitReport({ item: 'TWIN_SEED', stages: [hitStage(), hitStage()] });
    expect(poseScript(r).recoils).toHaveLength(2);
  });
});

/** 최소 시트 정의 — 필수 묶음만. */
const minimal = () => ({
  format: 'mirboard-artillery-skin',
  version: 1,
  image: 'atlas.png',
  pxPerCell: 4,
  anims: {
    'unit.SPROUT.idle': { frames: [[0, 0, 8, 8], [8, 0, 8, 8]], frameMs: 100, anchor: [4, 8], mount: [4, 2] },
    'barrel.SPROUT': { frames: [[0, 8, 8, 4]], anchor: [1, 2] },
    'seed.PEA': { frames: [[8, 8, 4, 4]] },
    explosion: { frames: [[0, 12, 8, 8], [8, 12, 8, 8], [16, 12, 8, 8]], radius: 3, loop: false },
  },
});

describe('시트 정의 읽기 (D-227)', () => {
  it('필수 묶음과 기본값', () => {
    const a = parseAtlas(minimal(), { w: 32, h: 32 });
    expect(a.pxPerCell).toBe(4);
    expect(a.labelLift).toBe(15);
    expect(a.recoilCells).toBe(2);
    expect(a.anims.get('seed.PEA')!.anchor).toEqual([2, 2]);
    expect(a.anims.get('unit.SPROUT.idle')!.barrel).toBe(true);
  });

  it('잘못된 정의는 AtlasError — 형식·필수 묶음·시트 밖 프레임·tint 수', () => {
    expect(() => parseAtlas({ ...minimal(), format: 'x' })).toThrow(AtlasError);
    expect(() => parseAtlas({ ...minimal(), version: 2 })).toThrow(AtlasError);
    const noSeed = minimal();
    delete (noSeed.anims as Record<string, unknown>)['seed.PEA'];
    expect(() => parseAtlas(noSeed)).toThrow(/seed.PEA/);
    expect(() => parseAtlas(minimal(), { w: 16, h: 32 })).toThrow(/시트/);
    const badTint = minimal();
    (badTint.anims['unit.SPROUT.idle'] as Record<string, unknown>).tint = [[0, 0, 8, 8]];
    expect(() => parseAtlas(badTint)).toThrow(/tint/);
  });

  it('프레임 고르기 — 반복·멈춤·진행도', () => {
    const a = parseAtlas(minimal());
    const idle = a.anims.get('unit.SPROUT.idle')!;
    expect([0, 99, 100, 250].map((ms) => animFrameIndex(idle, ms))).toEqual([0, 0, 1, 0]);
    const boom = a.anims.get('explosion')!;
    expect(animFrameIndex(boom, 10_000)).toBe(0); // frameMs 0 → 첫 프레임
    expect([0, 0.34, 0.99, 1].map((t) => progressFrameIndex(boom, t))).toEqual([0, 1, 2, 2]);
  });

  it('없는 묶음은 대체 순서로 — 같은 종류 대기 → 새싹포', () => {
    const a = parseAtlas(minimal());
    expect(unitAnim(a, 'ACORN', 'hit')).toBe(a.anims.get('unit.SPROUT.idle'));
    expect(seedAnim(a, 'SPORE')).toBe(a.anims.get('seed.PEA'));
    expect(explosionAnim(a, true)).toBe(a.anims.get('explosion'));
  });

  it('저장소의 시트(SPRITE_SKIN_DIR) — 정의가 맞고 프레임이 PNG 안에, 포대 3종 × 상태 5개·씨앗 5종·둔덕까지 있다', () => {
    const bytes = readBytes(`${SHEET_DIR}atlas.png`);
    expect(Array.from(bytes.slice(1, 4))).toEqual([0x50, 0x4e, 0x47]);
    const w = (bytes[16] << 24) | (bytes[17] << 16) | (bytes[18] << 8) | bytes[19];
    const h = (bytes[20] << 24) | (bytes[21] << 16) | (bytes[22] << 8) | bytes[23];
    const atlas = parseAtlas(JSON.parse(readText(`${SHEET_DIR}atlas.json`)), { w, h });
    for (const kind of ['SPROUT', 'ACORN', 'DANDELION']) {
      for (const pose of UNIT_POSES) expect(atlas.anims.has(`unit.${kind}.${pose}`)).toBe(true);
      expect(atlas.anims.has(`barrel.${kind}`)).toBe(true);
    }
    for (const shell of ['PEA', 'PUMPKIN', 'VINE', 'CHESTNUT', 'SPORE']) expect(atlas.anims.has(`seed.${shell}`)).toBe(true);
    expect(atlas.anims.get('mound')!.radius).toBeGreaterThan(0);
    expect(atlas.anims.get('unit.SPROUT.down')!.barrel).toBe(false);
  });
});

/** 모든 그리기 호출을 받아 넘기는 가짜 Canvas — 부른 메서드 이름을 센다. */
function fakeCtx(calls: Map<string, number>): CanvasRenderingContext2D {
  return new Proxy({} as Record<string, unknown>, {
    get(_t, k) {
      if (typeof k === 'symbol') return undefined;
      return (..._a: unknown[]) => {
        calls.set(k, (calls.get(k) ?? 0) + 1);
        return k === 'createLinearGradient' ? { addColorStop() {} } : undefined;
      };
    },
    set: () => true,
  }) as unknown as CanvasRenderingContext2D;
}

function sceneAt(r: ReturnType<typeof hitReport>, tMs: number, poses?: PoseState[]): Scene {
  const frame = frameAt(r, before, tMs);
  return {
    terrain: null,
    units: frame.units,
    angles: { 0: 40, 1: 150 },
    turnSeat: 1,
    mySeat: 0,
    cloudLevel: 340,
    names: { 0: '나', 1: '상대' },
    aim: null,
    ghost: null,
    frame,
    palette: vectorSkin.fallbackPalette,
    time: tMs,
    poses,
  };
}

describe('스킨으로 장면 그리기 (D-227)', () => {
  const sheet = { image: {} as CanvasImageSource, width: 32, height: 32 };
  const sprite = () => createSpriteSkin('sprite:test', parseAtlas(minimal()), sheet, () => null);

  it('두 스킨 모두 재생 전 구간을 그린다 — 시트 스킨은 포대·씨앗·폭발을 drawImage 로', () => {
    const r = hitReport();
    const s = poseScript(r);
    for (const skin of [vectorSkin, sprite()] as ArtillerySkin[]) {
      const calls = new Map<string, number>();
      const ctx = fakeCtx(calls);
      for (let t = 0; t <= replayLengthMs(r); t += 50) {
        const frame = frameAt(r, before, t);
        const poses = frame.units.map((u) => poseAt(u, s, t, 1, t, fresh()));
        drawScene(ctx, 2, sceneAt(r, t, poses), 0.5, skin);
      }
      if (skin === vectorSkin) expect(calls.get('drawImage') ?? 0).toBe(0);
      else expect(calls.get('drawImage')).toBeGreaterThan(0);
      expect(calls.get('save')).toBe(calls.get('restore'));
    }
  });

  it('시트 스킨 — 흙 색은 정의의 soil, 이름표 높이는 labelLift', () => {
    const atlas: SpriteAtlas = parseAtlas({ ...minimal(), labelLift: 20, soil: { grass: '#010203', grassDark: '#010203', topsoil: '#010203', subsoil: '#010203' } });
    const skin = createSpriteSkin('s', atlas, sheet, () => null);
    const mask = new Uint8Array(FIELD_W * 360);
    mask[5 * FIELD_W + 3] = 1;
    // jsdom 에 ImageData 가 없으면 건너뛴다(그림 함수는 브라우저 전용)
    if (typeof ImageData !== 'undefined') {
      const img = skin.terrainImage(mask);
      const k = (5 * FIELD_W + 3) * 4;
      expect(img.data[k + 3]).toBe(255);
      expect(img.data[k]).toBeLessThan(20);
    }
    const texts: number[] = [];
    const ctx = new Proxy({} as Record<string, unknown>, {
      get: (_t, k) => (k === 'fillText' ? (_s: string, _x: number, y: number) => texts.push(y) : () => undefined),
      set: () => true,
    }) as unknown as CanvasRenderingContext2D;
    const label = { x: 10, footY: 100, text: 'a', hp: 50, maxHp: 100, fontSize: 10, unitScale: 1 };
    skin.drawLabel(ctx, label, vectorSkin.fallbackPalette);
    vectorSkin.drawLabel(ctx, label, vectorSkin.fallbackPalette);
    expect(texts[1] - texts[0]).toBe(5);
  });
});

describe('스킨 고르기 — 시트를 받기 전·실패하면 도형 스킨 (D-227)', () => {
  it('vector 면 받지 않는다', () => {
    const load = vi.fn();
    expect(new SkinSource('vector', load).current()).toBe(vectorSkin);
    expect(load).not.toHaveBeenCalled();
  });

  it('sprite — 받는 동안 도형, 다 받으면 알리고 시트 스킨, 한 번만 받는다', async () => {
    const skin = { ...vectorSkin, id: 'sprite:x' };
    let resolve!: (s: ArtillerySkin) => void;
    const load = vi.fn(() => new Promise<ArtillerySkin>((r) => (resolve = r)));
    const src = new SkinSource('sprite', load);
    const heard = vi.fn();
    src.subscribe(heard);
    expect(src.current()).toBe(vectorSkin);
    expect(src.current()).toBe(vectorSkin);
    resolve(skin);
    await Promise.resolve();
    await Promise.resolve();
    expect(heard).toHaveBeenCalledTimes(1);
    expect(src.current()).toBe(skin);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('sprite — 실패하면 계속 도형 스킨', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const src = new SkinSource('sprite', () => Promise.reject(new Error('404')));
    expect(src.current()).toBe(vectorSkin);
    await new Promise((r) => setTimeout(r, 0));
    expect(src.current()).toBe(vectorSkin);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
