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
      opts?: {
        teamPolicy?: TeamPolicy;
        fillWithBots?: boolean;
        targetScore?: number;
        turnSeconds?: number;
        stake?: number;
      },
  ): Promise<Room> {
    const body: Record<string, unknown> = { name, gameType };
    if (opts?.teamPolicy) body.teamPolicy = opts.teamPolicy;
    if (opts?.fillWithBots) body.fillWithBots = true;
    if (opts?.targetScore) body.targetScore = opts.targetScore;
    if (opts?.turnSeconds != null) body.turnSeconds = opts.turnSeconds;
    if (opts?.stake != null) body.stake = opts.stake;
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

  /** Phase 16(#2) — 대기실 준비 토글. 전원 준비되면 서버가 게임 시작. */
  setReady(token: string, roomId: string, ready: boolean): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/ready`, {
      method: 'POST',
      token,
      body: { ready },
    });
  },

  join(token: string, roomId: string): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/join`, {
      method: 'POST',
      token,
    });
  },

  /** D-82 — 호스트가 매치 종료 후 같은 테이블에서 '한 판 더'(리매치). 칩 누적. */
  rematch(token: string, roomId: string): Promise<Room> {
    return apiRequest(`/api/rooms/${encodeURIComponent(roomId)}/rematch`, {
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
