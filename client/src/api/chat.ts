import { apiRequest } from './client';

/** D-93 — 신고 대상 범위. ROOM 이면 roomId 필수. */
export type ChatScope = 'LOBBY' | 'ROOM';

export interface ChatReportResponse {
  reportId: number;
  eventId: string;
}

/** D-93 — 어드민 조회 항목 (서버 `AdminController.ChatReportView` 미러). */
export interface AdminChatReport {
  reportId: number;
  eventId: string;
  scope: ChatScope;
  roomId: string | null;
  reportedUserId: number;
  reporterUserId: number;
  message: string;
  messageAt: number;
  createdAt: number;
  /** 피신고자가 받은 누적 신고 수 — 정지 판단용. */
  totalAgainstReported: number;
}

export const chatApi = {
  /**
   * 채팅 메시지 신고. **본문은 보내지 않는다** — eventId 만 지목하고 원문은 서버가
   * 자기 보관분(Redis 링버퍼)에서 확정한다(무고 방지, D-93).
   *
   * 에러: `CHAT_MESSAGE_NOT_FOUND`(404, 너무 오래된 메시지) · `SELF_REPORT`(400) ·
   * `DUPLICATE_REPORT`(409, 이미 신고함).
   */
  report(
    token: string,
    eventId: string,
    scope: ChatScope,
    roomId?: string,
  ): Promise<ChatReportResponse> {
    return apiRequest('/api/chat/reports', {
      method: 'POST',
      token,
      body: { eventId, scope, roomId },
    });
  },

  /** 어드민 전용 — 신고 목록(최신순). */
  adminList(token: string, limit = 50): Promise<{ reports: AdminChatReport[] }> {
    return apiRequest(`/api/admin/chat-reports?limit=${limit}`, { token });
  },
};
