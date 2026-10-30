import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GameHubPage } from './GameHubPage';
import { ApiError } from '@/api/client';
import { useAuthStore } from '@/features/auth/authStore';
import { spectateNoticeSeen } from '@/features/lobby/SpectateNotice';
import type { GameSummary, Room } from '@/types/api';

/**
 * D-121 — 허브의 '게임 방법'은 게임 카드마다 달린다. 허브는 게임을 고를 수 없으므로
 * 첫 방문 자동 노출을 하지 않는다(자동 노출은 그 게임 대기실 첫 입장, RoomPage).
 */

const { catalog, list, stats, spectate } = vi.hoisted(() => ({
  catalog: vi.fn(),
  list: vi.fn(),
  stats: vi.fn(),
  spectate: vi.fn(),
}));

vi.mock('@/api/games', () => ({ gamesApi: { catalog } }));
vi.mock('@/api/rooms', () => ({ roomsApi: { list, joinOrReconnect: vi.fn(), spectate } }));
vi.mock('@/api/users', () => ({ usersApi: { stats } }));
vi.mock('@/features/stats/RankingCard', () => ({ RankingCard: () => <section data-testid="ranking" /> }));

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
    <MemoryRouter initialEntries={['/games']}>
      <Routes>
        <Route path="/games" element={<GameHubPage />} />
        <Route path="/profile" element={<div>PROFILE_PAGE</div>} />
      </Routes>
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
      await screen.findByRole('heading', { name: '스컬킹에 오신 걸 환영합니다' }),
    ).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }));

    fireEvent.click(screen.getByRole('button', { name: '티츄 게임 방법' }));
    expect(
      await screen.findByRole('heading', { name: '티츄에 오신 걸 환영합니다' }),
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

describe('GameHubPage — 대기 중인 방 목록 (D-130)', () => {
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

  // D-136 — 봇과 연습 방은 목록에 오르지 않는다(서버). 예전 "🤖 솔로" 배지도 지웠다 — 혹시 와도 그리지 않는다.
  it('봇 채우기 방이 와도 "🤖 솔로" 배지를 그리지 않는다 (D-136)', async () => {
    list.mockResolvedValue({ rooms: [{ ...room('p', 'SKULL_KING', 8), fillWithBots: true, botSeats: [1, 2, 3, 4, 5, 6, 7] }] });
    renderHub();

    expect(await screen.findByText(/스컬킹 · 1 \/ 8 · 대기 중/)).toBeInTheDocument();
    expect(screen.queryByText(/솔로/)).toBeNull();
  });
});

/**
 * D-133 결정 A — 서버가 관전으로 받으면 방 화면 위에 "…관전으로 들어왔어요"를 띄운다. 허브 "구경하기"(방 ID 로 관전)는 본인이 고른
 * 관전이라 탭에 관전 기록을 남기고 들어가 방 화면이 안내를 띄우지 않게 한다.
 */
describe('GameHubPage — 허브에서 고른 관전 (D-133)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    catalog.mockResolvedValue({ games: CATALOG });
    list.mockResolvedValue({ rooms: [] });
    stats.mockResolvedValue({ userId: 1, username: 'me', games: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
  });

  it('방 ID 로 구경하기를 고르면 탭에 관전 기록을 남기고 그 방으로 간다', async () => {
    spectate.mockResolvedValue(room('r9', 'TICHU', 4));
    render(
      <MemoryRouter initialEntries={['/games']}>
        <Routes>
          <Route path="/games" element={<GameHubPage />} />
          <Route path="/rooms/:roomId" element={<div>ROOM_PAGE</div>} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.change(await screen.findByPlaceholderText('방 ID 로 관전 진입'), { target: { value: 'r9' } });
    fireEvent.click(screen.getByRole('button', { name: '구경하기' }));

    expect(await screen.findByText('ROOM_PAGE')).toBeInTheDocument();
    expect(spectate).toHaveBeenCalledWith('tok', 'r9');
    expect(spectateNoticeSeen('r9')).toBe(true);
  });
});

/** D-138 — 허브 "입장"·"구경하기"가 실패하면 서버 영어 원문(`Room not found` 등) 대신 한국어(api/errorText). */
describe('GameHubPage — 오류 문구 (D-138)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    catalog.mockResolvedValue({ games: CATALOG });
    list.mockResolvedValue({ rooms: [room('a', 'SKULL_KING', 8)] });
    stats.mockResolvedValue({ userId: 1, username: 'me', games: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
  });

  it('입장하려던 방이 그사이 없어지면 "방이 없어졌어요"', async () => {
    const { roomsApi } = await import('@/api/rooms');
    vi.mocked(roomsApi.joinOrReconnect).mockRejectedValue(
      new ApiError(404, { error: { code: 'ROOM_NOT_FOUND', message: 'Room not found' } }),
    );
    renderHub();

    fireEvent.click(await screen.findByRole('button', { name: '입장' }));

    expect(await screen.findByText('방이 없어졌어요')).toBeInTheDocument();
    expect(screen.queryByText('Room not found')).toBeNull();
  });

  it('구경하려던 방 ID 가 없으면 "방이 없어졌어요"', async () => {
    spectate.mockRejectedValue(new ApiError(404, { error: { code: 'ROOM_NOT_FOUND', message: 'Room not found' } }));
    renderHub();

    fireEvent.change(await screen.findByPlaceholderText('방 ID 로 관전 진입'), { target: { value: 'nope' } });
    fireEvent.click(screen.getByRole('button', { name: '구경하기' }));

    expect(await screen.findByText('방이 없어졌어요')).toBeInTheDocument();
  });
});

