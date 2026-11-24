import { apiRequest } from './client';

/** D-85·D-304 — 본인 비밀번호 변경(현재 비밀번호는 묻지 않는다). 성공 시 204(void). 실패는 ApiError(INVALID_INPUT/GUEST_FORBIDDEN). */
export const meApi = {
  changePassword(token: string, newPassword: string): Promise<void> {
    return apiRequest<void>('/api/me/password', {
      method: 'PUT',
      token,
      body: { newPassword },
    });
  },
};
