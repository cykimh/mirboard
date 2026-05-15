import { FormEvent, MutableRefObject, useEffect, useRef, useState } from 'react';
import { Button } from '@/components/Button';
import { useRoomChatStore } from './roomChatStore';

interface RoomChatProps {
  myUserId: number;
  sendChat: (message: string) => void;
  /** useStompRoom 의 chatPanelOpenRef — 안 읽은 카운트 계산용. */
  panelOpenRef: MutableRefObject<boolean>;
}

/**
 * Phase 8B — 인-게임 채팅 패널. 우측 사이드 컬럼 (데스크탑) 으로 노출하기 위해
 * GameTable 옆에 고정 폭으로 배치한다. 별도 모바일 슬라이드업은 8E (보드 풍
 * 레이아웃) 에서 처리.
 */
export function RoomChat({ myUserId, sendChat, panelOpenRef }: RoomChatProps) {
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
    <aside
      className="room-chat-panel"
      style={{
        width: 260,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        background: 'var(--color-surface, #1a1a1f)',
        border: '1px solid var(--color-border, #2a2a32)',
        borderRadius: 8,
        padding: 12,
        height: 'fit-content',
        maxHeight: 480,
      }}
    >
      <header style={{ fontSize: 13, fontWeight: 600, opacity: 0.85 }}>채팅</header>
      <div
        ref={listRef}
        style={{
          flex: 1,
          overflowY: 'auto',
          minHeight: 240,
          fontSize: 13,
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
        }}
      >
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
      <form onSubmit={handleSubmit} style={{ display: 'flex', gap: 4 }}>
        <input
          type="text"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          maxLength={500}
          placeholder="메시지 입력..."
          style={{ flex: 1, padding: '4px 8px', fontSize: 13 }}
        />
        <Button type="submit" variant="primary">전송</Button>
      </form>
    </aside>
  );
}
