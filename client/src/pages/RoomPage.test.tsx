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

// 게임판은 소켓을 여는 무거운 컴포넌트라 props 만 잡는 스텁으로 바꾼다 (D-120). 텍스트는
// 대기실 튜토리얼 테스트(D-121)가 IN_GAME 분기 진입을 확인하는 데 쓴다.
const { skullProps, tichuProps, oneCardProps } = vi.hoisted(() => ({
  skullProps: [] as Record<string, unknown>[],
  tichuProps: [] as Record<string, unknown>[],
  oneCardProps: [] as Record<string, unknown>[],
}));
vi.mock('@/features/skullking/SkullKingTable', () => ({
  SkullKingTable: (props: Record<string, unknown>) => {
    skullProps.push(props);
    return <div data-testid="skullking-table">스컬킹 게임판</div>;
  },
}));
vi.mock('@/features/onecard/OneCardTable', () => ({
  OneCardTable: (props: Record<string, unknown>) => {
    oneCardProps.push(props);
    return <div data-testid="onecard-table">원카드 게임판</div>;
  },
}));
vi.mock('@/features/tichu/GameTable', () => ({
  GameTable: (props: Record<string, unknown>) => {
    tichuProps.push(props);
    return <div data-testid="tichu-table">티츄 게임판</div>;
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
    oneCardProps.length = 0;
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

  /**
   * D-122 — FINISHED 방에서 누가 나가면 서버가 `LREM` 으로 좌석 목록을 당겼고(서버는 D-122 로
   * 막았다), 유지된 게임판은 매번 최신 메타의 playerIds·botSeats 로 그렸다. 엔진 좌석 번호는
   * 그대로라 남은 사람 화면의 이름·(나)·봇 표시가 한 칸씩 밀렸다. 전이 직전 IN_GAME 의
   * 좌석 목록을 얼려 넘긴다.
   */
  it('유지된 게임판은 전이 직전 IN_GAME 좌석 목록을 고정해 넘긴다 — 뒤이은 퇴장 메타에 밀리지 않는다', async () => {
    // 나(1)는 좌석 1. 좌석 0 은 다른 사람(2), 2·3 은 봇.
    const inGame = {
      ...ROOM,
      gameType: 'SKULL_KING',
      status: 'IN_GAME',
      playerIds: [2, 1, 900, 901],
      botSeats: [2, 3],
    } as Room;
    loadGame.mockResolvedValue(game('SKULL_KING', []));
    renderRoom(inGame);
    await screen.findByTestId('skullking-table');
    expect(skullProps.at(-1)!.playerIds).toEqual([2, 1, 900, 901]);

    act(() => {
      metaCallback()({ ...inGame, status: 'FINISHED' } as Room);
    });
    // 좌석 0 의 사람이 '메인으로'를 눌러 나간 메타 — 목록이 당겨지고 봇 좌석도 재계산된다.
    // (참가자 집합이 바뀌어 이름 재조회가 돈다 — async act 로 그 갱신까지 흘려보낸다.)
    await act(async () => {
      metaCallback()({
        ...inGame,
        status: 'FINISHED',
        playerIds: [1, 900, 901],
        botSeats: [1, 2],
      } as Room);
    });

    const last = skullProps.at(-1)!;
    expect(last.roomFinished).toBe(true);
    expect(last.playerIds).toEqual([2, 1, 900, 901]);
    expect(last.botSeats).toEqual([2, 3]);
  });

  it('게임 중에는 고정하지 않고 최신 메타를 그대로 넘긴다', async () => {
    const inGame = {
      ...ROOM,
      gameType: 'SKULL_KING',
      status: 'IN_GAME',
      playerIds: [1, 2],
      botSeats: [],
    } as unknown as Room;
    loadGame.mockResolvedValue(game('SKULL_KING', []));
    renderRoom(inGame);
    await screen.findByTestId('skullking-table');

    act(() => {
      metaCallback()({ ...inGame, botSeats: [1] } as Room);
    });

    expect(skullProps.at(-1)!.botSeats).toEqual([1]);
  });

  it('처음부터 FINISHED 로 들어오면(새로고침) 기존 종료 카드', async () => {
    loadGame.mockResolvedValue(game('SKULL_KING', []));
    renderRoom({ gameType: 'SKULL_KING', status: 'FINISHED' });

    expect(await screen.findByText('게임이 종료되었습니다.')).toBeInTheDocument();
    expect(screen.queryByTestId('skullking-table')).toBeNull();
  });

  // D-129 — 원카드는 종료 화면(순위)을 가진 두 번째 게임이다. 이 분기가 없으면 원카드 방이 티츄 게임판으로 떨어진다.
  it('원카드 게임 중이면 원카드 게임판을 그린다 — 티츄 게임판으로 떨어지지 않는다', async () => {
    loadGame.mockResolvedValue(game('ONE_CARD', []));
    renderRoom({ gameType: 'ONE_CARD', status: 'IN_GAME', playerIds: [1, 2, 3] });

    await screen.findByTestId('onecard-table');
    expect(screen.queryByTestId('tichu-table')).toBeNull();
    expect(oneCardProps.at(-1)!.playerIds).toEqual([1, 2, 3]);
    expect(oneCardProps.at(-1)!.roomFinished).toBe(false);
  });

  it('원카드도 게임 중 FINISHED 를 받으면 게임판을 유지하고 roomFinished 를 넘긴다', async () => {
    loadGame.mockResolvedValue(game('ONE_CARD', []));
    renderRoom({ gameType: 'ONE_CARD', status: 'IN_GAME' });
    await screen.findByTestId('onecard-table');

    act(() => {
      metaCallback()({ ...ROOM, gameType: 'ONE_CARD', status: 'FINISHED' } as Room);
    });

    expect(screen.getByTestId('onecard-table')).toBeInTheDocument();
    expect(oneCardProps.at(-1)!.roomFinished).toBe(true);
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
