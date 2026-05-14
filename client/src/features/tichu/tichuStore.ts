import { create } from 'zustand';
import type { Card, PrivateHand, TableView } from '@/types/tichu';
import { cardKey } from '@/types/tichu';

export interface TichuRoomState {
  roomId: string | null;
  tableView: TableView | null;
  privateHand: PrivateHand | null;
  lastSeq: number;
  selectedCardKeys: Set<string>;
  errorMessage: string | null;
  roundEnded: { teamAScore: number; teamBScore: number; firstFinisherSeat: number } | null;
}

export interface TichuActions {
  reset: (roomId: string) => void;
  applySnapshot: (
    snapshot: { tableView: TableView; privateHand: PrivateHand; eventSeq: number },
  ) => void;
  applyTableView: (table: TableView, seq?: number) => void;
  applyPrivateHand: (hand: PrivateHand) => void;
  setError: (message: string | null) => void;
  setRoundEnded: (info: TichuRoomState['roundEnded']) => void;
  toggleCardSelection: (card: Card) => void;
  clearSelection: () => void;
}

export const useTichuStore = create<TichuRoomState & TichuActions>((set, get) => ({
  roomId: null,
  tableView: null,
  privateHand: null,
  lastSeq: 0,
  selectedCardKeys: new Set(),
  errorMessage: null,
  roundEnded: null,

  reset(roomId) {
    set({
      roomId,
      tableView: null,
      privateHand: null,
      lastSeq: 0,
      selectedCardKeys: new Set(),
      errorMessage: null,
      roundEnded: null,
    });
  },

  applySnapshot({ tableView, privateHand, eventSeq }) {
    set({
      tableView,
      privateHand,
      lastSeq: eventSeq,
      errorMessage: null,
    });
  },

  applyTableView(table, seq) {
    if (seq !== undefined && seq <= get().lastSeq) return;
    set({ tableView: table, lastSeq: seq ?? get().lastSeq });
  },

  applyPrivateHand(hand) {
    set({ privateHand: hand });
  },

  setError(message) {
    set({ errorMessage: message });
  },

  setRoundEnded(info) {
    set({ roundEnded: info });
  },

  toggleCardSelection(card) {
    const key = cardKey(card);
    const next = new Set(get().selectedCardKeys);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    set({ selectedCardKeys: next });
  },

  clearSelection() {
    set({ selectedCardKeys: new Set() });
  },
}));
