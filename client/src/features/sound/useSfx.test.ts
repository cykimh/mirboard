import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  MADE_SFX_MAX_SEC,
  ROLL_SFX_DICE,
  ROLL_SFX_HITS_SEC,
  ROLL_SFX_PEAK,
  ROLL_SFX_SEC,
  ROLL_SFX_STAGGER_SEC,
  YACHT_SFX_CHORD_SEC,
  YACHT_SFX_FIRST_NOTE_SEC,
  YACHT_SFX_STEP_SEC,
} from './useSfx';
import { YACHT_PARTY_MS } from '@/features/yacht/YachtTable';
import { ROLL_ANIMATION_MS, ROLL_DIE_MS, ROLL_STAGGER_MS } from '@/features/yacht/DiceTray';

/** D-207 — 얏추 요트 칸 효과음(Web Audio 합성). 가짜 AudioContext 로 무엇을 몇 개 울렸는지만 센다. */
const MUTE_KEY = 'mirboard.sfxMuted';

interface Tone {
  type: string;
  freq: number;
  start: number;
  stop: number;
}

let tones: Tone[] = [];
let noises = 0;
let contexts = 0;
/** 잡음 소스(주사위 소리) — 되풀이 여부·멈춘 시각·버퍼 길이(샘플). */
interface NoiseSource {
  loop: boolean;
  buffer: { length: number } | null;
  started: number | null;
  stopped: number | null;
}
let sources: NoiseSource[] = [];
/** 세기 노드에 처음 건 값(소리 크기). */
let gainPeaks: number[] = [];

class FakeParam {
  value = 0;
  onFirst: ((v: number) => void) | null = null;
  setValueAtTime(v: number) {
    this.value = v;
    this.onFirst?.(v);
    this.onFirst = null;
  }
  linearRampToValueAtTime() {}
  exponentialRampToValueAtTime() {}
}

class FakeNode {
  connect<T>(next: T): T {
    return next;
  }
}

class FakeOscillator extends FakeNode {
  frequency = new FakeParam();
  private tone: Tone = { type: 'sine', freq: 0, start: 0, stop: 0 };
  constructor() {
    super();
    tones.push(this.tone);
  }
  set type(v: string) {
    this.tone.type = v;
  }
  start(at: number) {
    this.tone.start = at;
    this.tone.freq = this.frequency.value;
  }
  stop(at: number) {
    this.tone.stop = at;
  }
}

class FakeAudioContext {
  state = 'running';
  currentTime = 10;
  sampleRate = 8000;
  destination = new FakeNode();
  constructor() {
    contexts += 1;
  }
  resume() {
    return Promise.resolve();
  }
  createOscillator() {
    return new FakeOscillator();
  }
  createGain() {
    const gain = new FakeParam();
    gain.onFirst = (v) => gainPeaks.push(v);
    return Object.assign(new FakeNode(), { gain });
  }
  createBiquadFilter() {
    return Object.assign(new FakeNode(), { type: '', frequency: new FakeParam(), Q: new FakeParam() });
  }
  createDynamicsCompressor() {
    return Object.assign(new FakeNode(), {
      threshold: new FakeParam(),
      knee: new FakeParam(),
      ratio: new FakeParam(),
      attack: new FakeParam(),
      release: new FakeParam(),
    });
  }
  createBuffer(_channels: number, length: number) {
    const data = new Float32Array(length);
    return { length, getChannelData: () => data };
  }
  createBufferSource() {
    noises += 1;
    const record: NoiseSource = { loop: false, buffer: null, started: null, stopped: null };
    sources.push(record);
    const node = Object.assign(new FakeNode(), {
      start: (at = 0) => {
        record.started = at;
      },
      stop: (at = 0) => {
        record.stopped = at;
      },
    });
    Object.defineProperty(node, 'buffer', { set: (b) => (record.buffer = b), get: () => record.buffer });
    Object.defineProperty(node, 'loop', { set: (v) => (record.loop = v), get: () => record.loop });
    return node;
  }
}

