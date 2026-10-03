import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GameHubPage } from './GameHubPage';
import { useAuthStore } from '@/features/auth/authStore';
import type { GameSummary } from '@/types/api';

/**
 * D-121 — 허브의 '게임 방법'은 게임 카드마다 달린다. 허브는 게임을 고를 수 없으므로
 * 첫 방문 자동 노출을 하지 않는다(자동 노출은 그 게임 대기실 첫 입장, RoomPage).
 */

const { catalog, list, stats } = vi.hoisted(() => ({
  catalog: vi.fn(),
  list: vi.fn(),
  stats: vi.fn(),
}));

vi.mock('@/api/games', () => ({ gamesApi: { catalog } }));
vi.mock('@/api/rooms', () => ({ roomsApi: { list, joinOrReconnect: vi.fn(), spectate: vi.fn() } }));
vi.mock('@/api/users', () => ({ usersApi: { stats } }));
vi.mock('@/ws/useLobbyStomp', () => ({
  useLobbyStomp: () => ({ messages: [], connected: false, send: vi.fn() }),
}));
vi.mock('@/features/stats/RankingCard', () => ({ RankingCard: () => null }));

function game(id: string, displayName: string, status: GameSummary['status'] = 'AVAILABLE'): GameSummary {
  return {
    id,
    displayName,
    shortDescription: '',
    minPlayers: 2,
    maxPlayers: 8,
    status,
    supportedRoomOptions: [],
  };
}

const CATALOG = [
  game('SKULL_KING', '스컬킹'),
  game('TICHU', '티츄'),
  game('COMING_SOON', '준비 중 게임', 'COMING_SOON'),
];

function renderHub() {
  return render(
    <MemoryRouter>
      <GameHubPage />
    </MemoryRouter>,
  );
}

describe('GameHubPage — 게임별 튜토리얼 (D-121)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    catalog.mockResolvedValue({ games: CATALOG });
    list.mockResolvedValue({ rooms: [] });
    stats.mockResolvedValue({ userId: 1, username: 'me', games: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
  });

  it('튜토리얼이 등록된 게임 카드에만 "게임 방법" 버튼이 달린다', async () => {
    renderHub();

    expect(await screen.findByRole('button', { name: '티츄 게임 방법' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '준비 중 게임 게임 방법' })).toBeNull();
    // 헤더의 공용 '게임 방법' 버튼은 카드로 옮겨졌다.
    expect(screen.queryByRole('button', { name: '게임 방법' })).toBeNull();
  });

  it('카드 버튼이 그 게임의 튜토리얼을 연다', async () => {
    renderHub();

    fireEvent.click(await screen.findByRole('button', { name: '티츄 게임 방법' }));
    expect(
      await screen.findByRole('heading', { name: '미르보드 티츄에 오신 걸 환영합니다' }),
    ).toBeInTheDocument();
  });

  it('빈 localStorage 로 마운트해도 허브는 튜토리얼을 자동으로 열지 않는다', async () => {
    renderHub();

    await screen.findByRole('button', { name: '티츄 게임 방법' });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('닫으면 그 게임의 열람 키를 기록한다 — 대기실에서 다시 뜨지 않는다', async () => {
    renderHub();

    fireEvent.click(await screen.findByRole('button', { name: '티츄 게임 방법' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(localStorage.getItem('mirboard.tutorial.seen.v1')).toBe('1');
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
