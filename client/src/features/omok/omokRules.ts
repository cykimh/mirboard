import { BOARD_SIZE, COLUMN_LETTERS, type OmokForbiddenPoint, type OmokMove, type OmokPoint, type OmokStone } from '@/types/omok';

/**
 * 오목 화면 계산 — 판정은 하지 않는다(Server-Authoritative). 금수 자리는 서버가 보낸 목록을 그대로 쓴다.
 */

export const key = (p: OmokPoint) => `${p.x},${p.y}`;

/** 수순 → 칸별 색(짝수 번째가 흑). */
export function stonesOf(moves: OmokMove[]): Map<string, OmokStone> {
  const out = new Map<string, OmokStone>();
  moves.forEach((m, i) => out.set(key(m), i % 2 === 0 ? 'BLACK' : 'WHITE'));
  return out;
}

export function onBoard(p: OmokPoint): boolean {
  return p.x >= 0 && p.x < BOARD_SIZE && p.y >= 0 && p.y < BOARD_SIZE;
}

/** 판 안 좌표(교차점 간격 1)를 가장 가까운 교차점으로. 판 밖은 가장자리로 자른다. */
export function nearestPoint(fx: number, fy: number): OmokPoint {
  const clamp = (v: number) => Math.min(BOARD_SIZE - 1, Math.max(0, Math.round(v)));
  return { x: clamp(fx), y: clamp(fy) };
}

export function forbiddenAt(forbidden: OmokForbiddenPoint[], p: OmokPoint | null): OmokForbiddenPoint | null {
  if (!p) return null;
  return forbidden.find((f) => f.x === p.x && f.y === p.y) ?? null;
}

export const FORBIDDEN_LABEL: Record<OmokForbiddenPoint['kind'], string> = {
  DOUBLE_THREE: '33 금수예요',
  DOUBLE_FOUR: '44 금수예요',
  OVERLINE: '장목(6목 이상) 금수예요',
};

/** 화면 좌표 글자 — 가로 A~T(I 없음), 세로 19~1(아래가 1). 돋보기·데스크톱 표시·보조기기 이름. */
export function coordName(p: OmokPoint): string {
  return `${COLUMN_LETTERS[p.x] ?? '?'}${BOARD_SIZE - p.y}`;
}

export const TEAM_OF = (seat: number) => seat % 2;
