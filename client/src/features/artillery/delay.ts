import type { DelayTable, Item, Shell, UnitView } from '@/types/artillery';

/**
 * D-226 — 이번 차례에 쌓일 딜레이(화면 안내). 판정은 서버다 — 표는 공개 뷰가 실어 준 서버 정본이고, 쓴 연료는 이동 미리보기 값이라
 * 서버가 다시 걸으면 1~2 어긋날 수 있다. 표가 없으면(구 서버) null.
 */
export function turnDelay(
  table: DelayTable | null,
  shell: Shell,
  item: Item | null,
  fuelUsed: number,
): number | null {
  if (!table) return null;
  const base = table.shells[shell];
  if (base === undefined) return null;
  return base + Math.floor(Math.max(0, fuelUsed) / Math.max(1, table.fuelPerDelay)) + (item ? (table.items[item] ?? 0) : 0);
}

/** 살아 있는 포대 중 가장 작은 딜레이 — 좌석의 딜레이는 이것과의 차이로 보인다. */
export function minDelay(units: UnitView[]): number {
  let m = Infinity;
  for (const u of units) if (u.alive && u.delay !== undefined) m = Math.min(m, u.delay);
  return m === Infinity ? 0 : m;
}

/** D-225 — 팀 이름. 팀 = 좌석 홀짝(대기실 팀 배정의 1·3번째 자리 대 2·4번째 자리). */
export const TEAM_NAMES = ['A팀', 'B팀'] as const;
export const teamName = (team: number) => TEAM_NAMES[team] ?? `${team + 1}팀`;
