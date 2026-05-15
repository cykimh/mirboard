import { create } from 'zustand';
import type { HandType } from '@/types/tichu';

export type EffectKind = 'BOMB' | 'STRAIGHT_FLUSH_BOMB';

export interface ActiveEffect {
  id: number;
  kind: EffectKind;
  /** 자동 해제 epoch ms. */
  expiresAt: number;
}

interface EffectState {
  active: ActiveEffect | null;
  trigger: (kind: EffectKind) => void;
  clear: () => void;
}

const EFFECT_DURATION_MS = 1800;
let nextId = 1;

/**
 * Phase 8G — 하이핸드 이펙트 dispatcher. tichuStore.applyEvent 의 PLAYED 분기에서
 * handType in {BOMB, STRAIGHT_FLUSH_BOMB} 이면 trigger. EffectsOverlay 컴포넌트가
 * active 를 구독해 화면 플래시 + SVG 폭발 렌더.
 */
export const useEffectStore = create<EffectState>((set) => ({
  active: null,
  trigger: (kind) => {
    const id = nextId++;
    set({ active: { id, kind, expiresAt: Date.now() + EFFECT_DURATION_MS } });
  },
  clear: () => set({ active: null }),
}));

/** HandType 가 하이핸드면 EffectKind 반환, 아니면 null. */
export function effectForHandType(type: HandType): EffectKind | null {
  if (type === 'BOMB') return 'BOMB';
  if (type === 'STRAIGHT_FLUSH_BOMB') return 'STRAIGHT_FLUSH_BOMB';
  return null;
}
