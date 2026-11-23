import type { DambadukMoveOption, DambadukStone, DambadukTableView, DambadukTerritory } from '@/types/dambaduk';

/** 테스트용 resync 판 — 2인, 선 = 좌석 0, 옮김 단계. */
export const TWO_STONES: DambadukStone[] = [
  { id: 0, seat: 0, kind: 'BENT', x: 1, y: 1 },
  { id: 1, seat: 0, kind: 'BENT', x: 5, y: 5 },
  { id: 2, seat: 1, kind: 'BENT', x: 5, y: 1 },
  { id: 3, seat: 1, kind: 'BENT', x: 1, y: 5 },
  { id: 4, seat: 0, kind: 'STRAIGHT', x: 2, y: 3 },
  { id: 5, seat: 1, kind: 'STRAIGHT', x: 4, y: 3 },
  { id: 6, seat: 1, kind: 'STRAIGHT', x: 3, y: 2 },
  { id: 7, seat: 0, kind: 'STRAIGHT', x: 3, y: 4 },
];

export const ONE_REGION: DambadukTerritory = {
  regions: [{ cells: Array.from({ length: 49 }, (_, i) => i), kind: 'CONTESTED', owner: -1, seats: [0, 1] }],
  scores: [0, 0],
  largest: [0, 0],
};

/** 돌 4(C4 곧은돌)의 수 — 위로 1·2칸, 왼쪽 1·2칸. */
export const OPTIONS_C4: DambadukMoveOption[] = [
  { stoneId: 4, path: ['UP'], toX: 2, toY: 2, jump: false, jumpedFace: -1, walls: ['UP', 'RIGHT', 'DOWN', 'LEFT'] },
  { stoneId: 4, path: ['LEFT'], toX: 1, toY: 3, jump: false, jumpedFace: -1, walls: ['UP', 'RIGHT', 'DOWN', 'LEFT'] },
  { stoneId: 4, path: ['UP', 'UP'], toX: 2, toY: 1, jump: false, jumpedFace: -1, walls: ['UP', 'RIGHT', 'DOWN', 'LEFT'] },
  { stoneId: 4, path: ['LEFT', 'LEFT'], toX: 0, toY: 3, jump: false, jumpedFace: -1, walls: ['UP', 'RIGHT', 'DOWN'] },
];

export function playingView(over: Partial<DambadukTableView> = {}): DambadukTableView {
  return {
    seatCount: 2,
    firstSeat: 0,
    seatColors: [0, 1],
    phase: 'PLAYING',
    stones: TWO_STONES,
    walls: [],
    placementQueue: [],
    turnSeat: 0,
    jumpsLeft: [0, 0],
    timeouts: [0, 0],
    exits: [],
    moveCount: 0,
    territory: ONE_REGION,
    placeKinds: [],
    options: OPTIONS_C4,
    result: null,
    ...over,
  };
}

export function placingView(over: Partial<DambadukTableView> = {}): DambadukTableView {
  return playingView({
    phase: 'PLACING',
    stones: TWO_STONES.slice(0, 4),
    placementQueue: [0, 1, 1, 0],
    placeKinds: ['STRAIGHT'],
    options: [],
    ...over,
  });
}

export function snapshot(tableView: DambadukTableView, turnRemainingMs: number | null = null) {
  return { roomId: 'r1', phase: tableView.phase, eventSeq: 1, tableView, privateHand: null, turnRemainingMs };
}
