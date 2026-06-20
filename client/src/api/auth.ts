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
};
