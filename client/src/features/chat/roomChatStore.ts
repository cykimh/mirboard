import { create } from 'zustand';

export interface RoomChatMessage {
  eventId: string;
  ts: number;
  userId: number;
  username: string;
  message: string;
}

const MAX_MESSAGES = 200;

interface RoomChatState {
  roomId: string | null;
  messages: RoomChatMessage[];
  unreadCount: number;
  /** roomId 가 바뀌면 호출. 이전 방 메시지/카운트 전부 초기화. */
  reset: (roomId: string) => void;
  appendIncoming: (msg: RoomChatMessage, isPanelOpen: boolean) => void;
  markRead: () => void;
}

/**
 * Phase 8B — 인-게임 채팅 메시지 큐 + 안 읽은 카운트. 인메모리 (영속화 없음, 로비
 * 채팅 패턴 동일). 200개 까지만 유지.
 */
export const useRoomChatStore = create<RoomChatState>((set) => ({
  roomId: null,
  messages: [],
  unreadCount: 0,
  reset: (roomId) => set({ roomId, messages: [], unreadCount: 0 }),
  appendIncoming: (msg, isPanelOpen) =>
    set((s) => ({
      messages: [...s.messages, msg].slice(-MAX_MESSAGES),
      unreadCount: isPanelOpen ? 0 : s.unreadCount + 1,
    })),
  markRead: () => set({ unreadCount: 0 }),
}));
