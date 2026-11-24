import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProfilePage } from './ProfilePage';
import { useAuthStore } from '@/features/auth/authStore';
import { useToastStore } from '@/components/toast/toastStore';

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

/** D-154 — 프로필 이미지는 내 프로필에서 바꾼다(메인 위쪽 아바타는 프로필로 가는 버튼). D-303 — 아바타를 눌러서만. 게스트는 못 바꾼다. */
describe('ProfilePage — 프로필 이미지 (D-154)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    stats.mockResolvedValue({ userId: 1, username: '철수', games: [] });
    catalog.mockResolvedValue({ games: [] });
  });

  it('D-302 — 회원은 아바타를 눌러 설정 창을 연다', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '철수' } as never });
    renderProfile();

    fireEvent.click(await screen.findByRole('button', { name: '내 아바타 — 눌러서 이미지 바꾸기' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('AVATAR_DIALOG');
  });

  it('D-303 — [프로필 이미지 바꾸기] 버튼은 없다', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '철수' } as never });
    renderProfile();

    await screen.findByRole('button', { name: '내 아바타 — 눌러서 이미지 바꾸기' });
    expect(screen.queryByRole('button', { name: '프로필 이미지 바꾸기' })).toBeNull();
  });

  it('D-303 — 게스트가 아바타를 누르면 창 대신 안내 토스트', async () => {
    useToastStore.setState({ toasts: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 2, username: 'guest-ab12cd34', guest: true } as never });
    renderProfile();

    fireEvent.click(await screen.findByRole('button', { name: '내 아바타' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(useToastStore.getState().toasts.map((x) => x.message).join()).toContain('게스트는 아바타를 바꿀 수 없어요');
    expect(screen.queryByRole('button', { name: '프로필 이미지 바꾸기' })).toBeNull();
  });
});

/** D-305 — 머리에 통합 계급과 레이팅 숫자. D-304 — 비밀번호는 새 비밀번호만. */
describe('ProfilePage — 통합 레이팅·비밀번호 (D-304·D-305)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    catalog.mockResolvedValue({ games: [{ id: 'ONE_CARD', displayName: '원카드' }] });
    headToHead.mockResolvedValue({ entries: [] });
  });

  it('머리에 통합 계급 아이콘과 레이팅 숫자를 보인다 — 게임 줄에는 계급이 없다', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '철수' } as never });
    stats.mockResolvedValue({
      userId: 1,
      username: '철수',
      rating: 1126,
      tier: 'GOLD_STAR',
      games: [{ gameType: 'ONE_CARD', rating: 1090, tier: 'GOLD_STAR', winCount: 4, loseCount: 1, desertCount: 0 }],
    });
    renderProfile();

    expect(await screen.findByTestId('profile-rating')).toHaveTextContent('1126점');
    expect(screen.getAllByRole('img', { name: '금별' })).toHaveLength(1);
    const records = screen.getByRole('region', { name: '게임별 전적' });
    expect(within(records).getByText('원카드')).toBeInTheDocument();
    expect(within(records).getByText(/4승 1패/)).toBeInTheDocument();
    expect(within(records).queryByRole('img', { name: '금별' })).toBeNull();
  });

  it('한 판도 안 한 회원은 시작 계급·1000점', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '철수' } as never });
    stats.mockResolvedValue({ userId: 1, username: '철수', rating: 1000, tier: 'MISSILE', games: [] });
    renderProfile();

    expect(await screen.findByTestId('profile-rating')).toHaveTextContent('1000점');
    expect(screen.getByRole('img', { name: '미사일' })).toBeInTheDocument();
  });

  it('게스트는 계급·레이팅이 없다', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 2, username: 'guest-ab12cd34', guest: true } as never });
    stats.mockResolvedValue({ userId: 2, username: 'guest-ab12cd34', rating: 1000, tier: 'MISSILE', games: [] });
    renderProfile();

    await screen.findByRole('button', { name: '계급표' });
    await screen.findByText('아직 플레이한 게임이 없습니다.');
    expect(screen.queryByTestId('profile-rating')).toBeNull();
    expect(screen.queryByRole('img', { name: '미사일' })).toBeNull();
  });

  it('비밀번호 변경은 새 비밀번호와 확인만 묻는다', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '철수' } as never });
    stats.mockResolvedValue({ userId: 1, username: '철수', rating: 1000, tier: 'MISSILE', games: [] });
    renderProfile();

    expect(await screen.findByLabelText('새 비밀번호')).toBeInTheDocument();
    expect(screen.getByLabelText('새 비밀번호 확인')).toBeInTheDocument();
    expect(screen.queryByLabelText('현재 비밀번호')).toBeNull();
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
    // D-303 — 게임은 아이콘으로(이름은 풍선말·보조기기용 글).
    expect(within(section).getByText('원카드')).toBeInTheDocument();
    expect(headToHead).toHaveBeenCalledWith('tok', 1);
  });

  it('아직 없으면 안내', async () => {
    renderProfile();
    expect(await screen.findByText('아직 함께한 사람이 없어요')).toBeInTheDocument();
  });
});

/** D-266 — 계급 옆 [계급표]: 헤더 계급(D-305 — 통합 계급)을 강조한다. */
describe('ProfilePage — 계급표 (D-266)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    catalog.mockResolvedValue({ games: [{ id: 'ONE_CARD', displayName: '원카드' }] });
    headToHead.mockResolvedValue({ entries: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '철수' } as never });
  });

  it('누르면 16단계 표가 열리고 내 계급 줄이 강조된다', async () => {
    stats.mockResolvedValue({
      userId: 1,
      username: '철수',
      rating: 1130,
      tier: 'GOLD_STAR',
      games: [{ gameType: 'ONE_CARD', rating: 1130, tier: 'GOLD_STAR', winCount: 4, loseCount: 1, desertCount: 0 }],
    });
    renderProfile();
    await screen.findAllByRole('img', { name: '금별' });

    fireEvent.click(screen.getByRole('button', { name: '계급표' }));

    const dialog = screen.getByRole('dialog', { name: '계급표' });
    const rows = within(dialog).getAllByRole('listitem');
    expect(rows).toHaveLength(16);
    const mine = rows.filter((li) => li.getAttribute('aria-current') === 'true');
    expect(mine.map((li) => li.getAttribute('data-tier'))).toEqual(['GOLD_STAR']);
    expect(mine[0]).toHaveTextContent('1120점 이상');
    expect(dialog).toHaveTextContent('1130점');
    expect(dialog).not.toHaveTextContent('기준');
  });

  it('게스트는 강조 없이 표만', async () => {
    useAuthStore.setState({ token: 'tok', user: { userId: 2, username: 'guest-ab12cd34', guest: true } as never });
    stats.mockResolvedValue({ userId: 2, username: 'guest-ab12cd34', games: [] });
    renderProfile();

    fireEvent.click(await screen.findByRole('button', { name: '계급표' }));
    const rows = within(screen.getByRole('dialog', { name: '계급표' })).getAllByRole('listitem');
    expect(rows).toHaveLength(16);
    expect(rows.some((li) => li.hasAttribute('aria-current'))).toBe(false);
  });
});
