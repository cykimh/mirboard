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

  /**
   * D-117 — 가입 없는 체험. 서버가 방문자마다 일회용 게스트를 만들고 로그인 응답을 준다.
   * 본문은 비어 있어도 되지만 서버가 `Content-Type: application/json` 을 요구하므로 `{}` 를 싣는다.
   * 토큰은 싣지 않는다(인증 경로는 어차피 IP 단위 한도).
   */
  guest(): Promise<LoginResponse> {
    return apiRequest('/api/auth/guest', { method: 'POST', body: {} });
  },

  me(token: string): Promise<MeResponse> {
    return apiRequest('/api/me', { token });
  },
};