describe('GameHubPage — 사이트 이름 없음 (D-145)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    catalog.mockResolvedValue({ games: CATALOG });
    list.mockResolvedValue({ rooms: [] });
    stats.mockResolvedValue({ userId: 1, username: 'me', games: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
  });

  it('화면에 사이트 이름을 쓰지 않는다 — 큰 제목은 보조기기에만 남는다', async () => {
    const { container } = renderHub();
    await screen.findByRole('button', { name: '티츄 게임 방법' });

    expect(container.textContent).not.toMatch(/미르보드|Mirboard/);
    expect(screen.getByRole('heading', { level: 1 })).toHaveClass('sr-only');
  });
});

describe('GameHubPage — 로비 정리 (D-146)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    catalog.mockResolvedValue({ games: CATALOG });
    list.mockResolvedValue({ rooms: [] });
    stats.mockResolvedValue({
      userId: 1,
      username: 'me',
      games: [{ gameType: 'TICHU', rating: 1200, tier: 'DOUBLE_GOLD_STAR', winCount: 3, loseCount: 2, desertCount: 1 }],
    });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
  });

  it('색약 모드 버튼·전적 합계·로비 채팅이 없다 — 계급 배지는 남는다', async () => {
    const { container } = renderHub();
    await screen.findByRole('button', { name: '티츄 게임 방법' });

    expect(screen.queryByRole('button', { name: '색약 모드' })).toBeNull();
    expect(container.textContent).not.toMatch(/3승|2패|탈주 1/);
    expect(screen.queryByText('로비 채팅')).toBeNull();
    expect(screen.queryByPlaceholderText(/메시지|연결 중/)).toBeNull();
  });
});


/** D-154 — 메인 순서는 랭킹 → 대기 중인 방 → 게임 소개 카드. 위쪽에는 내 아바타만 두고 누르면 내 프로필로 간다. */
describe('GameHubPage — 배치와 아바타 (D-154)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    catalog.mockResolvedValue({ games: CATALOG });
    list.mockResolvedValue({ rooms: [] });
    stats.mockResolvedValue({ userId: 1, username: 'me', games: [] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
  });

  it('랭킹 → 대기 중인 방 → 게임 소개 순서다', async () => {
    renderHub();
    const gameCard = await screen.findByRole('button', { name: '티츄 게임 방법' });
    const ranking = screen.getByTestId('ranking');
    const rooms = screen.getByText('대기 중인 방');

    expect(ranking.compareDocumentPosition(rooms) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(rooms.compareDocumentPosition(gameCard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('위쪽에는 아바타만 — 이름 버튼이 없고, 아바타를 누르면 내 프로필로 간다', async () => {
    renderHub();
    await screen.findByRole('button', { name: '티츄 게임 방법' });

    expect(screen.queryByRole('button', { name: 'me' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '내 프로필' }));
    expect(await screen.findByText('PROFILE_PAGE')).toBeInTheDocument();
  });
});
