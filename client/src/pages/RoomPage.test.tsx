import { act, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomPage } from './RoomPage';
import { useAuthStore } from '@/features/auth/authStore';
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

// 게임판은 소켓을 여는 무거운 컴포넌트라 props 만 잡는 스텁으로 바꾼다 (D-120).
const { skullProps, tichuProps } = vi.hoisted(() => ({
  skullProps: [] as Record<string, unknown>[],
  tichuProps: [] as Record<string, unknown>[],
}));
vi.mock('@/features/skullking/SkullKingTable', () => ({
  SkullKingTable: (props: Record<string, unknown>) => {
    skullProps.push(props);
    return <div data-testid="skullking-table" />;
  },
}));
vi.mock('@/features/tichu/GameTable', () => ({
  GameTable: (props: Record<string, unknown>) => {
    tichuProps.push(props);
    return <div data-testid="tichu-table" />;
  },
}));

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

describe('RoomPage — 대기실 헤더 라벨 (D-110)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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

/**
 * D-120 — 봇 방은 매치가 끝나면 서버가 방을 FINISHED 로 바꾸고, 그 메타가 게임 이벤트보다
 * 먼저 올 수 있다. 그때 게임판을 내리면 라운드 10 결과와 최종 점수를 볼 수 없다. 그래서
 * **이 세션에서 IN_GAME→FINISHED 전이를 본** 스컬킹 게임판은 내리지 않는다 — 메타와 게임
 * 이벤트의 도착 순서와 무관한 판단이다.
 */
describe('RoomPage — 종료 전이 후 게임판 유지 (D-120)', () => {
  const metaCallback = () => vi.mocked(useRoomMeta).mock.calls.at(-1)![2];

  beforeEach(() => {
    vi.clearAllMocks();
    skullProps.length = 0;
    tichuProps.length = 0;
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  it('스컬킹 게임 중 FINISHED 를 받으면 게임판을 유지하고 roomFinished 를 넘긴다', async () => {
    loadGame.mockResolvedValue(game('SKULL_KING', []));
    renderRoom({ gameType: 'SKULL_KING', status: 'IN_GAME' });
    await screen.findByTestId('skullking-table');
    expect(skullProps.at(-1)!.roomFinished).toBe(false);

    act(() => {
      metaCallback()({ ...ROOM, gameType: 'SKULL_KING', status: 'FINISHED' } as Room);
    });

    expect(screen.getByTestId('skullking-table')).toBeInTheDocument();
    expect(skullProps.at(-1)!.roomFinished).toBe(true);
    expect(screen.queryByText('게임이 종료되었습니다.')).toBeNull();
  });

  it('처음부터 FINISHED 로 들어오면(새로고침) 기존 종료 카드', async () => {
    loadGame.mockResolvedValue(game('SKULL_KING', []));
    renderRoom({ gameType: 'SKULL_KING', status: 'FINISHED' });

    expect(await screen.findByText('게임이 종료되었습니다.')).toBeInTheDocument();
    expect(screen.queryByTestId('skullking-table')).toBeNull();
  });

  it('티츄 방은 기존대로 FINISHED 전이 시 종료 카드로 바뀐다', async () => {
    loadGame.mockResolvedValue(game('TICHU', ['TARGET_SCORE', 'TEAMS', 'BETTING']));
    renderRoom({ gameType: 'TICHU', status: 'IN_GAME' });
    await screen.findByTestId('tichu-table');

    act(() => {
      metaCallback()({ ...ROOM, gameType: 'TICHU', status: 'FINISHED' } as Room);
    });

    expect(screen.queryByTestId('tichu-table')).toBeNull();
    expect(screen.getByText('게임이 종료되었습니다.')).toBeInTheDocument();
  });
});
