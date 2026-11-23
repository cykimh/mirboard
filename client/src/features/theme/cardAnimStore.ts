import { create } from 'zustand';

// 연출(카드 애니메이션) on/off (기본 ON). themeStore 패턴 미러 — 수동 localStorage
// 영속 + main.tsx 에서 init() 1회 호출.
//
// D-291 — 연출을 보일지의 **단일 기준**이다:
//   · 저장값 '1'(사용자가 메뉴에서 직접 켬) → OS '동작 줄이기'여도 연출을 보인다(`forced`).
//   · 저장값 '0'(직접 끔) → 항상 끈다.
//   · 저장값 없음 → OS 설정을 따른다(동작 줄이기면 끔).
// `forced` 는 `<html>` 의 `motion-forced` 클래스로도 나간다 — CSS 의 `@media (prefers-reduced-motion: reduce)` 블록은 전부
// `:root:not(.motion-forced)` 아래에 있어 직접 켠 사람에게는 닿지 않는다. JS 로 돌리는 연출은 {@link motionReduced} 하나로 묻는다
// (`matchMedia` 를 따로 읽지 말 것).
const STORAGE_KEY = 'mirboard.cardAnim';
/** `<html>` 클래스 — 사용자가 연출을 직접 켰다(OS 동작 줄이기보다 앞선다). */
export const MOTION_FORCED_CLASS = 'motion-forced';

interface CardAnimState {
  enabled: boolean;
  /** 사용자가 직접 켰다(저장값 '1') — OS '동작 줄이기'보다 앞선다. */
  forced: boolean;
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

function applyHtmlClass(forced: boolean) {
  try {
    document.documentElement.classList.toggle(MOTION_FORCED_CLASS, forced);
  } catch {
    /* 문서 없음 — 무시 */
  }
}

/** OS '동작 줄이기' 선호(앱 설정과 무관한 날것). */
function osReducedMotion(): boolean {
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
  forced: false,

  setEnabled(v) {
    persist(v);
    applyHtmlClass(v);
    set({ enabled: v, forced: v });
  },

  toggle() {
    get().setEnabled(!get().enabled);
  },

  init() {
    let enabled = true;
    let forced = false;
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === '1' || saved === '0') {
        enabled = saved === '1';
        forced = enabled;
      } else if (osReducedMotion()) {
        // 저장값 없음 + 모션 최소화 선호 → 기본 OFF (사용자가 토글로 덮어쓸 수 있음).
        enabled = false;
      }
    } catch {
      /* 무시 */
    }
    applyHtmlClass(forced);
    set({ enabled, forced });
  },
}));

/**
 * D-291 — 움직임을 줄여야 하나: OS '동작 줄이기'이고 사용자가 연출을 직접 켜지 않았을 때만 참. JS 로 돌리는 연출(카드 비행·금가루·
 * 캔버스)이 묻는 한 곳이다 — CSS 쪽 짝은 `:root:not(.motion-forced)`.
 */
export function motionReduced(): boolean {
  return osReducedMotion() && !useCardAnimStore.getState().forced;
}
