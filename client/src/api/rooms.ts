import { apiRequest } from './client';
import type { Room, RoomListResponse } from '@/types/api';

export const roomsApi = {
  list(token: string, gameType?: string): Promise<RoomListResponse> {
    const qs = gameType ? `?gameType=${encodeURIComponent(gameType)}` : '';
    return apiRequest(`/api/rooms${qs}`, { token });
  },

  create(token: string, name: string, gameType: string): Promise<Room> {
    return apiRequest('/api/rooms', {
      method: 'POST',
      token,
      body: { name, gameType },
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
