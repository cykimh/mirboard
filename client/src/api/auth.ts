import { apiRequest } from './client';
import type { LoginResponse, MeResponse } from '@/types/api';

export const authApi = {
  register(username: string, password: string): Promise<{ userId: number; username: string }> {
    return apiRequest('/api/auth/register', {
      method: 'POST',
      body: { username, password },
    });
  },

  login(username: string, password: string): Promise<LoginResponse> {
    return apiRequest('/api/auth/login', {
      method: 'POST',
      body: { username, password },
    });
  },

  me(token: string): Promise<MeResponse> {
    return apiRequest('/api/me', { token });
  },

  /** D-81 — 가상 칩 무료 충전(잔액<200 일 때만 500 으로). 갱신된 me 반환. */
  topUpChips(token: string): Promise<MeResponse> {
    return apiRequest('/api/me/chips/topup', { method: 'POST', token });
  },
};
