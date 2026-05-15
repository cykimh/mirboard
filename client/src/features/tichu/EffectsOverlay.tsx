import { useEffect } from 'react';
import { useEffectStore } from './effectStore';
import { useSfx } from './useSfx';

/**
 * Phase 8G — 화면 전역 이펙트 오버레이. effectStore.active 가 셋팅되면 CSS
 * 애니메이션 + 사운드를 1.8초 동안 노출 후 자동 clear.
 *
 * BOMB → 빨강 플래시 + 폭발 SVG.
 * STRAIGHT_FLUSH_BOMB → 보라 + 별 trail.
 */
export function EffectsOverlay() {
  const active = useEffectStore((s) => s.active);
  const clear = useEffectStore((s) => s.clear);
  const { play } = useSfx();

  useEffect(() => {
    if (!active) return;
    play(active.kind === 'BOMB' ? 'bomb' : 'straight-flush');
    const remaining = Math.max(0, active.expiresAt - Date.now());
    const id = window.setTimeout(clear, remaining);
    return () => window.clearTimeout(id);
  }, [active, clear, play]);

  if (!active) return null;

  const isStraightFlush = active.kind === 'STRAIGHT_FLUSH_BOMB';
  const flashColor = isStraightFlush ? 'rgba(180, 80, 255, 0.4)' : 'rgba(255, 60, 60, 0.45)';
  const label = isStraightFlush ? 'STRAIGHT FLUSH BOMB!' : 'BOMB!';

  return (
    <div
      key={active.id}
      style={{
        position: 'fixed',
        inset: 0,
        pointerEvents: 'none',
        zIndex: 9999,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: flashColor,
        animation: 'mirboard-fx-flash 1.8s ease-out forwards',
      }}
    >
      <svg
        width="320"
        height="320"
        viewBox="0 0 320 320"
        style={{ animation: 'mirboard-fx-burst 1.8s ease-out forwards' }}
      >
        {/* 폭발 spike — 12개 방사형 라인 */}
        {Array.from({ length: 12 }).map((_, i) => {
          const angle = (i * 30) * (Math.PI / 180);
          const x = 160 + Math.cos(angle) * 140;
          const y = 160 + Math.sin(angle) * 140;
          return (
            <line
              key={i}
              x1="160"
              y1="160"
              x2={x}
              y2={y}
              stroke={isStraightFlush ? '#cf6bff' : '#ffd23f'}
              strokeWidth="6"
              strokeLinecap="round"
            />
          );
        })}
        <circle
          cx="160"
          cy="160"
          r="60"
          fill={isStraightFlush ? '#cf6bff' : '#ff6b3f'}
          opacity="0.9"
        />
      </svg>
      <div
        style={{
          position: 'absolute',
          fontSize: 48,
          fontWeight: 900,
          color: '#fff',
          textShadow: '0 0 12px rgba(0,0,0,0.8)',
          letterSpacing: 2,
          animation: 'mirboard-fx-pop 1.8s ease-out forwards',
        }}
      >
        {label}
      </div>
    </div>
  );
}
