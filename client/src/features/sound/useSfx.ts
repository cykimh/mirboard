import { useCallback, useEffect, useState } from 'react';

export type SfxKey = 'bomb' | 'straight-flush';

const SFX_URL: Record<SfxKey, string> = {
  bomb: '/sfx/bomb.mp3',
  'straight-flush': '/sfx/straight-flush.mp3',
};

const MUTE_KEY = 'mirboard.sfxMuted';

/**
 * D-207 얏추 — 요트 칸(다섯 개 같은 눈) 축하음의 박자. 게임판 현수막의 주사위 다섯 개가 같은 박자로 놓인다
 * (CSS `yt-fx-dice-five` 의 지연과 같은 값 — 바꾸면 같이 고친다).
 */
export const YACHT_SFX_FIRST_NOTE_SEC = 0.22;
export const YACHT_SFX_STEP_SEC = 0.09;
/** 다섯 음 뒤 팡파르 화음이 터지는 때(두 번째 섬광·충격 고리와 같다). */
export const YACHT_SFX_CHORD_SEC = 0.72;

/** D-208 얏추 — 굴림이 족보로 멈춘 순간의 짧은 알림음이 끝나는 상한(초). 기록 축하(팡파르)보다 훨씬 짧다. */
export const MADE_SFX_MAX_SEC = 0.6;
export type MadeSfxTier = 'light' | 'mid' | 'big';

// 차례 알림용 합성음(#9). mp3 에셋이 없어 Web Audio 로 짧은 2음을 생성한다.
// AudioContext 는 싱글톤(사용자 제스처 이후 resume).
let audioCtx: AudioContext | null = null;
/** D-267 — 다른 소리 모듈(`tableSfx`)이 같은 컨텍스트를 쓴다. */
export function getAudioCtx(): AudioContext | null {
  try {
    if (typeof window === 'undefined') return null;
    if (!audioCtx) {
      const AC =
        window.AudioContext ||
        (window as unknown as { webkitAudioContext?: typeof AudioContext })
          .webkitAudioContext;
      if (!AC) return null;
      audioCtx = new AC();
    }
    return audioCtx;
  } catch {
    return null;
  }
}

/** D-267 — 훅 밖(게임 sink)에서 묻는 음소거 여부. 훅의 토글이 같은 키에 쓴다. */
export function isSfxMuted(): boolean {
  try {
    return typeof window !== 'undefined' && window.localStorage.getItem(MUTE_KEY) === '1';
  } catch {
    return false;
  }
}

function blip(
  ctx: AudioContext,
  freq: number,
  start: number,
  dur: number,
  peak = 0.18,
  type: OscillatorType = 'sine',
) {
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = type;
  osc.frequency.value = freq;
  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.linearRampToValueAtTime(peak, start + 0.012);
  gain.gain.exponentialRampToValueAtTime(0.0001, start + dur);
  osc.connect(gain).connect(ctx.destination);
  osc.start(start);
  osc.stop(start + dur);
}

/**
 * Phase 8G — 효과음 재생 hook. mp3 자산은 `/client/public/sfx/` 에 있어야 하며,
 * 없으면 silent fail (onerror). mute 토글은 localStorage 영속화.
 *
 * 브라우저 자동재생 정책 우회: 첫 사용자 클릭이 발생한 이후에만 새 Audio() 가
 * 재생됨. 보장은 useEffect 의 user-gesture-ack 가 아니라 호출 시점이 click 의
 * 직계 후속이라는 점 — applyEvent → trigger → useEffect → play 는 STOMP 수신
 * 이라 자동재생 차단 가능. play() 의 promise 실패는 무시.
 */
