import { act } from '@testing-library/react';
import type { SeatView, StarSquarePrivateBoard, StarSquareTableView } from '@/types/starsquare';
import { useStarSquareStore } from './starSquareStore';

/** 2 7 6 / 9 5 1 / 4 3 8 — 낙서 마방진. */
export const LO_SHU = [2, 7, 6, 9, 5, 1, 4, 3, 8];
/** 단서 2(왼쪽 위)·1(가운데 오른쪽). */
export const LO_SHU_CLUES = [2, 0, 0, 0, 0, 1, 0, 0, 0];

export function seat(i: number, over: Partial<SeatView> = {}): SeatView {
  return { seat: i, filled: 2, lines: 0, solved: false, solvePlace: 0, elapsedMs: -1, points: 0, firsts: 0, deserted: false, ...over };
}

export function view(over: Partial<StarSquareTableView> = {}): StarSquareTableView {
  return {
    phase: 'PLAYING',
    puzzleIndex: 0,
    puzzleCount: 3,
    size: 3,
    magicSum: 15,
    clues: LO_SHU_CLUES,
    remainingMs: 420_000,
    closing: false,
    seats: [seat(0), seat(1)],
    lastPuzzle: null,
    result: null,
    ...over,
  };
}

/** 스토어를 방 r1 · 내 좌석으로 맞추고 스냅샷 하나를 넣는다. */
export function seedStore(over: Partial<StarSquareTableView> = {}, mine: StarSquarePrivateBoard | null = {
  puzzleIndex: 0,
  grid: LO_SHU_CLUES,
  version: 0,
  solved: false,
}, mySeat = 0) {
  act(() => {
    const st = useStarSquareStore.getState();
    st.setMySeat(mySeat);
    st.reset('r1');
    st.applySnapshot({ roomId: 'r1', phase: 'PLAYING', eventSeq: 1, tableView: view(over), privateHand: mine });
  });
}
