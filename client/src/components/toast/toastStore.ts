import { create } from 'zustand';

export type ToastTone = 'info' | 'error';

export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
}

/** 토스트가 떠 있는 시간. */
export const TOAST_MS = 3000;
/** 한 번에 보이는 최대 개수 — 넘치면 오래된 것부터 내린다. */
const MAX_TOASTS = 3;

interface ToastState {
  toasts: Toast[];
  show: (message: string, tone?: ToastTone) => void;
  dismiss: (id: number) => void;
  clear: () => void;
}

let nextId = 1;
const timers = new Map<number, ReturnType<typeof setTimeout>>();

/**
 * D-157 — 버튼을 누른 뒤의 짧은 안내(링크 복사됨·마이크 못 씀 등)를 화면 위에 떠서 보여 준다. 버튼 줄 안에 글자를 끼우면
 * 줄 높이·폭이 바뀌어 게임판 머리 줄이 일그러졌다. 같은 문구가 이미 떠 있으면 새로 쌓지 않고 시간만 다시 잰다.
 */
export const useToastStore = create<ToastState>((set, get) => {
  const schedule = (id: number) => {
    clearTimeout(timers.get(id));
    timers.set(
      id,
      setTimeout(() => get().dismiss(id), TOAST_MS),
    );
  };
  return {
    toasts: [],
    show: (message, tone = 'info') => {
      const same = get().toasts.find((t) => t.message === message && t.tone === tone);
      if (same) {
        schedule(same.id);
        return;
      }
      const toast: Toast = { id: nextId++, message, tone };
      const all = [...get().toasts, toast];
      for (const dropped of all.slice(0, Math.max(0, all.length - MAX_TOASTS))) {
        clearTimeout(timers.get(dropped.id));
        timers.delete(dropped.id);
      }
      set({ toasts: all.slice(-MAX_TOASTS) });
      schedule(toast.id);
    },
    dismiss: (id) => {
      clearTimeout(timers.get(id));
      timers.delete(id);
      set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
    },
    clear: () => {
      timers.forEach((t) => clearTimeout(t));
      timers.clear();
      set({ toasts: [] });
    },
  };
});

/** 컴포넌트 밖(훅 콜백 등)에서 부르는 짧은 형태. */
export function showToast(message: string, tone: ToastTone = 'info') {
  useToastStore.getState().show(message, tone);
}
