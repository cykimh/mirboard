import { create } from 'zustand';

// 카드 제출 애니메이션 on/off (기본 ON). themeStore 패턴 미러 — 수동 localStorage
// 영속 + main.tsx 에서 init() 1회 호출.
const STORAGE_KEY = 'mirboard.cardAnim';

interface CardAnimState {
  enabled: boolean;
  setEnabled: (v: boolean) => void;
  toggle: () => void;
  /** localStorage 복원. 미저장 시 OS '동작 줄이기' 선호면 기본 OFF, 아니면 ON. */
  init: () => void;
}

function persist(enabled: boolean) {
  try {
    localStorage.setItem(STORAGE_KEY, enabled ? '1' : '0');
  } catch {
    /* localStorage 불가 환경 무시 */
  }
}

function prefersReducedMotion(): boolean {
  try {
    return (
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    );
  } catch {
    return false;
  }
}

export const useCardAnimStore = create<CardAnimState>((set, get) => ({
  // 앱 기본은 ON. init() 가 저장값/모션 설정으로 덮어쓴다.
  enabled: true,

  setEnabled(v) {
    persist(v);
    set({ enabled: v });
  },

  toggle() {
    get().setEnabled(!get().enabled);
  },

  init() {
    let enabled = true;
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === '1' || saved === '0') {
        enabled = saved === '1';
      } else if (prefersReducedMotion()) {
        // 저장값 없음 + 모션 최소화 선호 → 기본 OFF (사용자가 토글로 덮어쓸 수 있음).
        enabled = false;
      }
    } catch {
      /* 무시 */
    }
    set({ enabled });
  },
}));
