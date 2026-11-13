/**
 * 테스트 전용 가짜 Web Audio (D-267). 무엇을 몇 개 만들었는지만 센다 — `vi.stubGlobal('AudioContext', FakeAudioContext)` 로 꽂는다.
 * AudioContext 는 `useSfx` 모듈의 싱글톤이라, 컨텍스트 생성 수를 세려면 테스트가 모듈을 새로 읽어야 한다.
 */
export interface FakeTone {
  type: string;
  freq: number;
  start: number;
  stop: number;
  peak: number;
}

export const audioLog = {
  tones: [] as FakeTone[],
  /** 잡음 토막마다 시작 시각. */
  noises: [] as number[],
  contexts: 0,
  /** 만든 소리 조각 수(오실레이터 + 잡음). */
  get voices() {
    return this.tones.length + this.noises.length;
  },
  reset() {
    this.tones = [];
    this.noises = [];
    this.contexts = 0;
  },
};

class FakeParam {
  value = 0;
  first: number | null = null;
  peak = 0;
  setValueAtTime(v: number) {
    this.value = v;
    if (this.first === null) this.first = v;
  }
  linearRampToValueAtTime(v: number) {
    this.peak = Math.max(this.peak, v);
  }
  exponentialRampToValueAtTime() {}
}

class FakeNode {
  gain?: FakeParam;
  owner?: FakeTone;
  connect<T>(next: T): T {
    // 오실레이터 → 게인: 그 음의 크기를 기록한다.
    const target = next as unknown as FakeNode;
    if (this.owner && target.gain) this.owner.peak = target.gain.peak;
    return next;
  }
}

class FakeOscillator extends FakeNode {
  frequency = new FakeParam();
  private tone: FakeTone = { type: 'sine', freq: 0, start: 0, stop: 0, peak: 0 };
  constructor() {
    super();
    this.owner = this.tone;
    audioLog.tones.push(this.tone);
  }
  set type(v: string) {
    this.tone.type = v;
  }
  start(at: number) {
    this.tone.start = at;
    this.tone.freq = this.frequency.first ?? this.frequency.value;
  }
  stop(at: number) {
    this.tone.stop = at;
  }
}

export class FakeAudioContext {
  state = 'running';
  currentTime = 10;
  sampleRate = 8000;
  destination = new FakeNode();
  constructor() {
    audioLog.contexts += 1;
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
    return Object.assign(new FakeNode(), { type: '', frequency: new FakeParam(), Q: new FakeParam() });
  }
  /** D-271 — 효과음 출구의 압축기. */
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
    return { getChannelData: () => new Float32Array(length) };
  }
  createBufferSource() {
    return Object.assign(new FakeNode(), {
      buffer: null,
      start: (at: number) => {
        audioLog.noises.push(at);
      },
    });
  }
}
