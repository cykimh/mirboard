import { apiRequest } from './client';
import type { Room, RoomListResponse, RoomSettingsUpdate, TeamPolicy } from '@/types/api';

export interface JoinOrReconnectResponse {
  mode: 'JOINED' | 'RECONNECTED' | 'SPECTATING';
  room: Room;
}

export const roomsApi = {
  list(token: string, gameType?: string): Promise<RoomListResponse> {
    const qs = gameType ? `?gameType=${encodeURIComponent(gameType)}` : '';
    return apiRequest(`/api/rooms${qs}`, { token });
  },

  create(
      token: string,
      name: string,
      gameType: string,
      opts?: {
        teamPolicy?: TeamPolicy;
        fillWithBots?: boolean;
        targetScore?: number;
        turnSeconds?: number;
        stake?: number;
        /** D-99 — 방 인원. 생략하면 서버가 GameDefinition.maxPlayers() 로 결정. */
        capacity?: number;
      },
  ): Promise<Room> {
    const body: Record<string, unknown> = { name, gameType };
    if (opts?.teamPolicy) body.teamPolicy = opts.teamPolicy;
    if (opts?.fillWithBots) body.fillWithBots = true;
    if (opts?.targetScore) body.targetScore = opts.targetScore;
    if (opts?.turnSeconds != null) body.turnSeconds = opts.turnSeconds;
    if (opts?.stake != null) body.stake = opts.stake;
    if (opts?.capacity != null) body.capacity = opts.capacity;
    return apiRequest('/api/rooms', { method: 'POST', token, body });
  },

  /** Phase 8C — WAITING 방에서 호스트만 호출 가능. */
  updateTeamPolicy(token: string, roomId: string, teamPolicy: TeamPolicy): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/team-policy`, {
      method: 'PUT',
      token,
      body: { teamPolicy },
    });
  },

  /** D-161 — 대기실에서 방장이 게임 설정을 바꾼다(부분 갱신). 응답은 갱신된 방 — 다른 사람은 방 메타 갱신으로 받는다. */
  updateSettings(token: string, roomId: string, settings: RoomSettingsUpdate): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/settings`, {
      method: 'PUT',
      token,
      body: settings,
    });
  },

  /** D-162 — 방장이 게임 중 끊긴 사람을 내보낸다(바로 탈주 처리). 응답 본문 없음. */
  kick(token: string, roomId: string, userId: number): Promise<void> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/kick`, {
      method: 'POST',
      token,
      body: { userId },
    });
  },

  get(token: string, roomId: string): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}`, { token });
  },

  /** Phase 16(#2) — 대기실 준비 토글. D-161 — 친구 방은 표시만(시작은 방장), 봇과 연습 방만 전원 준비로 시작. */
  setReady(token: string, roomId: string, ready: boolean): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/ready`, {
      method: 'POST',
      token,
      body: { ready },
    });
  },

  /** D-136 — 친구와 하기 대기실에서 방장이 "지금 N명으로 시작"(정원을 현재 인원으로 줄이고 시작). 응답은 시작된 방. */
  startNow(token: string, roomId: string): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/start`, { method: 'POST', token });
  },

  join(token: string, roomId: string): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/join`, {
      method: 'POST',
      token,
    });
  },


  /** Phase 8A — 직접 링크 진입 자동 분기 (JOINED / RECONNECTED / SPECTATING). */
  joinOrReconnect(token: string, roomId: string): Promise<JoinOrReconnectResponse> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/join-or-reconnect`, {
      method: 'POST',
      token,
    });
  },

  /** Phase 8A — 호스트가 진행 중인 게임을 강제 종료. */
  abort(token: string, roomId: string): Promise<void> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/abort`, {
      method: 'POST',
      token,
    });
  },

  leave(token: string, roomId: string): Promise<void> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/leave`, {
      method: 'POST',
      token,
    });
  },

  spectate(token: string, roomId: string): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/spectate`, {
      method: 'POST',
      token,
    });
  },

  stopSpectating(token: string, roomId: string): Promise<void> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/spectate`, {
      method: 'DELETE',
      token,
    });
  },

  resync<T = unknown>(token: string, roomId: string): Promise<T> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/resync`, { token });
  },
};
