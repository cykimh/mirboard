import { fireEvent, render, screen } from '@testing-library/react';
import {
  MemoryRouter,
  Route,
  Routes,
  useNavigate,
  useNavigationType,
} from 'react-router-dom';
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

const { guest, login } = vi.hoisted(() => ({ guest: vi.fn(), login: vi.fn() }));

vi.mock('@/api/auth', () => ({
  authApi: { guest, login, register: vi.fn(), me: vi.fn() },
}));

/** 허브 자리 스텁 — 어떻게 왔는지(PUSH/REPLACE)와 허브의 로그아웃 동선을 흉내 낸다. */
function GamesStub() {
  const navigationType = useNavigationType();
  const navigate = useNavigate();
  const logout = useAuthStore((s) => s.logout);
  return (
    <>
      <div>GAMES_PAGE</div>
      <div data-testid="nav-type">{navigationType}</div>
      <button
        type="button"
        onClick={() => {
          logout();
          navigate('/login');
        }}
      >
        LOGOUT
      </button>
    </>
  );
}

function renderLogin(initial = '/login') {
  render(
    <MemoryRouter initialEntries={[initial]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/games" element={<GamesStub />} />
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

/**
 * D-122 — 로그인 상태의 `/login`. 게스트 입장 뒤 브라우저 '뒤로' 한 번이면 로그인 화면이 다시
 * 보였고, 거기서 게스트 버튼을 한 번 더 누르면 확인 없이 새 게스트가 발급돼 이전 게스트 신원
 * (비밀번호가 없어 복구 불가)을 잃었다. 허브 로그아웃에만 건 확인(D-117)을 우회하는 경로였다.
 */
describe('LoginPage — 로그인 상태 리다이렉트 (D-122)', () => {
  const SESSION = {
    token: 'tok',
    expiresAt: Date.now() + 3_600_000,
    user: { userId: 7, username: 'guest-abcd2345', guest: true },
  };

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.setState({ token: null, expiresAt: null, user: null });
  });

  it('토큰이 있으면 /login 대신 허브로 보낸다 (replace — 뒤로가기로 되돌아오지 않게)', async () => {
    useAuthStore.setState(SESSION);
    renderLogin();

    expect(await screen.findByText('GAMES_PAGE')).toBeTruthy();
    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
    expect(screen.queryByRole('button', { name: '게스트로 바로 체험하기' })).toBeNull();
  });

  it('게스트 입장 성공 뒤 이동은 replace 다', async () => {
    guest.mockResolvedValue({
      accessToken: 'guest-tok',
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 99, username: 'guest-abcd2345', guest: true },
    });
    renderLogin();

    fireEvent.click(guestButton());

    expect(await screen.findByText('GAMES_PAGE')).toBeTruthy();
    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
  });

  it('비밀번호 로그인 성공 뒤 이동도 replace 다', async () => {
    login.mockResolvedValue({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 3, username: 'alice' },
    });
    renderLogin();

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByText('GAMES_PAGE')).toBeTruthy();
    expect(login).toHaveBeenCalledWith('alice', 'password1');
    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
  });

  it('허브에서 로그아웃하면 토큰이 지워져 /login 이 그대로 보인다', async () => {
    useAuthStore.setState(SESSION);
    renderLogin('/games');

    fireEvent.click(await screen.findByRole('button', { name: 'LOGOUT' }));

    expect(await screen.findByRole('button', { name: '게스트로 바로 체험하기' })).toBeTruthy();
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('만료된 토큰이면 보내지 않는다 — 다시 로그인할 길이 막히면 안 된다', () => {
    useAuthStore.setState({ ...SESSION, expiresAt: Date.now() - 1 });
    renderLogin();

    expect(guestButton()).toBeTruthy();
  });

  it('토큰만 있고 사용자 정보가 없으면 보내지 않는다 — 허브의 /login 가드와 왕복하지 않게', () => {
    useAuthStore.setState({ token: 'tok', expiresAt: null, user: null });
    renderLogin();

    expect(guestButton()).toBeTruthy();
  });
});
