import { apiRequest } from './client';
import type { Room, RoomListResponse, TeamPolicy } from '@/types/api';

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
      opts?: { teamPolicy?: TeamPolicy; fillWithBots?: boolean }
  ): Promise<Room> {
    const body: Record<string, unknown> = { name, gameType };
    if (opts?.teamPolicy) body.teamPolicy = opts.teamPolicy;
    if (opts?.fillWithBots) body.fillWithBots = true;
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

  get(token: string, roomId: string): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}`, { token });
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
