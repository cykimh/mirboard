import { fireEvent, render, screen } from '@testing-library/react';
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation,
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

/**
 * D-133 — 초대 링크로 온 사람. 보호 경로가 `state.from` 에 가려던 경로를 싣고 보낸다. 방 경로(`/rooms/…`)면
 * "방에 초대받았어요" 안내와 "게스트로 바로 입장"을 로그인 폼 **앞**에 두고, 게스트·로그인 뒤(그리고 이미
 * 로그인 상태로 왔을 때) 그 경로로 `replace` 한다. 돌아갈 주소는 사이트 안 경로만(`safeReturnPath`).
 */
describe('LoginPage — 초대 링크로 온 사람 (D-133)', () => {
  /** 도착한 화면이 어떻게 왔는지(PUSH/REPLACE)와 넘겨받은 state.from 을 보인다. */
  function Landing({ name }: { name: string }) {
    const navigationType = useNavigationType();
    const location = useLocation();
    const from = (location.state as { from?: unknown } | null)?.from;
    return (
      <>
        <div>{`${name} ${location.pathname}`}</div>
        <div data-testid="nav-type">{navigationType}</div>
        <div data-testid="state-from">{String(from)}</div>
      </>
    );
  }

  function renderWithFrom(from: unknown) {
    render(
      <MemoryRouter initialEntries={[{ pathname: '/login', state: { from } }]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/games" element={<Landing name="GAMES_PAGE" />} />
          <Route path="/profile" element={<Landing name="PROFILE_PAGE" />} />
          <Route path="/rooms/:roomId" element={<Landing name="ROOM_PAGE" />} />
          <Route path="/register" element={<Landing name="REGISTER_PAGE" />} />
        </Routes>
      </MemoryRouter>,
    );
  }

  const GUEST_SESSION = {
    accessToken: 'guest-tok',
    tokenType: 'Bearer',
    expiresAt: Date.now() + 3_600_000,
    user: { userId: 99, username: 'guest-abcd2345', guest: true },
  };

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.setState({ token: null, expiresAt: null, user: null });
  });

  it('방 경로에서 왔으면 초대 안내와 "게스트로 바로 입장"을 로그인 폼보다 앞에 둔다', () => {
    renderWithFrom('/rooms/r1');

    const notice = screen.getByText('방에 초대받았어요 — 가입하지 않아도 게스트로 바로 들어갈 수 있어요');
    const guestCta = screen.getByRole('button', { name: '게스트로 바로 입장' });
    const username = screen.getByLabelText('아이디');
    const follows = (a: Node, b: Node) =>
      (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;

    expect(follows(notice, guestCta)).toBe(true);
    expect(follows(guestCta, username)).toBe(true);
    expect(screen.queryByRole('button', { name: '게스트로 바로 체험하기' })).toBeNull();
  });

  it('일반 진입이면 지금 배치 그대로 — 안내가 없고 게스트 버튼은 폼 뒤', () => {
    renderLogin();

    expect(screen.queryByText(/방에 초대받았어요/)).toBeNull();
    const username = screen.getByLabelText('아이디');
    expect(
      username.compareDocumentPosition(guestButton()) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: '게스트로 바로 입장' })).toBeNull();
  });

  it('방이 아닌 보호 경로(/profile)에서 왔으면 배치는 그대로, 로그인 뒤 그 경로로', async () => {
    login.mockResolvedValue({ ...GUEST_SESSION, user: { userId: 3, username: 'alice' } });
    renderWithFrom('/profile');

    expect(screen.queryByText(/방에 초대받았어요/)).toBeNull();
    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByText('PROFILE_PAGE /profile')).toBeInTheDocument();
  });

  it('게스트로 바로 입장하면 그 방으로 replace 한다', async () => {
    guest.mockResolvedValue(GUEST_SESSION);
    renderWithFrom('/rooms/r1');

    fireEvent.click(screen.getByRole('button', { name: '게스트로 바로 입장' }));

    expect(await screen.findByText('ROOM_PAGE /rooms/r1')).toBeInTheDocument();
    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
  });

  it('비밀번호로 로그인해도 그 방으로 replace 한다', async () => {
    login.mockResolvedValue({ ...GUEST_SESSION, user: { userId: 3, username: 'alice' } });
    renderWithFrom('/rooms/r1');

    fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password1' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByText('ROOM_PAGE /rooms/r1')).toBeInTheDocument();
    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
  });

  it('이미 로그인 상태로 /login(from 있음)에 오면 로그인 화면 없이 그 방으로', async () => {
    useAuthStore.setState({
      token: 'tok',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 7, username: 'alice' },
    });
    renderWithFrom('/rooms/r1');

    expect(await screen.findByText('ROOM_PAGE /rooms/r1')).toBeInTheDocument();
    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
  });

  it.each(['//evil.com', '/\\evil.com', 'https://evil.com/rooms/r1', 'javascript:alert(1)', '/login'])(
    'from 이 %j 이면 안내 없이 로그인 뒤 허브로 — 사이트 밖·로그인 화면으로 가지 않는다',
    async (from) => {
      guest.mockResolvedValue(GUEST_SESSION);
      renderWithFrom(from);

      expect(screen.queryByText(/방에 초대받았어요/)).toBeNull();
      fireEvent.click(guestButton());

      expect(await screen.findByText('GAMES_PAGE /games')).toBeInTheDocument();
    },
  );

  it('"회원가입" 링크가 돌아갈 주소를 넘긴다', async () => {
    renderWithFrom('/rooms/r1');

    fireEvent.click(screen.getByRole('link', { name: '회원가입' }));

    expect(await screen.findByText('REGISTER_PAGE /register')).toBeInTheDocument();
    expect(screen.getByTestId('state-from').textContent).toBe('/rooms/r1');
  });

  it('게스트 생성 실패 안내의 회원가입 길도 돌아갈 주소를 넘긴다', async () => {
    guest.mockRejectedValue(
      new ApiError(429, { error: { code: 'TOO_MANY_REQUESTS', message: 'x' } }),
    );
    renderWithFrom('/rooms/r1');

    fireEvent.click(screen.getByRole('button', { name: '게스트로 바로 입장' }));
    fireEvent.click(await screen.findByRole('link', { name: '회원가입하고 시작하기' }));

    expect(await screen.findByText('REGISTER_PAGE /register')).toBeInTheDocument();
    expect(screen.getByTestId('state-from').textContent).toBe('/rooms/r1');
  });
});
