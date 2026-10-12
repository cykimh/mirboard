import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { useAuthStore } from '@/features/auth/authStore';

/**
 * D-133 — 초대 링크(`/rooms/<방ID>`)로 들어온 사람의 라우팅. 보호 경로가 `/login` 으로 보낼 때 가려던 경로를
 * `state.from` 에 싣고, 로그인 화면은 게스트 입장·로그인·가입 뒤 그 경로로 돌려보낸다. 실제 `App` 의 라우트
 * 구성(BrowserRouter)을 그대로 쓰고, 소켓·API 를 여는 화면만 스텁으로 바꾼다.
 */

const { guest, login, register } = vi.hoisted(() => ({
  guest: vi.fn(),
  login: vi.fn(),
  register: vi.fn(),
}));

vi.mock('@/api/auth', () => ({
  authApi: { guest, login, register, me: vi.fn() },
}));

vi.mock('@/pages/RoomPage', async () => {
  const router = await import('react-router-dom');
  return {
    RoomPage: () => {
      const { roomId } = router.useParams();
      const location = router.useLocation();
      return <div>{`ROOM_PAGE ${roomId}${location.search}`}</div>;
    },
  };
});
vi.mock('@/pages/GameHubPage', () => ({ GameHubPage: () => <div>GAMES_PAGE</div> }));
vi.mock('@/pages/ProfilePage', () => ({ ProfilePage: () => <div>PROFILE_PAGE</div> }));

const SESSION_RESPONSE = {
  accessToken: 'tok',
  tokenType: 'Bearer',
  expiresAt: Date.now() + 3_600_000,
};

function openAt(path: string) {
  window.history.replaceState(null, '', path);
  render(<App />);
}

describe('App — 초대 링크로 들어온 사람 (D-133)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.setState({ token: null, expiresAt: null, user: null });
  });

  it('로그인 안 된 채 방 링크를 열면 로그인 화면 → 게스트로 바로 입장 → 그 방(쿼리 포함)', async () => {
    guest.mockResolvedValue({
      ...SESSION_RESPONSE,
      user: { userId: 99, username: 'guest-abcd2345', guest: true },
    });
    openAt('/rooms/abc-123?from=kakao');

    expect(window.location.pathname).toBe('/login');
    expect(screen.getByText(/방에 초대받았어요/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '게스트로 바로 입장' }));

    expect(await screen.findByText('ROOM_PAGE abc-123?from=kakao')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/rooms/abc-123');
    expect(guest).toHaveBeenCalledTimes(1);
  });

  it('비밀번호로 로그인해도 그 방으로 간다', async () => {
    login.mockResolvedValue({ ...SESSION_RESPONSE, user: { userId: 3, username: 'alice' } });
    openAt('/rooms/abc-123');

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByText('ROOM_PAGE abc-123')).toBeInTheDocument();
  });

  it('회원가입으로 넘어가 가입해도 그 방으로 간다', async () => {
    register.mockResolvedValue({ userId: 5, username: 'newbie' });
    login.mockResolvedValue({ ...SESSION_RESPONSE, user: { userId: 5, username: 'newbie' } });
    openAt('/rooms/abc-123');

    fireEvent.click(screen.getByRole('link', { name: '회원가입' }));
    fireEvent.change(await screen.findByLabelText(/아이디 \(영문/), { target: { value: 'newbie' } });
    fireEvent.change(screen.getByLabelText(/비밀번호 \(8~64자\)/), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '회원가입 후 로그인' }));

    expect(await screen.findByText('ROOM_PAGE abc-123')).toBeInTheDocument();
    expect(register).toHaveBeenCalledWith('newbie', 'password1');
  });

  it('이미 로그인한 사람은 로그인 화면 없이 바로 그 방으로 들어간다', () => {
    useAuthStore.setState({
      token: 'tok',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 7, username: 'alice' },
    });
    openAt('/rooms/abc-123');

    expect(screen.getByText('ROOM_PAGE abc-123')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/rooms/abc-123');
  });

  it('방이 아닌 보호 경로(허브)로 왔으면 초대 안내 없이 로그인 뒤 그 경로로', async () => {
    login.mockResolvedValue({ ...SESSION_RESPONSE, user: { userId: 3, username: 'alice' } });
    openAt('/profile');

    expect(window.location.pathname).toBe('/login');
    expect(screen.queryByText(/방에 초대받았어요/)).toBeNull();
    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByText('PROFILE_PAGE')).toBeInTheDocument();
  });
});
