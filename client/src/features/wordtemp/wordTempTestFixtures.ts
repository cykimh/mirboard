import { act } from '@testing-library/react';
import type { SeatView, WordTempPrivateView, WordTempTableView } from '@/types/wordtemp';
import { useWordTempStore } from './wordTempStore';

export function seat(i: number, over: Partial<SeatView> = {}): SeatView {
  return { seat: i, alive: true, bot: false, setterPoints: 0, guessPoints: 0, total: 0, chosen: true, ...over };
}

/** 기본 — 4인, 1/2 라운드, 좌석 1(나래)이 출제자인 1차 힌트 단계. */
export function view(over: Partial<WordTempTableView> = {}): WordTempTableView {
  return {
    phase: 'HINT',
    round: 1,
    rounds: 2,
    turnNo: 1,
    setterSeat: 1,
    attempt: 1,
    phaseRemainingMs: 90_000,
    hintCount: 0,
    maxHints: 5,
    revealed: [],
    guessedSeats: [],
    attempts: [],
    seats: [seat(0), seat(1), seat(2), seat(3, { bot: true })],
    order: [1, 2, 3, 0],
    history: [],
    turnReveal: null,
    result: null,
    ...over,
  };
}

/** 내 비공개 뷰 — 기본은 추리자(후보만 있다). */
export function mine(over: Partial<WordTempPrivateView> = {}): WordTempPrivateView {
  return {
    seat: 0,
    candidates: [
      { index: 0, word: '바다', category: 'nature', available: true },
      { index: 1, word: '자전거', category: 'vehicle', available: true },
      { index: 2, word: '김밥', category: 'food', available: true },
    ],
    chosen: 0,
    answer: null,
    hints: [],
    myGuess: null,
    ...over,
  };
}

/** 스토어를 방 r1 · 내 좌석으로 맞추고 스냅샷 하나를 넣는다. `priv` 가 null 이면 관전자·봇처럼 비공개 뷰가 없다. */
export function seedStore(
  over: Partial<WordTempTableView> = {},
  priv: WordTempPrivateView | null = mine(),
  mySeat = 0,
) {
  act(() => {
    const st = useWordTempStore.getState();
    st.setMySeat(mySeat);
    st.reset('r1');
    st.applySnapshot({ roomId: 'r1', phase: over.phase ?? 'HINT', eventSeq: 1, tableView: view(over), privateHand: priv });
  });
}
