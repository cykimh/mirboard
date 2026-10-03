import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginPage } from './LoginPage';
import { ApiError } from '@/api/client';
import { useAuthStore } from '@/features/auth/authStore';

/**
 * D-117 — 로그인 화면의 「게스트로 바로 체험하기」.
 *
 * <p>처음 온 리뷰어가 가입 화면에서 이탈하지 않게 하는 것이 목적이라, 실패 안내가 막다른
 * 길이 되면 안 된다 — 한도(429)·중단(503/403) 모두 회원가입으로 이어 준다.
 */

const { guest } = vi.hoisted(() => ({ guest: vi.fn() }));

vi.mock('@/api/auth', () => ({
  authApi: { guest, login: vi.fn(), register: vi.fn(), me: vi.fn() },
}));

function renderLogin() {
  render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/games" element={<div>GAMES_PAGE</div>} />
        <Route path="/register" element={<div>REGISTER_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

function guestButton() {
  return screen.getByRole('button', { name: '게스트로 바로 체험하기' });
}

describe('LoginPage — 게스트 체험 (D-117)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.setState({ token: null, expiresAt: null, user: null });
  });

  it('게스트 버튼과 안내 캡션을 보여 준다', () => {
    renderLogin();
    expect(guestButton()).toBeTruthy();
    expect(screen.getByText('가입 없이 12시간 · 전적은 랭킹에 오르지 않아요')).toBeTruthy();
  });

  it('누르면 게스트를 만들고 메인으로 간다', async () => {
    guest.mockResolvedValue({
      accessToken: 'guest-tok',
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 99, username: 'guest-abcd2345', guest: true },
    });
    renderLogin();

    fireEvent.click(guestButton());

    expect(await screen.findByText('GAMES_PAGE')).toBeTruthy();
    expect(guest).toHaveBeenCalledTimes(1);
    expect(useAuthStore.getState().user?.guest).toBe(true);
  });

  it('IP 하루 한도(429)면 그 사실과 회원가입 길을 안내한다', async () => {
    guest.mockRejectedValue(
      new ApiError(429, { error: { code: 'TOO_MANY_REQUESTS', message: 'x' } }),
    );
    renderLogin();

    fireEvent.click(guestButton());

    expect(
      await screen.findByText(/이 네트워크에서 오늘 만들 수 있는 게스트 수를 다 썼어요/),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole('link', { name: '회원가입하고 시작하기' }));
    expect(await screen.findByText('REGISTER_PAGE')).toBeTruthy();
  });

  it('전역 상한(503)이면 지금은 어렵다고 안내하고 회원가입으로 유도한다', async () => {
    guest.mockRejectedValue(
      new ApiError(503, { error: { code: 'GUEST_UNAVAILABLE', message: 'x' } }),
    );
    renderLogin();

    fireEvent.click(guestButton());

    expect(await screen.findByText(/지금은 게스트 입장이 어려워요/)).toBeTruthy();
    expect(screen.getByRole('link', { name: '회원가입하고 시작하기' })).toBeTruthy();
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('킬스위치(403 GUEST_DISABLED)도 같은 안내', async () => {
    guest.mockRejectedValue(
      new ApiError(403, { error: { code: 'GUEST_DISABLED', message: 'x' } }),
    );
    renderLogin();

    fireEvent.click(guestButton());

    expect(await screen.findByText(/지금은 게스트 입장이 어려워요/)).toBeTruthy();
  });
});
