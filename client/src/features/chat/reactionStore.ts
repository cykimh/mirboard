import { create } from 'zustand';

/** 좌석에 떠오르는 이모지 반응(P2/7). 짧게 노출 후 prune 으로 정리. */
export interface Reaction {
  id: number;
  fromSeat: number;
  emoji: string;
  ts: number;
}

export const REACTION_TTL_MS = 2600;
let nextId = 1;

interface ReactionState {
  recent: Reaction[];
  /** 수신한 반응 추가(ts 는 호출 시각). */
  add: (fromSeat: number, emoji: string) => void;
  /** TTL 지난 항목 제거. now 를 명시받아 결정적(테스트 용이). */
  prune: (now: number) => void;
  reset: () => void;
}

export const useReactionStore = create<ReactionState>((set) => ({
  recent: [],
  add: (fromSeat, emoji) =>
    set((s) => ({
      recent: [...s.recent, { id: nextId++, fromSeat, emoji, ts: Date.now() }],
    })),
  prune: (now) =>
    set((s) => {
      const next = s.recent.filter((r) => now - r.ts < REACTION_TTL_MS);
      return next.length === s.recent.length ? s : { recent: next };
    }),
  reset: () => set({ recent: [] }),
}));
