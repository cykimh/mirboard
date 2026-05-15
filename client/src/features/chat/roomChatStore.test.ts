import { beforeEach, describe, expect, it } from 'vitest';
import { useRoomChatStore, type RoomChatMessage } from './roomChatStore';

const msg = (id: string, ts = 1000): RoomChatMessage => ({
  eventId: id,
  ts,
  userId: 1,
  username: 'alice',
  message: `m${id}`,
});

describe('roomChatStore', () => {
  beforeEach(() => {
    useRoomChatStore.getState().reset('room-a');
  });

  it('reset clears messages and unread count', () => {
    useRoomChatStore.getState().appendIncoming(msg('1'), false);
    expect(useRoomChatStore.getState().messages.length).toBe(1);
    expect(useRoomChatStore.getState().unreadCount).toBe(1);

    useRoomChatStore.getState().reset('room-b');
    expect(useRoomChatStore.getState().messages).toEqual([]);
    expect(useRoomChatStore.getState().unreadCount).toBe(0);
    expect(useRoomChatStore.getState().roomId).toBe('room-b');
  });

  it('appendIncoming increments unread when panel is closed', () => {
    useRoomChatStore.getState().appendIncoming(msg('1'), false);
    useRoomChatStore.getState().appendIncoming(msg('2'), false);
    expect(useRoomChatStore.getState().unreadCount).toBe(2);
  });

  it('appendIncoming keeps unread at 0 when panel is open', () => {
    useRoomChatStore.getState().appendIncoming(msg('1'), true);
    useRoomChatStore.getState().appendIncoming(msg('2'), true);
    expect(useRoomChatStore.getState().unreadCount).toBe(0);
  });

  it('markRead resets unread count', () => {
    useRoomChatStore.getState().appendIncoming(msg('1'), false);
    useRoomChatStore.getState().appendIncoming(msg('2'), false);
    useRoomChatStore.getState().markRead();
    expect(useRoomChatStore.getState().unreadCount).toBe(0);
  });

  it('messages are capped at MAX_MESSAGES (200)', () => {
    for (let i = 0; i < 250; i++) {
      useRoomChatStore.getState().appendIncoming(msg(String(i)), true);
    }
    const s = useRoomChatStore.getState();
    expect(s.messages.length).toBe(200);
    // 최신 200개만 남음.
    expect(s.messages[0].eventId).toBe('50');
    expect(s.messages[199].eventId).toBe('249');
  });
});
