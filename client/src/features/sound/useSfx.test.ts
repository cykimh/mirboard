import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { YACHT_SFX_CHORD_SEC, YACHT_SFX_FIRST_NOTE_SEC, YACHT_SFX_STEP_SEC } from './useSfx';
import { YACHT_PARTY_MS } from '@/features/yacht/YachtTable';

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

class FakeParam {
  value = 0;
  setValueAtTime(v: number) {
    this.value = v;
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
    return Object.assign(new FakeNode(), { gain: new FakeParam() });
  }
  createBiquadFilter() {
    return Object.assign(new FakeNode(), { type: '', frequency: new FakeParam() });
  }
  createBuffer(_channels: number, length: number) {
    return { getChannelData: () => new Float32Array(length) };
  }
  createBufferSource() {
    noises += 1;
    return Object.assign(new FakeNode(), { buffer: null, start: () => {} });
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
