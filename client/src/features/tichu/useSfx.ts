import { useCallback, useEffect, useState } from 'react';

export type SfxKey = 'bomb' | 'straight-flush';

const SFX_URL: Record<SfxKey, string> = {
  bomb: '/sfx/bomb.mp3',
  'straight-flush': '/sfx/straight-flush.mp3',
};

const MUTE_KEY = 'mirboard.sfxMuted';

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

  const toggleMute = useCallback(() => setMuted((v) => !v), []);

  return { play, muted, toggleMute };
}
