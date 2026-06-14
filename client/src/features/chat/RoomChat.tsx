import { FormEvent, MutableRefObject, useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { useRoomChatStore } from './roomChatStore';

interface RoomChatProps {
  myUserId: number;
  sendChat: (message: string) => void;
  /** useStompRoom 의 chatPanelOpenRef — 안 읽은 카운트 계산용. */
  panelOpenRef: MutableRefObject<boolean>;
  onClose: () => void;
}

/**
 * 인-게임 채팅 패널. 보드 위에 떠 있는 반투명 fixed 오버레이(보드를 밀지 않음).
 */
export function RoomChat({ myUserId, sendChat, panelOpenRef, onClose }: RoomChatProps) {
  const messages = useRoomChatStore((s) => s.messages);
  const markRead = useRoomChatStore((s) => s.markRead);
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  // 패널이 마운트되어 있는 동안에는 panelOpen=true → 들어오는 메시지가 unread 로
  // 안 잡힘. unmount 시 false 로 복귀.
  useEffect(() => {
    panelOpenRef.current = true;
    markRead();
    return () => {
      panelOpenRef.current = false;
    };
  }, [panelOpenRef, markRead]);

  // 메시지 도착 시 자동 스크롤.
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
    markRead();
  }, [messages, markRead]);

  function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!draft.trim()) return;
    sendChat(draft);
    setDraft('');
  }

  return (
    <aside className="room-chat-panel">
      <header className="room-chat-header">
        <span>채팅</span>
        <button
          type="button"
          className="room-chat-close"
          onClick={onClose}
          aria-label="채팅 닫기"
        >
          ✕
        </button>
      </header>
      <div ref={listRef} className="room-chat-list">
        {messages.length === 0 && (
          <p style={{ opacity: 0.5, fontStyle: 'italic' }}>아직 메시지가 없습니다.</p>
        )}
        {messages.map((m) => (
          <div
            key={m.eventId}
            style={{
              alignSelf: m.userId === myUserId ? 'flex-end' : 'flex-start',
              maxWidth: '90%',
            }}
          >
            <span style={{ opacity: 0.6, marginRight: 4, fontSize: 11 }}>
              {m.username}:
            </span>
            <span>{m.message}</span>
          </div>
        ))}
      </div>
      <form onSubmit={handleSubmit} className="room-chat-form">
        <input
          type="text"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          maxLength={500}
          placeholder="메시지 입력..."
        />
        <Button type="submit" size="sm">전송</Button>
      </form>
    </aside>
  );
}
