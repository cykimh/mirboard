import { create } from 'zustand';

const STORAGE_KEY = 'mirboard.theme';

export type Theme = 'light' | 'dark';

interface ThemeState {
  theme: Theme;
  setTheme: (t: Theme) => void;
  toggle: () => void;
  /** localStorage 복원 후 `<html>` 클래스 적용. 미저장 시 기본 dark. */
  init: () => void;
}

function applyHtmlClass(theme: Theme) {
  const root = document.documentElement;
  root.classList.toggle('dark', theme === 'dark');
}

function persist(theme: Theme) {
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    /* localStorage 불가 환경 무시 */
  }
}

export const useThemeStore = create<ThemeState>((set, get) => ({
  // 앱 기본은 다크(현행 UI 와 동일). init() 가 저장값으로 덮어쓴다.
  theme: 'dark',

  setTheme(t) {
    applyHtmlClass(t);
    persist(t);
    set({ theme: t });
  },

  toggle() {
    get().setTheme(get().theme === 'dark' ? 'light' : 'dark');
  },

  init() {
    let theme: Theme = 'dark';
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'light' || saved === 'dark') theme = saved;
    } catch {
      /* 무시 */
    }
    applyHtmlClass(theme);
    set({ theme });
  },
}));
