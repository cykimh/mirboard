import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigationType } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RegisterPage } from './RegisterPage';
import { useAuthStore } from '@/features/auth/authStore';

/**
 * D-133 — 가입 뒤 목적지. 로그인 화면의 "회원가입" 링크가 넘긴 `state.from`(초대받은 방 등)으로 가입·로그인 뒤
 * `replace` 한다. 규칙은 로그인 화면과 같다 — 사이트 안 경로만(`safeReturnPath`), 아니면 허브.
 */

const { login, register } = vi.hoisted(() => ({ login: vi.fn(), register: vi.fn() }));

vi.mock('@/api/auth', () => ({
  authApi: { guest: vi.fn(), login, register, me: vi.fn() },
}));

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

function renderRegister(from?: unknown) {
  render(
    <MemoryRouter initialEntries={[{ pathname: '/register', state: from === undefined ? null : { from } }]}>
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/login" element={<Landing name="LOGIN_PAGE" />} />
        <Route path="/games" element={<Landing name="GAMES_PAGE" />} />
        <Route path="/rooms/:roomId" element={<Landing name="ROOM_PAGE" />} />
      </Routes>
    </MemoryRouter>,
  );
}

function submitSignup() {
  fireEvent.change(screen.getByLabelText(/별명 \(한글/), { target: { value: '새내기' } });
  fireEvent.change(screen.getByLabelText(/비밀번호 \(4자 이상\)/), { target: { value: 'password1' } });
  fireEvent.click(screen.getByRole('button', { name: '회원가입 후 로그인' }));
}

describe('RegisterPage — 가입 뒤 목적지 (D-133)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    useAuthStore.setState({ token: null, expiresAt: null, user: null });
    register.mockResolvedValue({ userId: 5, username: 'newbie' });
    login.mockResolvedValue({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresAt: Date.now() + 3_600_000,
      user: { userId: 5, username: 'newbie' },
    });
  });

  it('초대받은 방에서 왔으면 가입 뒤 그 방으로 replace 한다', async () => {
    renderRegister('/rooms/r1');

    submitSignup();

    expect(await screen.findByText('ROOM_PAGE /rooms/r1')).toBeInTheDocument();
    expect(screen.getByTestId('nav-type').textContent).toBe('REPLACE');
    expect(register).toHaveBeenCalledWith('새내기', 'password1');
    expect(login).toHaveBeenCalledWith('새내기', 'password1');
  });

  it('돌아갈 주소가 없으면 지금처럼 허브로', async () => {
    renderRegister();

    submitSignup();

    expect(await screen.findByText('GAMES_PAGE /games')).toBeInTheDocument();
  });

  it.each(['//evil.com', 'https://evil.com/rooms/r1', '/login'])(
    '돌아갈 주소가 %j 이면 허브로 — 사이트 밖·로그인 화면으로 가지 않는다',
    async (from) => {
      renderRegister(from);

      submitSignup();

      expect(await screen.findByText('GAMES_PAGE /games')).toBeInTheDocument();
    },
  );

  it('"로그인" 링크로 돌아가도 돌아갈 주소를 넘긴다 — 초대 안내가 사라지지 않게', async () => {
    renderRegister('/rooms/r1');

    fireEvent.click(screen.getByRole('link', { name: '로그인' }));

    expect(await screen.findByText('LOGIN_PAGE /login')).toBeInTheDocument();
    expect(screen.getByTestId('state-from').textContent).toBe('/rooms/r1');
  });
});

describe('RegisterPage — 별명 (D-149)', () => {
  it('별명 칸은 한글·영문·숫자 2~10자를 받는다', () => {
    render(
      <MemoryRouter initialEntries={['/register']}>
        <RegisterPage />
      </MemoryRouter>,
    );
    const input = screen.getByLabelText(/별명 \(한글·영문·숫자 2~10자\)/) as HTMLInputElement;
    const re = new RegExp(input.pattern);
    expect(re.test('철수')).toBe(true);
    expect(re.test('영희Kim1')).toBe(true);
    expect(re.test('가')).toBe(false);
    expect(re.test('alice_01')).toBe(false);
    expect(re.test('가나다라마바사아자차카')).toBe(false);
    expect(screen.queryByText(/아이디/)).toBeNull();
  });
});

describe('RegisterPage — 비밀번호 (D-156)', () => {
  it('비밀번호는 4자 이상이면 무엇이든 — 칸의 최소 길이가 4다', () => {
    render(
      <MemoryRouter initialEntries={['/register']}>
        <RegisterPage />
      </MemoryRouter>,
    );
    const pw = screen.getByLabelText('비밀번호 (4자 이상)') as HTMLInputElement;
    expect(pw.minLength).toBe(4);
    expect(pw.pattern).toBe('');
  });
});
