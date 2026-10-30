import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfilePage } from './ProfilePage';
import { useAuthStore } from '@/features/auth/authStore';

const { stats, catalog, headToHead } = vi.hoisted(() => ({
  stats: vi.fn(),
  catalog: vi.fn(),
  headToHead: vi.fn(() => Promise.resolve({ entries: [] as unknown[] })),
}));

vi.mock('@/api/users', () => ({ usersApi: { stats, headToHead } }));
vi.mock('@/api/games', () => ({ gamesApi: { catalog } }));
vi.mock('@/features/profile/AvatarSettingsModal', () => ({
  AvatarSettingsModal: ({ open }: { open: boolean }) => (open ? <div role="dialog">AVATAR_DIALOG</div> : null),
}));

function renderProfile() {
  return render(
    <MemoryRouter>
      <ProfilePage />
    </MemoryRouter>,
  );
}

/** D-154 — 프로필 이미지는 내 프로필에서 바꾼다(메인 위쪽 아바타는 프로필로 가는 버튼). 게스트는 못 바꾼다. */
describe('ProfilePage — 프로필 이미지 (D-154)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    stats.mockResolvedValue({ userId: 1, username: '철수', games: [] });
    catalog.mockResolvedValue({ games: [] });
  });

  it('회원은 [프로필 이미지 바꾸기]로 설정 창을 연다', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '철수' } as never });
    renderProfile();

    fireEvent.click(await screen.findByRole('button', { name: '프로필 이미지 바꾸기' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('AVATAR_DIALOG');
  });

  it('게스트는 버튼이 잠겨 있다', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 2, username: 'guest-ab12cd34', guest: true } as never });
    renderProfile();

    expect(await screen.findByRole('button', { name: '프로필 이미지 바꾸기' })).toBeDisabled();
  });
});

/** D-154 — 상대 전적: 게임별로 상대마다 승·패(무가 있으면 무). 게스트·봇은 서버가 뺀다. */
describe('ProfilePage — 상대 전적 (D-154)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    stats.mockResolvedValue({ userId: 1, username: '철수', games: [] });
    catalog.mockResolvedValue({ games: [{ id: 'ONE_CARD', displayName: '원카드' }, { id: 'SKULL_KING', displayName: '스컬킹' }] });
    headToHead.mockResolvedValue({ entries: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '철수' } as never });
  });

  it('게임별로 상대마다 전적을 보인다', async () => {
    headToHead.mockResolvedValue({
      entries: [
        { gameType: 'ONE_CARD', opponentId: 2, opponentName: '영희', wins: 3, losses: 2, draws: 0 },
        { gameType: 'SKULL_KING', opponentId: 3, opponentName: '민수', wins: 1, losses: 0, draws: 1 },
      ],
    });
    renderProfile();

    const section = await screen.findByRole('region', { name: '상대 전적' });
    expect(await within(section).findByText('영희')).toBeInTheDocument();
    expect(within(section).getByText('3승 2패')).toBeInTheDocument();
    expect(within(section).getByText('1승 0패 1무')).toBeInTheDocument();
    expect(within(section).getByText('원카드')).toBeInTheDocument();
    expect(headToHead).toHaveBeenCalledWith('tok', 1);
  });

  it('아직 없으면 안내', async () => {
    renderProfile();
    expect(await screen.findByText('아직 함께한 사람이 없어요')).toBeInTheDocument();
  });
});
