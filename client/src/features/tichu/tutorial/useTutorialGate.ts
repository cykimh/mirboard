import { useCallback, useEffect, useState } from 'react';

/** A2 — 첫 로그인 1회 자동 노출 플래그(localStorage). 헤더 도움말 버튼으로 재호출 가능. */
export const TUTORIAL_SEEN_KEY = 'mirboard.tutorial.seen.v1';

export function hasSeenTutorial(): boolean {
  return localStorage.getItem(TUTORIAL_SEEN_KEY) != null;
}

export function markTutorialSeen(): void {
  localStorage.setItem(TUTORIAL_SEEN_KEY, '1');
}

export function useTutorialGate() {
  const [open, setOpen] = useState(false);

  // 첫 방문(미열람)에만 자동 노출.
  useEffect(() => {
    if (!hasSeenTutorial()) setOpen(true);
  }, []);

  const show = useCallback(() => setOpen(true), []);

  const close = useCallback(() => {
    markTutorialSeen();
    setOpen(false);
  }, []);

  return { open, show, close };
}
