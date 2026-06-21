import { apiRequest } from './client';

/** D-85 — 본인 비밀번호 변경. 성공 시 204(void). 실패는 ApiError(BAD_CREDENTIALS/INVALID_INPUT). */
export const meApi = {
  changePassword(token: string, currentPassword: string, newPassword: string): Promise<void> {
    return apiRequest<void>('/api/me/password', {
      method: 'PUT',
      token,
      body: { currentPassword, newPassword },
    });
  },
};
