import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GameHubPage } from './GameHubPage';
import { useAuthStore } from '@/features/auth/authStore';
import type { GameSummary, Room } from '@/types/api';

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

function room(roomId: string, gameType: string, capacity: number): Room {
  return {
    roomId,
    name: `방 ${roomId}`,
    gameType,
    hostId: 2,
    status: 'WAITING',
    capacity,
    playerCount: 1,
    playerIds: [2],
    spectatorIds: [],
    teamPolicy: 'SEQUENTIAL',
    createdAt: 0,
    fillWithBots: false,
    botSeats: [],
    targetScore: 1000,
    turnSeconds: 0,
    stake: 0,
    readyUserIds: [],
  };
}

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
    expect(screen.getByRole('button', { name: '스컬킹 게임 방법' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '준비 중 게임 게임 방법' })).toBeNull();
    // 헤더의 공용 '게임 방법' 버튼은 카드로 옮겨졌다.
    expect(screen.queryByRole('button', { name: '게임 방법' })).toBeNull();
  });

  it('카드 버튼이 그 게임의 튜토리얼을 연다', async () => {
    renderHub();

    fireEvent.click(await screen.findByRole('button', { name: '스컬킹 게임 방법' }));
    expect(
      await screen.findByRole('heading', { name: '미르보드 스컬킹에 오신 걸 환영합니다' }),
    ).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }));

    fireEvent.click(screen.getByRole('button', { name: '티츄 게임 방법' }));
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

    fireEvent.click(await screen.findByRole('button', { name: '스컬킹 게임 방법' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(localStorage.getItem('mirboard.tutorial.skull_king.seen.v1')).toBe('1');
    // 다른 게임의 열람 기록은 건드리지 않는다.
    expect(localStorage.getItem('mirboard.tutorial.seen.v1')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('GameHubPage — 대기 중인 방 목록 (S5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    catalog.mockResolvedValue({ games: CATALOG });
    stats.mockResolvedValue({ userId: 1, username: 'me', games: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
  });

  /** 대기실 헤더(D-110)처럼 카탈로그의 표시 이름을 쓴다 — `SKULL_KING`·`ONE_CARD` 원문이 나란히 보였다. */
  it('게임 종류를 표시 이름으로, 카탈로그에 없는 게임은 원문으로 보여 준다', async () => {
    list.mockResolvedValue({ rooms: [room('a', 'SKULL_KING', 8), room('b', 'MYSTERY', 4)] });
    renderHub();

    expect(await screen.findByText(/스컬킹 · 1 \/ 8 · 대기 중/)).toBeInTheDocument();
    expect(screen.getByText(/MYSTERY · 1 \/ 4 · 대기 중/)).toBeInTheDocument();
    expect(screen.queryByText(/SKULL_KING ·/)).toBeNull();
    expect(screen.queryByText(/WAITING/)).toBeNull(); // enum 원문이 아니라 한글 상태 라벨
  });
});
