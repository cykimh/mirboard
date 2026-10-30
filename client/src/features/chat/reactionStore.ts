import { useEffect, useState } from 'react';
import { create } from 'zustand';

/** 좌석에 떠오르는 이모지 반응(P2/7). 짧게 노출 후 prune 으로 정리. */
export interface Reaction {
  id: number;
  fromSeat: number;
  emoji: string;
  ts: number;
}

export const REACTION_TTL_MS = 2600;

/** 보낼 수 있는 이모지(서버 화이트리스트와 같은 8종). 티츄·원카드가 같이 쓴다(D-154). */
export const REACTIONS = ['👍', '😂', '😮', '😢', '🔥', '👏', '❤️', '🎉'];
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

/**
 * D-154 — 좌석별로 지금 떠 있는 마지막 반응(좌석 → 이모지). 짧게 띄우고 지운다(TTL). 원카드 좌석 패널이 쓴다 — 티츄는 좌석 좌표로
 * 띄우는 `ReactionFloats` 를 쓴다.
 */
export function useLiveReactions(): Record<number, Reaction> {
  const recent = useReactionStore((s) => s.recent);
  const prune = useReactionStore((s) => s.prune);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (recent.length === 0) return;
    const id = window.setInterval(() => {
      const n = Date.now();
      prune(n);
      setNow(n);
    }, 400);
    return () => window.clearInterval(id);
  }, [recent.length, prune]);
  const bySeat: Record<number, Reaction> = {};
  for (const r of recent) {
    if (r.fromSeat >= 0 && now - r.ts < REACTION_TTL_MS) bySeat[r.fromSeat] = r;
  }
  return bySeat;
}
