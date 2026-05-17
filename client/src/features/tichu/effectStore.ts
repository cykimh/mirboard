import { create } from 'zustand';
import type { HandType } from '@/types/tichu';

export type EffectKind = 'BOMB' | 'STRAIGHT_FLUSH_BOMB' | 'TICHU_DECLARED';

export interface ActiveEffect {
  id: number;
  kind: EffectKind;
  /** TICHU_DECLARED 전용 — 배너에 표시할 문구 (예: "🔔 티츄! · 시트 2"). */
  text?: string;
  /** 자동 해제 epoch ms. */
  expiresAt: number;
}

interface EffectState {
  active: ActiveEffect | null;
  trigger: (kind: EffectKind, text?: string) => void;
  clear: () => void;
}

/** 이펙트별 노출 시간. 선언 배너는 모두가 인지하도록 약간 더 길게. */
const DURATION_BY_KIND: Record<EffectKind, number> = {
  BOMB: 1800,
  STRAIGHT_FLUSH_BOMB: 1800,
  TICHU_DECLARED: 2000,
};
let nextId = 1;

/**
 * Phase 8G — 하이핸드 이펙트 dispatcher. tichuStore.applyEvent 의 PLAYED 분기에서
 * handType in {BOMB, STRAIGHT_FLUSH_BOMB} 이면 trigger. EffectsOverlay 컴포넌트가
 * active 를 구독해 화면 플래시 + SVG 폭발 렌더.
 */
export const useEffectStore = create<EffectState>((set) => ({
  active: null,
  trigger: (kind, text) => {
    const id = nextId++;
    set({
      active: { id, kind, text, expiresAt: Date.now() + DURATION_BY_KIND[kind] },
    });
  },
  clear: () => set({ active: null }),
}));

/** HandType 가 하이핸드면 EffectKind 반환, 아니면 null. */
export function effectForHandType(type: HandType): EffectKind | null {
  if (type === 'BOMB') return 'BOMB';
  if (type === 'STRAIGHT_FLUSH_BOMB') return 'STRAIGHT_FLUSH_BOMB';
  return null;
}
