import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resetIsAdminForTest, useIsAdmin } from './useIsAdmin';
import { useAuthStore } from './authStore';

/** D-265 — 관리자 여부는 `GET /api/me` 의 `admin`. 게스트·비로그인은 묻지 않는다. */

const me = vi.hoisted(() => vi.fn());
vi.mock('@/api/auth', () => ({ authApi: { me } }));

describe('useIsAdmin (D-265)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetIsAdminForTest();
  });

  it('admin 이 true 면 관리자 — 같은 토큰은 한 번만 묻는다', async () => {
    me.mockResolvedValue({ userId: 1, username: '운영자', winCount: 0, loseCount: 0, admin: true });
    useAuthStore.setState({ token: 'tok-a', user: { userId: 1, username: '운영자' } as never });

    const first = renderHook(() => useIsAdmin());
    expect(first.result.current).toBeNull();
    await waitFor(() => expect(first.result.current).toBe(true));

    const second = renderHook(() => useIsAdmin());
    await waitFor(() => expect(second.result.current).toBe(true));
    expect(me).toHaveBeenCalledTimes(1);
  });

  it('admin 이 없거나(예전 서버) 요청이 실패하면 관리자가 아니다', async () => {
    me.mockResolvedValueOnce({ userId: 2, username: 'u', winCount: 0, loseCount: 0 });
    useAuthStore.setState({ token: 'tok-b', user: { userId: 2, username: 'u' } as never });
    const plain = renderHook(() => useIsAdmin());
    await waitFor(() => expect(plain.result.current).toBe(false));

    me.mockRejectedValueOnce(new Error('offline'));
    useAuthStore.setState({ token: 'tok-c', user: { userId: 3, username: 'v' } as never });
    const failed = renderHook(() => useIsAdmin());
    await waitFor(() => expect(failed.result.current).toBe(false));
  });

  it('게스트·비로그인은 묻지 않고 관리자가 아니다', () => {
    useAuthStore.setState({ token: 'tok-g', user: { userId: 9, username: 'guest-1', guest: true } as never });
    expect(renderHook(() => useIsAdmin()).result.current).toBe(false);

    useAuthStore.setState({ token: null, user: null });
    expect(renderHook(() => useIsAdmin()).result.current).toBe(false);
    expect(me).not.toHaveBeenCalled();
  });
});
