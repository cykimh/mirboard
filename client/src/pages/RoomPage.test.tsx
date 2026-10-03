import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomPage } from './RoomPage';
import { useAuthStore } from '@/features/auth/authStore';
import { GAME_TUTORIALS } from '@/features/tutorial/gameTutorials';
import { markTutorialSeen } from '@/features/tutorial/useTutorialGate';
import { useRoomMeta } from '@/ws/useRoomMeta';
import type { GameSummary, Room, RoomOption } from '@/types/api';

/**
 * D-106 정정 — 대기실의 좌석 정책 라벨.
 *
 * <p>이 파일이 없어서 결함이 UI 에서 안 잡혔다. 처음 구현은 팀 없는 게임에서 좌석 정책
 * 행을 **숨겼는데**, 좌석 정책은 게임 중립이다(`RANDOM` 은 좌석 순서를 섞을 뿐이고
 * `domain.game` 은 `TeamPolicy` 를 모른다). 개인전에서도 좌석 순서 = 턴 순서라 의미가
 * 있으므로, 숨기지 않고 라벨만 바꾼다.
 *
 * <p>세 번째 케이스(로딩 중 미표시)가 핵심이다 — 기본값을 "좌석 순서"로 두고 그리면
 * 티츄에서 "좌석 순서"가 한 프레임 떴다 "팀 배정"으로 바뀐다.
 */

vi.mock('@/ws/useRoomMeta', () => ({ useRoomMeta: vi.fn() }));
// 게임판은 자기 소켓을 연다 — 대기실 테스트에선 자리표시자로 둔다(IN_GAME 분기 진입만 확인).
vi.mock('@/features/skullking/SkullKingTable', () => ({
  SkullKingTable: () => <div>스컬킹 게임판</div>,
}));
vi.mock('@/features/tichu/GameTable', () => ({ GameTable: () => <div>티츄 게임판</div> }));

const { joinOrReconnect, loadGame, names } = vi.hoisted(() => ({
  joinOrReconnect: vi.fn(),
  loadGame: vi.fn(),
  names: vi.fn(),
}));

vi.mock('@/api/rooms', () => ({
  roomsApi: {
    joinOrReconnect,
    leave: vi.fn(),
    setReady: vi.fn(),
    updateTeamPolicy: vi.fn(),
    abort: vi.fn(),
    stopSpectating: vi.fn(),
  },
}));
vi.mock('@/api/games', () => ({ loadGame }));
vi.mock('@/api/users', () => ({ usersApi: { names } }));

const ROOM: Room = {
  roomId: 'r1',
  name: '테스트 방',
  gameType: 'TICHU',
  status: 'WAITING',
  hostId: 1,
  capacity: 4,
  playerCount: 1,
  playerIds: [1],
  teamPolicy: 'SEQUENTIAL',
  readyUserIds: [],
  spectatorIds: [],
  targetScore: 1000,
  turnSeconds: 0,
  stake: 0,
} as unknown as Room;

function game(id: string, options: RoomOption[]): GameSummary {
  return {
    id,
    displayName: id,
    shortDescription: '',
    minPlayers: 2,
    maxPlayers: 8,
    status: 'AVAILABLE',
    supportedRoomOptions: options,
  };
}

function renderRoom(room: Partial<Room> = {}) {
  joinOrReconnect.mockResolvedValue({ mode: 'JOINED', room: { ...ROOM, ...room } });
  return render(
    <MemoryRouter initialEntries={['/rooms/r1']}>
      <Routes>
        <Route path="/rooms/:roomId" element={<RoomPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('RoomPage — 좌석 정책 라벨 (D-106 정정)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // D-121 — 대기실 첫 입장 튜토리얼 자동 노출(모달)이 끼어들지 않게 전부 본 것으로 둔다.
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    // 실제 계약: { names: [{userId, username}] } — 배열이다.
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  it('팀이 있는 게임이면 "팀 배정" 으로 부른다', async () => {
    loadGame.mockResolvedValue(game('TICHU', ['TARGET_SCORE', 'TEAMS', 'BETTING']));
    renderRoom({ gameType: 'TICHU' });

    expect(await screen.findByText('팀 배정')).toBeTruthy();
    expect(screen.queryByText('좌석 순서')).toBeNull();
  });

  it('개인전이면 숨기지 않고 "좌석 순서" 로 부른다', async () => {
    loadGame.mockResolvedValue(game('SKULL_KING', []));
    renderRoom({ gameType: 'SKULL_KING' });

    // 한때 이 행을 통째로 숨겼다 — 개인전에서도 좌석 순서 = 턴 순서라 유효한 기능이다.
    expect(await screen.findByText('좌석 순서')).toBeTruthy();
    expect(screen.queryByText('팀 배정')).toBeNull();
  });

  it('카탈로그 조회 전에는 행을 그리지 않는다 (라벨 깜빡임 방지)', async () => {
    let resolve!: (g: GameSummary) => void;
    loadGame.mockReturnValue(new Promise<GameSummary>((r) => { resolve = r; }));
    renderRoom({ gameType: 'TICHU' });

    // 방은 이미 렌더됐지만 게임 메타는 아직 — 여기서 "좌석 순서"를 그리면
    // 곧바로 "팀 배정"으로 바뀌어 깜빡인다.
    await screen.findByText('테스트 방');
    expect(screen.queryByText('좌석 순서')).toBeNull();
    expect(screen.queryByText('팀 배정')).toBeNull();

    resolve(game('TICHU', ['TEAMS']));
    expect(await screen.findByText('팀 배정')).toBeTruthy();
  });

  it('게임 메타 조회에 실패해도 좌석 정책은 계속 쓸 수 있다', async () => {
    loadGame.mockRejectedValue(new Error('network'));
    renderRoom({ gameType: 'SKULL_KING' });

    // 실패는 빈 배열 폴백 — 라벨만 보수적으로 중립이 되고 기능은 유지된다.
    expect(await screen.findByText('좌석 순서')).toBeTruthy();
  });
});

describe('RoomPage — 대기실 헤더 라벨 (D-110)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  it('게임 표시명과 한국어 상태를 쓰고 enum 원문을 노출하지 않는다', async () => {
    loadGame.mockResolvedValue({ ...game('SKULL_KING', []), displayName: '스컬킹' });
    renderRoom({ gameType: 'SKULL_KING' });

    expect(await screen.findByText(/스컬킹 · 대기 중 · 1\/4/)).toBeTruthy();
    expect(screen.queryByText(/SKULL_KING|WAITING/)).toBeNull();
  });

  it('게임 메타 조회에 실패하면 게임 id 로 폴백한다', async () => {
    loadGame.mockRejectedValue(new Error('network'));
    renderRoom({ gameType: 'SKULL_KING' });

    expect(await screen.findByText(/SKULL_KING · 대기 중 · 1\/4/)).toBeTruthy();
  });
});

