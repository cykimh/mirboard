import type { PassSlot } from './tichuStore';

/**
 * 손패에 "선택됨" 으로 그릴 카드 키 집합.
 *
 * 플레이 단계에서는 선택 집합(selected)이 곧 표시 대상이다. 패스 단계에서는
 * 그 위에 두 가지를 더 얹는다 — 이미 줄 사람에게 배정된 카드(passSelection)와,
 * 슬롯 배정을 기다리는 카드(pendingPassCardKey). 셋을 합쳐야 "고른 카드가
 * 눌린 상태로 보인다" 는 UX 가 성립한다.
 *
 * D-87 에서 GameTable 에서 분리. 동작 불변.
 */
export function getSelectedKeys(
  selected: Set<string>,
  passSelection: Record<PassSlot, string | null>,
  isInPassing: boolean,
  pendingPassCardKey: string | null,
): Set<string> {
  if (!isInPassing) return selected;
  const merged = new Set(selected);
  for (const v of Object.values(passSelection)) {
    if (v) merged.add(v);
  }
  if (pendingPassCardKey) merged.add(pendingPassCardKey);
  return merged;
}
