import { BOARD_SIZE, VERTICAL_FACES, type DambadukCell, type DambadukDir } from '@/types/dambaduk';

/**
 * 담바둑 판 그리기용 기하 — 면 번호(서버 `Grid` 와 같은 규칙, 클라와의 계약)를 칸·선분으로 바꾼다. **판정이 아니다** — 갈 수 있는 칸·담 자리·
 * 터·점수는 서버가 보낸 값을 그린다. 여기 있는 것은 "면 17 은 어디에 그리나" 같은 그림 좌표뿐이다.
 */

/** 화면 글자 — 가로 A~G, 세로 1~7(위에서부터). B2 = (1,1). */
export function cellName(c: DambadukCell): string {
  return `${String.fromCharCode(65 + c.x)}${c.y + 1}`;
}

export function cellIndex(c: DambadukCell): number {
  return c.y * BOARD_SIZE + c.x;
}

export function cellOf(index: number): DambadukCell {
  return { x: index % BOARD_SIZE, y: Math.floor(index / BOARD_SIZE) };
}

const STEP: Record<DambadukDir, DambadukCell> = {
  UP: { x: 0, y: -1 },
  RIGHT: { x: 1, y: 0 },
  DOWN: { x: 0, y: 1 },
  LEFT: { x: -1, y: 0 },
};

export function step(c: DambadukCell, d: DambadukDir): DambadukCell {
  return { x: c.x + STEP[d].x, y: c.y + STEP[d].y };
}

function onBoard(c: DambadukCell): boolean {
  return c.x >= 0 && c.x < BOARD_SIZE && c.y >= 0 && c.y < BOARD_SIZE;
}

/** 칸 `c` 의 `d` 쪽 면 번호, 바깥담이면 −1 (서버 `Grid.face` 와 같다). */
export function faceOf(c: DambadukCell, d: DambadukDir): number {
  if (!onBoard(c) || !onBoard(step(c, d))) return -1;
  switch (d) {
    case 'RIGHT':
      return c.y * (BOARD_SIZE - 1) + c.x;
    case 'LEFT':
      return c.y * (BOARD_SIZE - 1) + c.x - 1;
    case 'DOWN':
      return VERTICAL_FACES + c.y * BOARD_SIZE + c.x;
    case 'UP':
      return VERTICAL_FACES + (c.y - 1) * BOARD_SIZE + c.x;
  }
}

/** 면이 놓인 선분 — 칸 단위 좌표(판 왼쪽 위 0,0 ~ 오른쪽 아래 7,7). 세로 선분이면 x1 === x2. */
export interface Segment {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  vertical: boolean;
}

export function segmentOf(face: number): Segment {
  if (face < VERTICAL_FACES) {
    const y = Math.floor(face / (BOARD_SIZE - 1));
    const x = (face % (BOARD_SIZE - 1)) + 1;
    return { x1: x, y1: y, x2: x, y2: y + 1, vertical: true };
  }
  const f = face - VERTICAL_FACES;
  const y = Math.floor(f / BOARD_SIZE) + 1;
  const x = f % BOARD_SIZE;
  return { x1: x, y1: y, x2: x + 1, y2: y, vertical: false };
}

export const DIR_LABEL: Record<DambadukDir, string> = { UP: '위', RIGHT: '오른쪽', DOWN: '아래', LEFT: '왼쪽' };
/** 경로 읽기 — ["UP","LEFT"] → "위 → 왼쪽". */
export function pathLabel(path: readonly DambadukDir[]): string {
  return path.map((d) => DIR_LABEL[d]).join(' → ');
}