async function freshHook() {
  // AudioContext 는 모듈 싱글톤이라 테스트마다 모듈을 새로 읽는다.
  vi.resetModules();
  const mod = await import('./useSfx');
  return renderHook(() => mod.useSfx());
}

describe('useSfx.playYacht (D-207)', () => {
  beforeEach(() => {
    tones = [];
    noises = 0;
    contexts = 0;
    window.localStorage.removeItem(MUTE_KEY);
    vi.stubGlobal('AudioContext', FakeAudioContext);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    window.localStorage.removeItem(MUTE_KEY);
  });

  it('내 요트는 팡파르 — 쿵·주사위 잡음, 박자에 맞춘 다섯 음 상승, 화음과 반짝임', async () => {
    const { result } = await freshHook();
    act(() => result.current.playYacht(true));
    expect(noises).toBe(1);
    const rise = tones.filter((t) => t.type === 'triangle' && t.start < 10 + YACHT_SFX_CHORD_SEC - 0.001);
    expect(rise).toHaveLength(5);
    rise.forEach((t, i) => expect(t.start).toBeCloseTo(10 + YACHT_SFX_FIRST_NOTE_SEC + i * YACHT_SFX_STEP_SEC, 5));
    // 음이 차례로 올라간다.
    expect(rise.map((t) => t.freq)).toEqual([...rise.map((t) => t.freq)].sort((a, b) => a - b));
    const chord = tones.filter((t) => t.type === 'triangle' && Math.abs(t.start - (10 + YACHT_SFX_CHORD_SEC)) < 0.001);
    expect(chord.length).toBeGreaterThanOrEqual(4);
    // 다섯 번째 음이 울린 뒤에 화음이 터진다.
    expect(YACHT_SFX_FIRST_NOTE_SEC + 4 * YACHT_SFX_STEP_SEC).toBeLessThan(YACHT_SFX_CHORD_SEC);
  });

  it('소리는 축하가 떠 있는 시간(YACHT_PARTY_MS) 안에 끝난다', async () => {
    const { result } = await freshHook();
    act(() => result.current.playYacht(true));
    const end = Math.max(...tones.map((t) => t.stop)) - 10;
    expect(end).toBeGreaterThan(1);
    expect(end * 1000).toBeLessThan(YACHT_PARTY_MS);
  });

  it('남의 요트는 작은 두 음만 — 쿵·잡음·화음이 없다', async () => {
    const { result } = await freshHook();
    act(() => result.current.playYacht(false));
    expect(tones).toHaveLength(2);
    expect(tones.every((t) => t.type === 'sine')).toBe(true);
    expect(noises).toBe(0);
    expect(Math.max(...tones.map((t) => t.stop)) - 10).toBeLessThan(0.6);
  });

  it('음소거면 내 것도 남의 것도 울리지 않는다(오디오 컨텍스트도 만들지 않는다)', async () => {
    window.localStorage.setItem(MUTE_KEY, '1');
    const { result } = await freshHook();
    expect(result.current.muted).toBe(true);
    act(() => result.current.playYacht(true));
    act(() => result.current.playYacht(false));
    expect(tones).toHaveLength(0);
    expect(noises).toBe(0);
    expect(contexts).toBe(0);
  });

  it('소리를 다시 켜면 울린다', async () => {
    window.localStorage.setItem(MUTE_KEY, '1');
    const { result } = await freshHook();
    act(() => result.current.toggleMute());
    act(() => result.current.playYacht(false));
    expect(tones).toHaveLength(2);
    expect(window.localStorage.getItem(MUTE_KEY)).toBe('0');
  });

  it('Web Audio 가 없는 환경에서는 조용히 넘어간다', async () => {
    vi.stubGlobal('AudioContext', undefined);
    const { result } = await freshHook();
    expect(() => act(() => result.current.playYacht(true))).not.toThrow();
    expect(tones).toHaveLength(0);
  });
});

