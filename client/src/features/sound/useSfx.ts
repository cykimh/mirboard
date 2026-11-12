import { useCallback, useEffect, useState } from 'react';

export type SfxKey = 'bomb' | 'straight-flush';

const SFX_URL: Record<SfxKey, string> = {
  bomb: '/sfx/bomb.mp3',
  'straight-flush': '/sfx/straight-flush.mp3',
};

const MUTE_KEY = 'mirboard.sfxMuted';

// 차례 알림용 합성음(#9). mp3 에셋이 없어 Web Audio 로 짧은 2음을 생성한다.
// AudioContext 는 싱글톤(사용자 제스처 이후 resume).
let audioCtx: AudioContext | null = null;
function getAudioCtx(): AudioContext | null {
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

function blip(ctx: AudioContext, freq: number, start: number, dur: number) {
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = 'sine';
  osc.frequency.value = freq;
  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.linearRampToValueAtTime(0.18, start + 0.012);
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

  const toggleMute = useCallback(() => setMuted((v) => !v), []);

  return { play, playChime, playSlam, playFive, muted, toggleMute };
}
