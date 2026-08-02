import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomPage } from './RoomPage';
import { useAuthStore } from '@/features/auth/authStore';
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
  render(
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