describe('RoomPage — 대기실 첫 입장 튜토리얼 (D-121)', () => {
  const SK_KEY = 'mirboard.tutorial.skull_king.seen.v1';
  const SK_TITLE = '미르보드 스컬킹에 오신 걸 환영합니다';

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useRoomMeta).mockReset();
    // setup.ts 의 MemoryStorage 는 파일 안에서 테스트 간에 유지된다 — 직접 비운다.
    localStorage.clear();
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    loadGame.mockResolvedValue({ ...game('SKULL_KING', []), displayName: '스컬킹' });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  it('그 게임 대기실에 처음 들어오면 1회 자동으로 뜨고, 닫으면 다시 안 뜬다', async () => {
    const first = renderRoom({ gameType: 'SKULL_KING' });

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: SK_TITLE })).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(localStorage.getItem(SK_KEY)).toBe('1');
    expect(screen.queryByRole('dialog')).toBeNull();
    first.unmount();

    renderRoom({ gameType: 'SKULL_KING' });
    await screen.findByText('테스트 방');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('티츄는 레거시 키를 본 사람에게 다시 띄우지 않는다', async () => {
    localStorage.setItem('mirboard.tutorial.seen.v1', '1');
    loadGame.mockResolvedValue(game('TICHU', ['TEAMS']));
    renderRoom({ gameType: 'TICHU' });

    await screen.findByText('팀 배정');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('헤더 "게임 방법" 버튼으로 다시 연다', async () => {
    localStorage.setItem(SK_KEY, '1');
    renderRoom({ gameType: 'SKULL_KING' });

    fireEvent.click(await screen.findByRole('button', { name: '게임 방법' }));
    expect(await screen.findByRole('heading', { name: SK_TITLE })).toBeInTheDocument();
  });

  it('튜토리얼이 없는 게임이면 버튼도 다이얼로그도 없다', async () => {
    loadGame.mockResolvedValue(game('NEW_GAME', []));
    renderRoom({ gameType: 'NEW_GAME' });

    await screen.findByText('테스트 방');
    expect(screen.queryByRole('button', { name: '게임 방법' })).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('IN_GAME 방으로 바로 들어오면 대기실 튜토리얼을 띄우지 않는다', async () => {
    renderRoom({ gameType: 'SKULL_KING', status: 'IN_GAME' });

    await screen.findByText('스컬킹 게임판');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(localStorage.getItem(SK_KEY)).toBeNull();
  });

  it('연 채로 게임이 시작되면 닫고, 리매치로 대기실에 돌아와도 다시 열리지 않는다', async () => {
    let pushRoom!: (r: Room) => void;
    vi.mocked(useRoomMeta).mockImplementation((_id, _token, onRoom) => {
      pushRoom = onRoom;
    });
    localStorage.setItem(SK_KEY, '1');
    renderRoom({ gameType: 'SKULL_KING' });

    fireEvent.click(await screen.findByRole('button', { name: '게임 방법' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();

    act(() => pushRoom({ ...ROOM, gameType: 'SKULL_KING', status: 'IN_GAME' }));
    expect(await screen.findByText('스컬킹 게임판')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();

    act(() => pushRoom({ ...ROOM, gameType: 'SKULL_KING', status: 'WAITING' }));
    expect(await screen.findByText('테스트 방')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
