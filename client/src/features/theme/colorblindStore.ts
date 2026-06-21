import { create } from 'zustand';

// A5 — 색약 모드(기본 OFF). 슈트를 색뿐 아니라 글리프(◆/⚔/⛩/★)로도 식별하게 한다.
// themeStore/cardAnimStore 패턴 미러 — 수동 localStorage 영속 + main.tsx 에서 init() 1회.
const STORAGE_KEY = 'mirboard.colorblind';

interface ColorblindState {
  enabled: boolean;
  setEnabled: (v: boolean) => void;
  toggle: () => void;
  /** localStorage 복원 후 <html> data-colorblind 적용. 미저장 시 기본 OFF. */
  init: () => void;
}

function applyAttr(enabled: boolean) {
  try {
    const root = document.documentElement;
    if (enabled) root.setAttribute('data-colorblind', '1');
    else root.removeAttribute('data-colorblind');
  } catch {
    /* document 불가 환경 무시 */
  }
}

function persist(enabled: boolean) {
  try {
    localStorage.setItem(STORAGE_KEY, enabled ? '1' : '0');
  } catch {
    /* 무시 */
  }
}

export const useColorblindStore = create<ColorblindState>((set, get) => ({
  enabled: false,

  setEnabled(v) {
    persist(v);
    applyAttr(v);
    set({ enabled: v });
  },

  toggle() {
    get().setEnabled(!get().enabled);
  },

  init() {
    let enabled = false;
    try {
      enabled = localStorage.getItem(STORAGE_KEY) === '1';
    } catch {
      /* 무시 */
    }
    applyAttr(enabled);
    set({ enabled });
  },
}));