export function useSfx() {
  const [muted, setMuted] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false;
    return window.localStorage.getItem(MUTE_KEY) === '1';
  });

  useEffect(() => {
    if (typeof window === 'undefined') return;
    window.localStorage.setItem(MUTE_KEY, muted ? '1' : '0');
  }, [muted]);

  const play = useCallback((key: SfxKey) => {
    if (muted) return;
    try {
      const audio = new Audio(SFX_URL[key]);
      audio.volume = 0.6;
      audio.play().catch(() => {
        // 자동재생 차단 또는 파일 없음 — silent.
      });
    } catch {
      // 브라우저가 Audio 생성을 거부 — silent.
    }
  }, [muted]);

  /** 내 차례 알림 — 짧은 2음 합성(#9). mp3 불필요. 음소거 시 무음. */
  const playChime = useCallback(() => {
    if (muted) return;
    const ctx = getAudioCtx();
    if (!ctx) return;
    try {
      if (ctx.state === 'suspended') ctx.resume().catch(() => {});
      const t = ctx.currentTime;
      blip(ctx, 660, t, 0.14);
      blip(ctx, 880, t + 0.1, 0.18);
    } catch {
      // 합성 실패 — silent.
    }
  }, [muted]);

  /**
   * D-193 오목 강하게 두기 — 낮게 떨어지는 "쿵" + 짧은 "딱" 을 합성한다(mp3 불필요). `delaySec` 뒤(돌이 판에 닿는 순간)에 울린다.
   * 음소거 시 무음.
   */
  const playSlam = useCallback(
    (delaySec = 0) => {
      if (muted) return;
      const ctx = getAudioCtx();
      if (!ctx) return;
      try {
        if (ctx.state === 'suspended') ctx.resume().catch(() => {});
        const t = ctx.currentTime + delaySec;
        // 쿵 — 150Hz 에서 38Hz 로 떨어지는 사인.
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(150, t);
        osc.frequency.exponentialRampToValueAtTime(38, t + 0.42);
        gain.gain.setValueAtTime(0.0001, t);
        gain.gain.linearRampToValueAtTime(0.55, t + 0.008);
        gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.5);
        osc.connect(gain).connect(ctx.destination);
        osc.start(t);
        osc.stop(t + 0.52);
        // 딱 — 잡음 한 토막을 낮은 쪽만 남겨 나무를 치는 소리로.
        const len = Math.floor(ctx.sampleRate * 0.12);
        const buf = ctx.createBuffer(1, len, ctx.sampleRate);
        const data = buf.getChannelData(0);
        for (let i = 0; i < len; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / len) ** 3;
        const noise = ctx.createBufferSource();
        noise.buffer = buf;
        const filter = ctx.createBiquadFilter();
        filter.type = 'lowpass';
        filter.frequency.value = 1800;
        const ng = ctx.createGain();
        ng.gain.value = 0.5;
        noise.connect(filter).connect(ng).connect(ctx.destination);
        noise.start(t);
      } catch {
        // 합성 실패 — silent.
      }
    },
    [muted],
  );

  /**
   * D-194 오목 5목 — 빛줄기가 돌에 닿는 박자(`stepSec`)마다 한 음씩 올라가는 맑은 음 `count` 개를 합성한다(5음 음계, 작게).
   * `delaySec` 뒤에 시작한다. 음소거 시 무음.
   */
  const playFive = useCallback(
    (delaySec = 0, stepSec = 0.14, count = 5) => {
      if (muted) return;
      const ctx = getAudioCtx();
      if (!ctx) return;
      try {
        if (ctx.state === 'suspended') ctx.resume().catch(() => {});
        const t = ctx.currentTime + delaySec;
        const scale = [523.25, 587.33, 659.25, 783.99, 880, 1046.5];
        for (let i = 0; i < count; i++) {
          const last = i === count - 1;
          blip(ctx, scale[Math.min(i, scale.length - 1)], t + i * stepSec, last ? 0.5 : 0.2);
        }
      } catch {
        // 합성 실패 — silent.
      }
    },
    [muted],
  );

  /**
   * D-207 얏추 — 요트 칸(다섯 개 같은 눈)을 기록했을 때. 전부 합성이다(mp3·외부 음원 없음).
   * `mine` 이면 팡파르: 낮은 "쿵" + 주사위 잡음 → 다섯 음 상승(주사위가 하나씩 놓이는 박자) → 장화음과 반짝임(약 1.8초).
   * 남의 기록이면 작게 두 음만(내 판을 방해하지 않게). 음소거 시 무음.
   */
  const playYacht = useCallback(
    (mine = true) => {
      if (muted) return;
      const ctx = getAudioCtx();
      if (!ctx) return;
      try {
        if (ctx.state === 'suspended') ctx.resume().catch(() => {});
        const t = ctx.currentTime;
        if (!mine) {
          blip(ctx, 783.99, t, 0.16, 0.07);
          blip(ctx, 1046.5, t + 0.11, 0.3, 0.07);
          return;
        }
        // 쿵 — 주사위 컵이 탁자를 치는 낮은 소리.
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(130, t);
        osc.frequency.exponentialRampToValueAtTime(46, t + 0.24);
        gain.gain.setValueAtTime(0.0001, t);
        gain.gain.linearRampToValueAtTime(0.32, t + 0.008);
        gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.3);
        osc.connect(gain).connect(ctx.destination);
        osc.start(t);
        osc.stop(t + 0.32);
        // 주사위 잡음 — 높은 쪽만 남긴 짧은 잡음 두 토막.
        const len = Math.floor(ctx.sampleRate * 0.2);
        const buf = ctx.createBuffer(1, len, ctx.sampleRate);
        const data = buf.getChannelData(0);
        for (let i = 0; i < len; i++) {
          const k = i / len;
          const env = k < 0.5 ? (1 - k * 2) ** 2 : (1 - (k - 0.5) * 2) ** 2 * 0.6;
          data[i] = (Math.random() * 2 - 1) * env;
        }
        const noise = ctx.createBufferSource();
        noise.buffer = buf;
        const filter = ctx.createBiquadFilter();
        filter.type = 'bandpass';
        filter.frequency.value = 3200;
        const ng = ctx.createGain();
        ng.gain.value = 0.16;
        noise.connect(filter).connect(ng).connect(ctx.destination);
        noise.start(t);
        // 다섯 음 상승 — 도·미·솔·도·미(주사위 다섯 개).
        const rise = [523.25, 659.25, 783.99, 1046.5, 1318.51];
        rise.forEach((f, i) => blip(ctx, f, t + YACHT_SFX_FIRST_NOTE_SEC + i * YACHT_SFX_STEP_SEC, 0.17, 0.13, 'triangle'));
        // 팡파르 — 장화음을 길게, 그 위에 높은 반짝임 셋.
        const c = t + YACHT_SFX_CHORD_SEC;
        for (const f of [261.63, 523.25, 659.25, 783.99, 1046.5]) {
          blip(ctx, f, c, 1.05, 0.085, 'triangle');
          blip(ctx, f * 2, c, 0.7, 0.03);
        }
        [2093, 2637.02, 3135.96].forEach((f, i) => blip(ctx, f, c + 0.08 + i * 0.07, 0.4, 0.045));
      } catch {
        // 합성 실패 — silent.
      }
    },
    [muted],
  );

  /**
   * D-208 얏추 — 내 굴림이 족보로 멈춘 순간의 "됐다" 알림음. 전부 합성이고 `MADE_SFX_MAX_SEC` 안에 끝난다.
   * 약(스몰 스트레이트·풀 하우스) 두 음 · 중(라지 스트레이트·포 카드) 세 음 · 강(요트) 네 음 종소리 + 반짝임 하나.
   * 요트 칸 기록의 팡파르(`playYacht`)와 달리 쿵·잡음·화음이 없다 — 곧이어 기록해도 다투지 않는다. 음소거 시 무음.
   */
  const playMade = useCallback(
    (tier: MadeSfxTier) => {
      if (muted) return;
      const ctx = getAudioCtx();
      if (!ctx) return;
      try {
        if (ctx.state === 'suspended') ctx.resume().catch(() => {});
        const t = ctx.currentTime;
        if (tier === 'light') {
          blip(ctx, 659.25, t, 0.12, 0.09);
          blip(ctx, 880, t + 0.08, 0.2, 0.09);
        } else if (tier === 'mid') {
          blip(ctx, 659.25, t, 0.12, 0.11, 'triangle');
          blip(ctx, 880, t + 0.075, 0.12, 0.11, 'triangle');
          blip(ctx, 1174.66, t + 0.15, 0.28, 0.11, 'triangle');
        } else {
          [783.99, 1046.5, 1318.51, 1567.98].forEach((f, i) => blip(ctx, f, t + i * 0.07, i === 3 ? 0.34 : 0.14, 0.13));
          blip(ctx, 3135.96, t + 0.24, 0.3, 0.04);
        }
      } catch {
        // 합성 실패 — silent.
      }
    },
    [muted],
  );

  const toggleMute = useCallback(() => setMuted((v) => !v), []);

  return { play, playChime, playSlam, playFive, playYacht, playMade, muted, toggleMute };
}
