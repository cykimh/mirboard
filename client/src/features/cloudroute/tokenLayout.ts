import { gridOf } from './board';

/**
 * 말 자리 계산 (D-218). 단위는 **판 폭의 1/100**(`--cr-u`) — CSS 의 판 안쪽 여백·칸 사이와 같은 수를 쓴다
 * (`22-cloudroute-table.css` 의 `.cr-board` padding 1.2u · gap 0.8u, `tokenLayout.test` 가 원문으로 붙잡는다).
 *
 * 말은 칸 위가 아니라 **칸 안쪽 가장자리 옆(가운데 하늘 쪽)** 에 떠 있다 — 375px 에서 칸이 50px 안팎이라 칸 위에 올리면 그림·이름을
 * 가린다. 모서리 칸은 안쪽 꼭짓점에 걸친다. 같은 칸에 여럿이면 가장자리를 따라 나란히(셋부터는 지그재그) 선다.
 */
export const BOARD_PAD_U = 1.2;
export const BOARD_GAP_U = 0.8;
export const CELL_U = (100 - 2 * BOARD_PAD_U - 6 * BOARD_GAP_U) / 7;
const PITCH_U = CELL_U + BOARD_GAP_U;
/** 칸 안쪽 가장자리에서 말 중심까지(가운데 쪽으로). */
const INSET_U = 3.2;

export interface Point {
  x: number;
  y: number;
}

const cellStart = (i: number) => BOARD_PAD_U + i * PITCH_U;
const cellMid = (i: number) => cellStart(i) + CELL_U / 2;

/** 칸 한가운데(연출 입자·말풍선 기준점). */
export function tileCenter(tile: number): Point {
  const { row, col } = gridOf(tile);
  return { x: cellMid(col), y: cellMid(row) };
}

interface Anchor extends Point {
  /** 여럿일 때 벌어지는 방향. */
  sx: number;
  sy: number;
  /** 가운데 쪽 방향. */
  ix: number;
  iy: number;
  corner: boolean;
}

function anchorOf(tile: number): Anchor {
  const { row, col } = gridOf(tile);
  const near = cellStart(1) - BOARD_GAP_U; // 왼쪽·윗줄 칸의 안쪽 가장자리
  const far = cellStart(6); // 오른쪽·아랫줄 칸의 안쪽 가장자리
  const edgeRow = row === 0 || row === 6;
  const edgeCol = col === 0 || col === 6;
  if (edgeRow && edgeCol) {
    const ix = col === 0 ? 1 : -1;
    const iy = row === 0 ? 1 : -1;
    return {
      x: (col === 0 ? near : far) + ix * 0.6,
      y: (row === 0 ? near : far) + iy * 0.6,
      sx: 0,
      sy: 0,
      ix,
      iy,
      corner: true,
    };
  }
  if (row === 6) return { x: cellMid(col), y: far - INSET_U, sx: 1, sy: 0, ix: 0, iy: -1, corner: false };
  if (row === 0) return { x: cellMid(col), y: near + INSET_U, sx: 1, sy: 0, ix: 0, iy: 1, corner: false };
  if (col === 0) return { x: near + INSET_U, y: cellMid(row), sx: 0, sy: 1, ix: 1, iy: 0, corner: false };
  return { x: far - INSET_U, y: cellMid(row), sx: 0, sy: 1, ix: -1, iy: 0, corner: false };
}

/** [벌어짐, 가운데 쪽] — 같은 칸 말 수별 자리. */
const SIDE_SLOTS: Record<number, [number, number][]> = {
  1: [[0, 0]],
  2: [
    [-3.4, 0],
    [3.4, 0],
  ],
  3: [
    [-4.3, 0],
    [0, 2.7],
    [4.3, 0],
  ],
  4: [
    [-4.7, 0],
    [-1.6, 2.8],
    [1.6, 0],
    [4.7, 2.8],
  ],
};

/**
 * 말 중심 자리. `index`/`count` 는 그 칸에 **서 있는** 말들 사이의 순서(움직이는 말은 `count = 1` 로 불러 가운데 자리를 쓴다).
 */
export function tokenPoint(tile: number, index = 0, count = 1): Point {
  const a = anchorOf(tile);
  const n = Math.max(1, Math.min(4, count));
  const k = Math.max(0, Math.min(n - 1, index));
  if (a.corner) {
    // 모서리는 안쪽 대각선으로 줄을 선다.
    const step = 2.3 * k;
    return { x: a.x + a.ix * step, y: a.y + a.iy * step };
  }
  const [spread, inward] = SIDE_SLOTS[n][k];
  return { x: a.x + a.sx * spread + a.ix * inward, y: a.y + a.sy * spread + a.iy * inward };
}

/** `from → to` 까지 한 칸씩(끝 포함, 시작 제외). `dir` 이 -1 이면 뒤로. */
export function stepPath(from: number, to: number, dir: 1 | -1 = 1, size = 24): number[] {
  const path: number[] = [];
  let at = from;
  while (at !== to && path.length < size) {
    at = (at + dir + size) % size;
    path.push(at);
  }
  return path;
}