/** D-208 — 얏추 굴림이 족보로 멈춘 순간의 알림음(세 단계). */
describe('useSfx.playMade (D-208)', () => {
  beforeEach(() => {
    tones = [];
    noises = 0;
    contexts = 0;
    window.localStorage.removeItem(MUTE_KEY);
    vi.stubGlobal('AudioContext', FakeAudioContext);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    window.localStorage.removeItem(MUTE_KEY);
  });

  it('세기가 오를수록 음이 많고 길다 — 약 2음 · 중 3음 · 강 4음 + 반짝임, 모두 MADE_SFX_MAX_SEC 안', async () => {
    const { result } = await freshHook();
    const measure = (tier: 'light' | 'mid' | 'big') => {
      tones = [];
      act(() => result.current.playMade(tier));
      return { count: tones.length, end: Math.max(...tones.map((t) => t.stop)) - 10, freqs: tones.map((t) => t.freq) };
    };
    const light = measure('light');
    const mid = measure('mid');
    const big = measure('big');
    expect([light.count, mid.count, big.count]).toEqual([2, 3, 5]);
    expect(light.end).toBeLessThan(mid.end);
    expect(mid.end).toBeLessThan(big.end);
    expect(big.end).toBeLessThanOrEqual(MADE_SFX_MAX_SEC);
    // 음이 차례로 올라간다.
    for (const m of [light, mid, big]) expect(m.freqs).toEqual([...m.freqs].sort((a, b) => a - b));
  });

  it('요트로 멈춘 종소리는 기록 팡파르와 다르다 — 쿵·잡음·화음이 없고 훨씬 짧다', async () => {
    const { result } = await freshHook();
    act(() => result.current.playMade('big'));
    expect(noises).toBe(0);
    expect(tones.every((t) => t.freq > 500)).toBe(true);
    const madeEnd = Math.max(...tones.map((t) => t.stop)) - 10;
    tones = [];
    act(() => result.current.playYacht(true));
    const fanfareEnd = Math.max(...tones.map((t) => t.stop)) - 10;
    expect(madeEnd).toBeLessThan(fanfareEnd / 2);
  });

  it('음소거면 울리지 않고(오디오 컨텍스트도 만들지 않는다), 다시 켜면 울린다', async () => {
    window.localStorage.setItem(MUTE_KEY, '1');
    const { result } = await freshHook();
    for (const tier of ['light', 'mid', 'big'] as const) act(() => result.current.playMade(tier));
    expect(tones).toHaveLength(0);
    expect(contexts).toBe(0);
    act(() => result.current.toggleMute());
    act(() => result.current.playMade('light'));
    expect(tones).toHaveLength(2);
  });

  it('Web Audio 가 없는 환경에서는 조용히 넘어간다', async () => {
    vi.stubGlobal('AudioContext', undefined);
    const { result } = await freshHook();
    expect(() => act(() => result.current.playMade('big'))).not.toThrow();
    expect(tones).toHaveLength(0);
  });
});

