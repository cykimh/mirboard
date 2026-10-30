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

  const toggleMute = useCallback(() => setMuted((v) => !v), []);

  return { play, playChime, muted, toggleMute };
}
