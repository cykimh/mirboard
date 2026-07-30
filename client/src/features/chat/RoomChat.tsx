import { FormEvent, MutableRefObject, useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/api/client';
import { chatApi } from '@/api/chat';
import { useAuthStore } from '@/features/auth/authStore';
import { useRoomChatStore } from './roomChatStore';

interface RoomChatProps {
  myUserId: number;
  sendChat: (message: string) => void;
  /** useStompRoom 의 chatPanelOpenRef — 안 읽은 카운트 계산용. */
  panelOpenRef: MutableRefObject<boolean>;
  onClose: () => void;
  /** D-93 — 신고 시 서버가 링버퍼를 찾을 방 범위. */
  roomId: string;
}

/**
 * 인-게임 채팅 패널. 보드 위에 떠 있는 반투명 fixed 오버레이(보드를 밀지 않음).
 */
export function RoomChat({ myUserId, sendChat, panelOpenRef, onClose, roomId }: RoomChatProps) {
  const messages = useRoomChatStore((s) => s.messages);
  const markRead = useRoomChatStore((s) => s.markRead);
  const token = useAuthStore((s) => s.token);
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  // D-93 — eventId → 신고 결과 문구. 낙관적 표시 없이 서버 응답만 반영한다.
  const [reportState, setReportState] = useState<Record<string, string>>({});

  async function handleReport(eventId: string) {
    if (!token || reportState[eventId] === '신고 중…') return;
    setReportState((s) => ({ ...s, [eventId]: '신고 중…' }));
    try {
      await chatApi.report(token, eventId, 'ROOM', roomId);
      setReportState((s) => ({ ...s, [eventId]: '신고됨' }));
    } catch (err) {
      const code = err instanceof ApiError ? err.code : '';
      const label =
        code === 'DUPLICATE_REPORT'
          ? '이미 신고함'
          : code === 'CHAT_MESSAGE_NOT_FOUND'
            ? '너무 오래된 메시지'
            : code === 'SELF_REPORT'
              ? '본인 메시지'
              : '신고 실패';
      setReportState((s) => ({ ...s, [eventId]: label }));
    }
  }

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
        {messages.map((m) => {
          const mine = m.userId === myUserId;
          const reported = reportState[m.eventId];
          return (
            <div
              key={m.eventId}
              className="room-chat-row"
              style={{
                alignSelf: mine ? 'flex-end' : 'flex-start',
                maxWidth: '90%',
              }}
            >
              <span style={{ opacity: 0.6, marginRight: 4, fontSize: 11 }}>
                {m.username}:
              </span>
              <span>{m.message}</span>
              {/* D-93 — 본인 메시지는 신고 불가(서버도 SELF_REPORT 로 거절). */}
              {!mine &&
                (reported ? (
                  <span className="room-chat-report-state">{reported}</span>
                ) : (
                  <button
                    type="button"
                    className="room-chat-report"
                    onClick={() => handleReport(m.eventId)}
                    aria-label={`${m.username} 의 메시지 신고`}
                    title="신고"
                  >
                    🚩
                  </button>
                ))}
            </div>
          );
        })}
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