/** D-264 — 얏추 굴림 소리·컵 흔드는 소리(잡음 토막 합성). */
describe('useSfx.playRoll · startShake (D-264)', () => {
  beforeEach(() => {
    tones = [];
    noises = 0;
    contexts = 0;
    sources = [];
    gainPeaks = [];
    window.localStorage.removeItem(MUTE_KEY);
    vi.stubGlobal('AudioContext', FakeAudioContext);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    window.localStorage.removeItem(MUTE_KEY);
  });

  it('굴림 소리의 박자는 굴림 연출과 같다 — 착지 42%·72%·94%, 주사위마다 45ms, 연출 길이 안에 마지막 착지', () => {
    expect(ROLL_SFX_HITS_SEC.map((s) => Math.round(s * 1000))).toEqual([0.42, 0.72, 0.94].map((k) => Math.round(k * ROLL_DIE_MS)));
    expect(ROLL_SFX_STAGGER_SEC * 1000).toBe(ROLL_STAGGER_MS);
    const lastHit = ROLL_SFX_HITS_SEC[2] + (ROLL_SFX_DICE - 1) * ROLL_SFX_STAGGER_SEC;
    expect(lastHit * 1000).toBeLessThanOrEqual(ROLL_ANIMATION_MS);
    expect(ROLL_SFX_SEC).toBeGreaterThan(lastHit);
    // 족보 알림음(D-208)은 연출이 끝난 뒤에 울리므로 굴림 소리가 그 전에 끝나 간다.
    expect(ROLL_SFX_SEC * 1000).toBeLessThan(ROLL_ANIMATION_MS + 150);
  });

  it('내 굴림 — 잡음 한 줄을 지금 바로 한 번 울린다(음은 없다). 착지 자리마다 소리가 적혀 있다', async () => {
    const { result } = await freshHook();
    act(() => result.current.playRoll(true));
    expect(noises).toBe(1);
    expect(tones).toHaveLength(0);
    const src = sources[0];
    expect(src.loop).toBe(false);
    expect(src.started).toBe(10);
    expect(src.buffer?.length).toBe(Math.floor(8000 * ROLL_SFX_SEC));
    expect(gainPeaks).toEqual([ROLL_SFX_PEAK.mine]);
    const data = (src.buffer as unknown as { getChannelData: () => Float32Array }).getChannelData();
    const energy = (fromSec: number, toSec: number) => {
      let sum = 0;
      for (let i = Math.floor(fromSec * 8000); i < Math.floor(toSec * 8000); i++) sum += Math.abs(data[i]);
      return sum;
    };
    for (let d = 0; d < ROLL_SFX_DICE; d++) {
      const at = ROLL_SFX_HITS_SEC[0] + d * ROLL_SFX_STAGGER_SEC;
      expect(energy(at, at + 0.02), `주사위 ${d} 첫 착지`).toBeGreaterThan(0);
    }
    // 쏟아지는 달그락과 첫 착지 사이(주사위가 떠 있는 동안)는 조용하다.
    expect(energy(0.2, 0.209)).toBe(0);
  });

  it('남의 굴림은 훨씬 작다', async () => {
    const { result } = await freshHook();
    act(() => result.current.playRoll(false));
    expect(noises).toBe(1);
    expect(gainPeaks).toEqual([ROLL_SFX_PEAK.other]);
    expect(ROLL_SFX_PEAK.other).toBeLessThan(ROLL_SFX_PEAK.mine / 3);
  });

  it('흔드는 소리는 되풀이하다가, 멈추면 곧 끝난다(두 번 멈춰도 한 번만)', async () => {
    const { result } = await freshHook();
    let stop = () => {};
    act(() => {
      stop = result.current.startShake();
    });
    expect(noises).toBe(1);
    expect(sources[0].loop).toBe(true);
    expect(sources[0].started).toBe(10);
    expect(sources[0].stopped).toBeNull();
    stop();
    const stoppedAt = sources[0].stopped;
    expect(stoppedAt).not.toBeNull();
    expect((stoppedAt ?? 0) - 10).toBeLessThan(0.2);
    sources[0].stopped = null;
    stop();
    expect(sources[0].stopped).toBeNull();
  });

  it('음소거면 굴림도 흔들기도 울리지 않는다(오디오 컨텍스트도 만들지 않는다), 다시 켜면 울린다', async () => {
    window.localStorage.setItem(MUTE_KEY, '1');
    const { result } = await freshHook();
    act(() => result.current.playRoll(true));
    act(() => result.current.playRoll(false));
    let stop = () => {};
    act(() => {
      stop = result.current.startShake();
    });
    expect(() => stop()).not.toThrow();
    expect(noises).toBe(0);
    expect(contexts).toBe(0);
    act(() => result.current.toggleMute());
    act(() => result.current.playRoll(true));
    expect(noises).toBe(1);
  });

  it('Web Audio 가 없는 환경에서는 조용히 넘어간다', async () => {
    vi.stubGlobal('AudioContext', undefined);
    const { result } = await freshHook();
    expect(() => act(() => result.current.playRoll(true))).not.toThrow();
    let stop = () => {};
    expect(() =>
      act(() => {
        stop = result.current.startShake();
      }),
    ).not.toThrow();
    expect(() => stop()).not.toThrow();
    expect(noises).toBe(0);
  });
});
