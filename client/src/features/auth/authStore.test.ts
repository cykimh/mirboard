import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from './authStore';

vi.mock('@/api/auth', () => ({
  authApi: {
    register: vi.fn().mockResolvedValue({ userId: 1, username: 'alice' }),
    login: vi.fn().mockResolvedValue({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 1, username: 'alice', guest: false },
    }),
    guest: vi.fn().mockResolvedValue({
      accessToken: 'guest-tok',
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 99, username: 'guest-abcd2345', guest: true },
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

  it('loadFromStorage discards expired token + clears storage', () => {
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
    expect(localStorage.getItem('mirboard.auth')).toBeNull();
  });

  it('loadFromStorage ignores corrupt JSON without throwing', () => {
    localStorage.setItem('mirboard.auth', '{not valid json');
    expect(() => useAuthStore.getState().loadFromStorage()).not.toThrow();
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('loadFromStorage is a no-op when nothing is stored', () => {
    useAuthStore.getState().loadFromStorage();
    expect(useAuthStore.getState().token).toBeNull();
  });

  // D-117 — 게스트 체험. 게스트 여부는 서버 응답(user.guest)을 그대로 영속한다 —
  // 클라가 username 접두로 다시 판별하면 규약이 두 군데가 된다.
  it('loginAsGuest persists the guest flag and loadFromStorage restores it', async () => {
    await useAuthStore.getState().loginAsGuest();
    expect(useAuthStore.getState().token).toBe('guest-tok');
    expect(useAuthStore.getState().user?.guest).toBe(true);
    expect(localStorage.getItem('mirboard.auth')).toContain('"guest":true');

    useAuthStore.setState({ token: null, expiresAt: null, user: null });
    useAuthStore.getState().loadFromStorage();
    expect(useAuthStore.getState().token).toBe('guest-tok');
    expect(useAuthStore.getState().user?.guest).toBe(true);
  });

  it('a snapshot saved before D-117 (no guest field) restores as a member', () => {
    localStorage.setItem(
      'mirboard.auth',
      JSON.stringify({
        token: 'persisted',
        expiresAt: Date.now() + 60_000,
        user: { userId: 7, username: 'bob' },
      }),
    );
    useAuthStore.getState().loadFromStorage();
    expect(useAuthStore.getState().user?.guest).toBeFalsy();
  });

  it('member login stores guest=false', async () => {
    await useAuthStore.getState().login('alice', 'pw12345678');
    expect(useAuthStore.getState().user?.guest).toBe(false);
  });
});
