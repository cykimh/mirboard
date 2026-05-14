import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from './authStore';

vi.mock('@/api/auth', () => ({
  authApi: {
    register: vi.fn().mockResolvedValue({ userId: 1, username: 'alice' }),
    login: vi.fn().mockResolvedValue({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 1, username: 'alice' },
    }),
    me: vi.fn(),
  },
}));

describe('authStore', () => {
  beforeEach(() => {
    localStorage.clear();
    useAuthStore.setState({ token: null, expiresAt: null, user: null });
  });

  it('login persists token to storage', async () => {
    await useAuthStore.getState().login('alice', 'pw12345678');
    expect(useAuthStore.getState().token).toBe('tok');
    expect(localStorage.getItem('mirboard.auth')).toContain('"token":"tok"');
  });

  it('logout clears storage', async () => {
    await useAuthStore.getState().login('alice', 'pw12345678');
    useAuthStore.getState().logout();
    expect(useAuthStore.getState().token).toBeNull();
    expect(localStorage.getItem('mirboard.auth')).toBeNull();
  });

  it('loadFromStorage restores valid token', () => {
    localStorage.setItem(
      'mirboard.auth',
      JSON.stringify({
        token: 'persisted',
        expiresAt: Date.now() + 60_000,
        user: { userId: 7, username: 'bob' },
      }),
    );
    useAuthStore.getState().loadFromStorage();
    expect(useAuthStore.getState().token).toBe('persisted');
    expect(useAuthStore.getState().user?.username).toBe('bob');
  });

  it('loadFromStorage discards expired token', () => {
    localStorage.setItem(
      'mirboard.auth',
      JSON.stringify({
        token: 'old',
        expiresAt: Date.now() - 1000,
        user: { userId: 7, username: 'bob' },
      }),
    );
    useAuthStore.getState().loadFromStorage();
    expect(useAuthStore.getState().token).toBeNull();
  });
});
