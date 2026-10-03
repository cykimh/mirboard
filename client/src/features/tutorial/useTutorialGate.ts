import { useCallback, useEffect, useState } from 'react';

/**
 * A2 → D-121 — 튜토리얼 열람 플래그(localStorage) 게이트. 키는 **게임별**이고 필수다 —
 * 게임 중립 계층에 특정 게임의 기본 키를 두지 않는다(각 게임의 `GameTutorial.seenKey`).
 */
export function hasSeenTutorial(seenKey: string): boolean {
  return localStorage.getItem(seenKey) != null;
}

export function markTutorialSeen(seenKey: string): void {
  localStorage.setItem(seenKey, '1');
}

/**
 * @param seenKey 열람 기록 키. null 이면(대기실 첫 렌더처럼 아직 게임을 모를 때) 자동 노출도
 *   기록도 하지 않는다.
 * @param autoOpen false 면 미열람이어도 자동으로 열지 않는다(게임판 '규칙' 버튼처럼 수동 전용).
 */
export function useTutorialGate(seenKey: string | null, autoOpen = true) {
  const [open, setOpen] = useState(false);

  // 미열람 + 자동 노출 허용일 때만 연다. 키가 늦게 정해져도(null → 키) 다시 판단한다.
  useEffect(() => {
    if (autoOpen && seenKey && !hasSeenTutorial(seenKey)) setOpen(true);
  }, [seenKey, autoOpen]);

  const show = useCallback(() => setOpen(true), []);

  // 호출 시점의 키를 기록한다 — 첫 렌더(키 null)에 잡힌 클로저가 남으면 기록이 빠진다.
  const close = useCallback(() => {
    if (seenKey) markTutorialSeen(seenKey);
    setOpen(false);
  }, [seenKey]);

  /** 열람으로 기록하지 않고 닫는다 — 게임 시작처럼 사용자가 닫은 게 아닌 경우. */
  const hide = useCallback(() => setOpen(false), []);

  return { open, show, close, hide };
}
