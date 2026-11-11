import { useEffect, useState } from 'react';

/**
 * 씨앗포 게임판 배치 (D-253) — `orientation` 미디어 쿼리를 `matchMedia` 로 본다.
 * - `landscape`: 가로로 눕힌 휴대폰(높이 540px 이하). 게임판이 화면을 덮고 전장이 높이에 꽉 맞는다, 나머지는 겹친다.
 * - `portrait`: 세로 화면. 전장이 화면 폭 전체 + 위쪽 하늘 여백({@link PORTRAIT_HEADROOM}).
 * - `wide`: 그 밖(데스크톱 가로 창) — 예전처럼 쌓는다.
 */
export type ArtilleryLayout = 'landscape' | 'portrait' | 'wide';

export const LANDSCAPE_QUERY = '(orientation: landscape) and (max-height: 540px)';
export const PORTRAIT_QUERY = '(orientation: portrait)';
/** 세로 화면의 위쪽 하늘 여백(칸) — 그림만이다(물리·판정은 640×360 그대로). 높이 솟은 씨앗이 화면 안에 보인다. */
export const PORTRAIT_HEADROOM = 100;

function read(): ArtilleryLayout {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'wide';
  if (window.matchMedia(LANDSCAPE_QUERY).matches) return 'landscape';
  if (window.matchMedia(PORTRAIT_QUERY).matches) return 'portrait';
  return 'wide';
}

export function useArtilleryLayout(): ArtilleryLayout {
  const [layout, setLayout] = useState<ArtilleryLayout>(read);
  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const queries = [window.matchMedia(LANDSCAPE_QUERY), window.matchMedia(PORTRAIT_QUERY)];
    const update = () => setLayout(read());
    for (const q of queries) q.addEventListener?.('change', update);
    window.addEventListener('resize', update);
    update();
    return () => {
      for (const q of queries) q.removeEventListener?.('change', update);
      window.removeEventListener('resize', update);
    };
  }, []);
  return layout;
}

/** 세로 화면 "가로로 돌리면 크게" 안내 — 한 번 닫으면 이 브라우저에선 다시 안 뜬다. 저장소가 막혀 있으면 이번 화면에서만 닫힌다. */
export const ROTATE_HINT_KEY = 'mirboard.artillery.rotateHint.v1';

export function rotateHintDismissed(): boolean {
  try {
    return window.localStorage.getItem(ROTATE_HINT_KEY) === '1';
  } catch {
    return false;
  }
}

export function dismissRotateHint(): void {
  try {
    window.localStorage.setItem(ROTATE_HINT_KEY, '1');
  } catch {
    /* 저장 못 해도 이번엔 닫힌다 */
  }
}

/** 전체화면 + 가로 고정이 되는 기기인가(안드로이드 크롬 등 — iOS 사파리는 없다). 없어도 게임은 그대로 된다. */
export function canLockLandscape(): boolean {
  if (typeof document === 'undefined' || typeof screen === 'undefined') return false;
  const orientation = screen.orientation as (ScreenOrientation & { lock?: (o: string) => Promise<void> }) | undefined;
  return document.fullscreenEnabled === true && typeof orientation?.lock === 'function';
}

/** 전체화면으로 바꾸고 가로로 고정해 본다 — 실패는 조용히 넘긴다(선택 기능). */
export async function lockLandscape(): Promise<boolean> {
  try {
    if (!document.fullscreenElement) await document.documentElement.requestFullscreen();
    const orientation = screen.orientation as ScreenOrientation & { lock?: (o: string) => Promise<void> };
    await orientation.lock?.('landscape');
    return true;
  } catch {
    return false;
  }
}
