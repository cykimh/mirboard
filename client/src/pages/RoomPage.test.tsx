import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomPage } from './RoomPage';
import { ApiError } from '@/api/client';
import type { JoinOrReconnectResponse } from '@/api/rooms';
import { useAuthStore } from '@/features/auth/authStore';
import { markSpectateNoticeSeen } from '@/features/lobby/SpectateNotice';
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

const { joinOrReconnect, spectate, loadGame, names } = vi.hoisted(() => ({
  joinOrReconnect: vi.fn(),
  spectate: vi.fn(),
  loadGame: vi.fn(),
  names: vi.fn(),
}));

vi.mock('@/api/rooms', () => ({
  roomsApi: {
    joinOrReconnect,
    spectate,
    leave: vi.fn(),
    setReady: vi.fn(),
    updateTeamPolicy: vi.fn(),
    abort: vi.fn(),
    stopSpectating: vi.fn(),
    startNow: vi.fn(),
    join: vi.fn(),
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

function renderRoom(room: Partial<Room> = {}, mode: JoinOrReconnectResponse['mode'] = 'JOINED') {
  joinOrReconnect.mockResolvedValue({ mode, room: { ...ROOM, ...room } });
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
 * **이 세션에서 IN_GAME→FINISHED 전이를 본** 스컬킹·원카드(D-129) 게임판은 내리지 않는다 —
 * 메타와 게임 이벤트의 도착 순서와 무관한 판단이다.
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

/**
 * D-133 — 대기실 "초대 링크". 방 주소 그대로(`/rooms/<방ID>`)를 공유창·복사·수동 입력란으로 건넨다. 초대는 시작 전에만
 * 의미가 있어(시작하면 자리가 없다) 대기실(WAITING)에만 둔다. 결과별 표시는 `InviteLinkButton.test` 가 자세히 본다.
 */
describe('RoomPage — 초대 링크 (D-133)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    loadGame.mockResolvedValue({ ...game('ONE_CARD', []), displayName: '원카드' });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  it('대기실에는 "초대 링크" 버튼이 있다', async () => {
    renderRoom({ gameType: 'ONE_CARD' });

    expect(await screen.findByRole('button', { name: '초대 링크' })).toBeInTheDocument();
  });

  it('게임 중(IN_GAME) 화면에는 없다', async () => {
    renderRoom({ gameType: 'ONE_CARD', status: 'IN_GAME' });

    await screen.findByTestId('onecard-table');
    expect(screen.queryByRole('button', { name: '초대 링크' })).toBeNull();
  });

  it('끝난 방(FINISHED) 화면에도 없다', async () => {
    loadGame.mockResolvedValue(game('TICHU', ['TEAMS']));
    renderRoom({ gameType: 'TICHU', status: 'FINISHED' });

    await screen.findByText('게임이 종료되었습니다.');
    expect(screen.queryByRole('button', { name: '초대 링크' })).toBeNull();
  });

  it('공유창·클립보드가 없으면(앱 내 브라우저) 이 방 주소가 선택된 입력란을 보인다', async () => {
    renderRoom({ gameType: 'ONE_CARD' });

    fireEvent.click(await screen.findByRole('button', { name: '초대 링크' }));

    const input = (await screen.findByLabelText('직접 복사해 주세요')) as HTMLInputElement;
    expect(input.value).toBe(`${window.location.origin}/rooms/r1`);
    expect(document.activeElement).toBe(input);
    expect(input.selectionEnd).toBe(input.value.length);
  });

  it('클립보드가 있으면 이 방 주소를 복사하고 알린다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    try {
      renderRoom({ gameType: 'ONE_CARD' });

      fireEvent.click(await screen.findByRole('button', { name: '초대 링크' }));

      expect(await screen.findByText('링크를 복사했어요')).toBeInTheDocument();
      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/rooms/r1`);
    } finally {
      delete (navigator as { clipboard?: unknown }).clipboard;
    }
  });

  // IP-3 — 사람으로 꽉 찬 대기실의 링크를 연 친구는 관전으로 들어온다. "바로 이 방에 들어와요"는 거짓이다.
  it('사람으로 꽉 찬 대기실이면 "링크를 받은 친구는 관전으로 들어와요"', async () => {
    renderRoom({ gameType: 'ONE_CARD', capacity: 2, playerCount: 2, playerIds: [1, 2], botSeats: [] });

    expect(
      await screen.findByText('자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요'),
    ).toBeInTheDocument();
    expect(screen.queryByText('링크를 보내면 친구가 바로 이 방에 들어와요.')).toBeNull();
  });

  it('빈자리가 있으면 "친구가 바로 이 방에 들어와요"', async () => {
    renderRoom({ gameType: 'ONE_CARD', capacity: 4, playerCount: 1, playerIds: [1] });

    expect(await screen.findByText('링크를 보내면 친구가 바로 이 방에 들어와요.')).toBeInTheDocument();
  });

  // 게스트 방은 기본 봇 채우기라 만들자마자 꽉 찬다. 봇은 자리를 비켜 주지 않으므로(봇 자리 비켜주기는 기각 — 사람과 할 때는 봇을
  // 없앤다) 링크를 받은 친구는 관전으로 들어온다 — 봇 자리도 찬 자리다.
  it('봇까지 다 찬 대기실(게스트 봇 채우기 방)도 "링크를 받은 친구는 관전으로 들어와요"', async () => {
    renderRoom({ gameType: 'ONE_CARD', capacity: 2, playerCount: 2, playerIds: [1, 900], botSeats: [1], fillWithBots: true });

    expect(
      await screen.findByText('자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요'),
    ).toBeInTheDocument();
    expect(screen.queryByText('링크를 보내면 친구가 바로 이 방에 들어와요.')).toBeNull();
  });
});

/**
 * D-133 — 방 주소로 처음 연 탭은 서버가 넣은 방 제목("{게임} · {방 이름} — 미르보드카페")으로 시작한다. SPA 는 제목을 관리하지 않으므로
 * 방 화면을 떠날 때 기본 제목으로 되돌린다 — 허브로 나가거나 다른 방에 가도 처음 방 이름이 탭·방문 기록에 남지 않게(IP-5).
 */
describe('RoomPage — 탭 제목 (D-133)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    loadGame.mockResolvedValue({ ...game('ONE_CARD', []), displayName: '원카드' });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  it('방 화면을 떠나면 서버가 넣은 방 제목을 기본 제목으로 되돌린다', async () => {
    document.title = '원카드 · 금요일 한 판 — 미르보드카페';
    const view = renderRoom({ gameType: 'ONE_CARD' });
    await screen.findByText('테스트 방');

    view.unmount();

    expect(document.title).toBe('Mirboard');
  });
});

/**
 * D-133 — 초대 링크로 들어왔는데 자리가 없거나 방이 사라졌을 때. 서버 오류 문구("Room capacity exceeded" 등)를
 * 그대로 보이지 않고 다음 행동을 제안한다 — 자리 없음은 관전, 방 없음은 메인으로. (서버 `join-or-reconnect` 는 꽉 찬
 * 대기실·진행 중 방이면 이미 관전으로 받으므로 이 패널은 확인과 입장 사이에 자리가 찬 경합에서 뜬다.)
 */
describe('RoomPage — 자리 없음·방 없음 (D-133)', () => {
  function renderRejected(err: ApiError) {
    joinOrReconnect.mockRejectedValue(err);
    return render(
      <MemoryRouter initialEntries={['/rooms/r1']}>
        <Routes>
          <Route path="/rooms/:roomId" element={<RoomPage />} />
          <Route path="/games" element={<div>GAMES_PAGE</div>} />
        </Routes>
      </MemoryRouter>,
    );
  }

  const apiError = (status: number, code: string, message: string) =>
    new ApiError(status, { error: { code, message } });

  beforeEach(() => {
    vi.clearAllMocks();
    skullProps.length = 0;
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 2, username: 'host' }] });
    loadGame.mockResolvedValue({ ...game('SKULL_KING', []), displayName: '스컬킹' });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'friend' } as never });
  });

  it.each([
    ['ROOM_FULL', 'Room capacity exceeded'],
    ['GAME_ALREADY_STARTED', 'Game already started'],
  ])('%s 면 오류 문구 대신 "자리가 없어요 — 관전할까요?"와 관전하기·메인으로', async (code, message) => {
    renderRejected(apiError(409, code, message));

    expect(await screen.findByText('자리가 없어요 — 관전할까요?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '관전하기' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '메인으로' })).toHaveAttribute('href', '/games');
    expect(screen.queryByText(message)).toBeNull();
  });

  it('관전하기를 누르면 관전으로 들어가 그 방을 다시 그린다 — 진행 중이면 관전자 게임판', async () => {
    renderRejected(apiError(409, 'GAME_ALREADY_STARTED', 'Game already started'));
    spectate.mockResolvedValue({
      ...ROOM,
      gameType: 'SKULL_KING',
      status: 'IN_GAME',
      hostId: 2,
      playerIds: [2, 3],
      playerCount: 2,
      spectatorIds: [1],
    } as Room);

    fireEvent.click(await screen.findByRole('button', { name: '관전하기' }));

    expect(await screen.findByTestId('skullking-table')).toBeInTheDocument();
    expect(spectate).toHaveBeenCalledWith('tok', 'r1');
    expect(skullProps.at(-1)!.spectator).toBe(true);
    expect(screen.queryByText('자리가 없어요 — 관전할까요?')).toBeNull();
    // D-133 결정 A — 패널에서 본인이 고른 관전이라 "…관전으로 들어왔어요" 안내는 없다.
    expect(screen.queryByText(/관전으로 들어왔어요/)).toBeNull();
  });

  it('꽉 찬 대기실에서 관전하기를 누르면 관전 중 대기실을 본다', async () => {
    renderRejected(apiError(409, 'ROOM_FULL', 'Room capacity exceeded'));
    spectate.mockResolvedValue({
      ...ROOM,
      gameType: 'SKULL_KING',
      hostId: 2,
      capacity: 2,
      playerIds: [2, 3],
      playerCount: 2,
      spectatorIds: [1],
    } as Room);

    fireEvent.click(await screen.findByRole('button', { name: '관전하기' }));

    expect(await screen.findByText('관전 중 — 본인 손패는 표시되지 않습니다.')).toBeInTheDocument();
  });

  it('메인으로를 누르면 허브로 간다', async () => {
    renderRejected(apiError(409, 'ROOM_FULL', 'Room capacity exceeded'));

    fireEvent.click(await screen.findByRole('link', { name: '메인으로' }));

    expect(await screen.findByText('GAMES_PAGE')).toBeInTheDocument();
  });

  it('ROOM_NOT_FOUND 면 "방이 없어졌어요"와 메인으로 — 관전하기는 없고 서버 문구도 보이지 않는다', async () => {
    renderRejected(apiError(404, 'ROOM_NOT_FOUND', 'Room not found'));

    expect(await screen.findByText('방이 없어졌어요')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '메인으로' })).toHaveAttribute('href', '/games');
    expect(screen.queryByRole('button', { name: '관전하기' })).toBeNull();
    expect(screen.queryByText('Room not found')).toBeNull();
  });

  it('관전하기 사이에 방이 사라지면 "방이 없어졌어요"로 바뀐다', async () => {
    renderRejected(apiError(409, 'ROOM_FULL', 'Room capacity exceeded'));
    spectate.mockRejectedValue(apiError(404, 'ROOM_NOT_FOUND', 'Room not found'));

    fireEvent.click(await screen.findByRole('button', { name: '관전하기' }));

    expect(await screen.findByText('방이 없어졌어요')).toBeInTheDocument();
  });

  // D-138 — 그 밖의 오류도 서버 영어 원문 대신 한국어(api/errorText).
  it('그 밖의 오류는 한국어 문구로 보인다', async () => {
    renderRejected(apiError(409, 'ALREADY_IN_ROOM', 'User already in room'));

    expect(await screen.findByText('이미 이 방에 있어요')).toBeInTheDocument();
    expect(screen.queryByText('User already in room')).toBeNull();
    expect(screen.queryByText('자리가 없어요 — 관전할까요?')).toBeNull();
  });
});

/**
 * D-133 결정 A — 서버 `join-or-reconnect` 는 꽉 찬 대기실·진행 중 방이면 묻지 않고 관전으로 받는다(`mode=SPECTATING`, 서버 무변경).
 * 그렇게 들어온 사람에게 방 화면 위에 이유를 알린다. 진행 중 방은 대기실 셸이 아니라 게임판을 바로 그리므로 세 렌더 분기(스컬킹·원카드
 * 게임판, 티츄 게임판, 대기실·종료 셸) 모두에 있어야 한다. 허브에서 고른 관전·새로고침한 관전자에게는 띄우지 않는다(탭 안 기록).
 */
describe('RoomPage — 관전으로 받아졌을 때 안내 (D-133 결정 A)', () => {
  const STARTED = '게임이 이미 시작돼 관전으로 들어왔어요';
  const FULL = '자리가 없어 관전으로 들어왔어요';
  const watching = { hostId: 2, playerIds: [2, 3], playerCount: 2, spectatorIds: [1] } as Partial<Room>;

  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    skullProps.length = 0;
    tichuProps.length = 0;
    oneCardProps.length = 0;
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 2, username: 'host' }] });
    loadGame.mockResolvedValue({ ...game('ONE_CARD', []), displayName: '원카드' });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'friend' } as never });
  });

  // FR-5 — 안내 문구를 읽어 주는 것은 방 화면이 늘 두는 상태 영역(`room-live-status`)이다. 카드는 보이는 안내일 뿐이다.
  const live = () => screen.getByTestId('room-live-status');

  it('진행 중인 원카드 방에 관전으로 받아지면 게임판 위에 안내한다', async () => {
    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');

    expect(await screen.findByTestId('onecard-table')).toBeInTheDocument();
    expect(await screen.findByRole('status')).toHaveTextContent(STARTED);
    expect(screen.getByRole('status')).toBe(live());
    expect(oneCardProps.at(-1)!.spectator).toBe(true);
  });

  it('진행 중인 티츄 방도 게임판 위에 안내한다 — 티츄 분기', async () => {
    loadGame.mockResolvedValue(game('TICHU', ['TEAMS']));
    renderRoom({ ...watching, gameType: 'TICHU', status: 'IN_GAME', capacity: 2 }, 'SPECTATING');

    expect(await screen.findByTestId('tichu-table')).toBeInTheDocument();
    expect(await screen.findByRole('status')).toHaveTextContent(STARTED);
    expect(screen.getByRole('status')).toBe(live());
  });

  it('꽉 찬 대기실에 관전으로 받아지면 안내하고 "관전 중" 알림도 그대로 둔다', async () => {
    renderRoom({ ...watching, gameType: 'ONE_CARD', capacity: 2 }, 'SPECTATING');

    expect(await screen.findByText('관전 중 — 본인 손패는 표시되지 않습니다.')).toBeInTheDocument();
    expect(live()).toHaveTextContent(FULL);
    expect(live()).toHaveAttribute('role', 'status');
  });

  /**
   * FR-5 — 라이브 영역은 내용이 바뀌기 전에 DOM 에 있어야 스크린 리더가 읽는다(IP-4 와 같은 결). 안내는 첫 입장 응답과 함께 정해져
   * 로딩 화면 → 방 화면 전환과 같은 렌더에 들어오므로, 영역을 방 화면 갈래 안에만 두면 영역과 문구가 함께 삽입된다. 그래서 로딩
   * 화면부터 모든 갈래의 맨 앞 같은 자리에 둔다 — 로딩 때의 그 노드에 문구가 들어간다.
   */
  it('로딩 화면에서도 상태 영역이 비어 있는 채로 있고, 안내 문구는 바로 그 노드에 들어간다', async () => {
    let resolve!: (v: JoinOrReconnectResponse) => void;
    joinOrReconnect.mockReturnValue(new Promise<JoinOrReconnectResponse>((r) => { resolve = r; }));
    render(
      <MemoryRouter initialEntries={['/rooms/r1']}>
        <Routes>
          <Route path="/rooms/:roomId" element={<RoomPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText('방 정보 불러오는 중...')).toBeInTheDocument();
    const before = live();
    expect(before).toHaveAttribute('role', 'status');
    expect(before.textContent).toBe('');

    await act(async () => {
      resolve({ mode: 'SPECTATING', room: { ...ROOM, ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' } as Room });
    });

    await screen.findByTestId('onecard-table');
    expect(live()).toBe(before);
    expect(before).toHaveTextContent(STARTED);
  });

  it.each([
    ['스컬킹·원카드 게임판', { gameType: 'ONE_CARD', status: 'IN_GAME' }, 'onecard-table'],
    ['티츄 게임판', { gameType: 'TICHU', status: 'IN_GAME' }, 'tichu-table'],
    ['대기실 셸', { gameType: 'ONE_CARD', status: 'WAITING' }, null],
  ] as const)('안내가 없어도 %s 에 상태 영역이 비어 있는 채로 있다', async (_label, over, boardId) => {
    useAuthStore.setState({ token: 'tok', user: { userId: 2, username: 'host' } as never });
    renderRoom({ ...watching, ...over } as Partial<Room>, 'RECONNECTED');

    if (boardId) await screen.findByTestId(boardId);
    else await screen.findByText('테스트 방');
    expect(live()).toHaveAttribute('role', 'status');
    expect(live().textContent).toBe('');
  });

  it('빈자리가 있는 대기실 관전은 안내하지 않는다 — 본인이 고른 관전', async () => {
    renderRoom({ ...watching, gameType: 'ONE_CARD', capacity: 4 }, 'SPECTATING');

    await screen.findByText('관전 중 — 본인 손패는 표시되지 않습니다.');
    expect(screen.queryByText(/관전으로 들어왔어요/)).toBeNull();
  });

  it('끝난 방은 안내하지 않는다 — 종료 카드가 설명한다', async () => {
    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'FINISHED' }, 'SPECTATING');

    await screen.findByText('게임이 종료되었습니다.');
    expect(screen.queryByText(/관전으로 들어왔어요/)).toBeNull();
  });

  it.each(['JOINED', 'RECONNECTED'] as const)('%s 면 안내하지 않는다', async (mode) => {
    useAuthStore.setState({ token: 'tok', user: { userId: 2, username: 'host' } as never });
    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, mode);

    await screen.findByTestId('onecard-table');
    expect(screen.queryByText(/관전으로 들어왔어요/)).toBeNull();
  });

  it('[닫기]를 누르면 내린다 — 상태 영역도 비운다', async () => {
    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');

    fireEvent.click(await screen.findByRole('button', { name: '닫기' }));

    expect(screen.queryByText(STARTED)).toBeNull();
    expect(live().textContent).toBe('');
    expect(screen.getByTestId('onecard-table')).toBeInTheDocument();
  });

  it('새로고침하면(같은 탭에서 다시 관전으로 받아짐) 다시 띄우지 않는다', async () => {
    const first = renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');
    expect(await screen.findByRole('status')).toHaveTextContent(STARTED);
    first.unmount();

    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');

    await screen.findByTestId('onecard-table');
    expect(screen.queryByText(STARTED)).toBeNull();
  });

  it('허브에서 고른 관전(기록을 남기고 들어옴)이면 띄우지 않는다', async () => {
    markSpectateNoticeSeen('r1');
    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');

    await screen.findByTestId('onecard-table');
    expect(screen.queryByText(STARTED)).toBeNull();
  });

  it('경합 패널에서 관전을 고르면 그 뒤 새로고침에도 띄우지 않는다', async () => {
    joinOrReconnect.mockRejectedValue(new ApiError(409, { error: { code: 'GAME_ALREADY_STARTED', message: 'x' } }));
    spectate.mockResolvedValue({ ...ROOM, ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' } as Room);
    const first = render(
      <MemoryRouter initialEntries={['/rooms/r1']}>
        <Routes>
          <Route path="/rooms/:roomId" element={<RoomPage />} />
        </Routes>
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByRole('button', { name: '관전하기' }));
    await screen.findByTestId('onecard-table');
    first.unmount();

    renderRoom({ ...watching, gameType: 'ONE_CARD', status: 'IN_GAME' }, 'SPECTATING');

    await screen.findByTestId('onecard-table');
    expect(screen.queryByText(STARTED)).toBeNull();
  });
});

/**
 * D-134 — 대기실 화면 재동기화. 휴대폰 방장이 초대 링크를 카톡으로 보내고 기다리는 동안 소켓이 죽으면(D-79) 그 사이 서버가 준비를
 * 풀었거나, 유예가 지나 자리를 잃었거나, 방이 없어졌을 수 있다 — 메타 소켓은 다시 구독만 해서 놓친 것을 받을 길이 없다. 그래서
 * `useRoomMeta` 가 다시 연결·다시 보이기·온라인 때 알리면(5번째 인자) 대기실은 첫 입장과 같은 `join-or-reconnect` 로 방을 다시
 * 맞춘다. 게임판은 자기 resync 가 있어(D-79) 부르지 않는다. 네트워크 실패로 화면을 오류로 바꾸지 않는다.
 */
describe('RoomPage — 대기실 화면 재동기화 (D-134)', () => {
  const resync = () => vi.mocked(useRoomMeta).mock.calls.at(-1)![4]!;
  const apiError = (status: number, code: string, message: string) =>
    new ApiError(status, { error: { code, message } });

  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    loadGame.mockResolvedValue({ ...game('ONE_CARD', []), displayName: '원카드' });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  it('대기실에서 알림을 받으면 join-or-reconnect 를 다시 불러 방을 다시 그린다', async () => {
    renderRoom({ gameType: 'ONE_CARD' });
    await screen.findByText('테스트 방');
    joinOrReconnect.mockResolvedValue({
      mode: 'RECONNECTED',
      room: { ...ROOM, gameType: 'ONE_CARD', playerIds: [1, 2], playerCount: 2, readyUserIds: [2] },
    });

    await act(async () => {
      await resync()();
    });

    expect(joinOrReconnect).toHaveBeenCalledTimes(2);
    expect(joinOrReconnect).toHaveBeenLastCalledWith('tok', 'r1');
    expect(await screen.findByText(/원카드 · 대기 중 · 2\/4/)).toBeInTheDocument();
  });

  it('유예가 지나 자리를 잃었고 방이 꽉 찼으면 관전으로 받아지고 첫 입장처럼 안내한다', async () => {
    renderRoom({ gameType: 'ONE_CARD' });
    await screen.findByText('테스트 방');
    joinOrReconnect.mockResolvedValue({
      mode: 'SPECTATING',
      room: { ...ROOM, gameType: 'ONE_CARD', capacity: 2, playerIds: [2, 3], playerCount: 2, spectatorIds: [1] },
    });

    await act(async () => {
      await resync()();
    });

    expect(await screen.findByText('관전 중 — 본인 손패는 표시되지 않습니다.')).toBeInTheDocument();
    expect(screen.getAllByText('자리가 없어 관전으로 들어왔어요').length).toBeGreaterThan(0);
  });

  it('방이 없어졌으면 "방이 없어졌어요" — 서버 문구를 보이지 않는다', async () => {
    renderRoom({ gameType: 'ONE_CARD' });
    await screen.findByText('테스트 방');
    joinOrReconnect.mockRejectedValue(apiError(404, 'ROOM_NOT_FOUND', 'Room not found'));

    await act(async () => {
      await resync()();
    });

    expect(await screen.findByText('방이 없어졌어요')).toBeInTheDocument();
    expect(screen.queryByText('Room not found')).toBeNull();
  });

  it.each([
    ['서버 오류', () => apiError(500, 'INTERNAL', 'boom')],
    ['네트워크 실패', () => new TypeError('Failed to fetch')],
  ])('그 밖의 오류(%s)는 조용히 넘기고 대기실을 그대로 둔다 — 다음 알림에 다시', async (_label, makeError) => {
    renderRoom({ gameType: 'ONE_CARD' });
    await screen.findByText('테스트 방');
    joinOrReconnect.mockRejectedValue(makeError());

    await act(async () => {
      await resync()();
    });

    expect(screen.getByText('테스트 방')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '초대 링크' })).toBeInTheDocument();
    expect(screen.queryByText('boom')).toBeNull();
    expect(screen.queryByText('Failed to fetch')).toBeNull();
  });

  it('게임 중(IN_GAME)에는 부르지 않는다 — 게임판이 자기 resync 를 한다', async () => {
    renderRoom({ gameType: 'ONE_CARD', status: 'IN_GAME' });
    await screen.findByTestId('onecard-table');

    await act(async () => {
      await resync()();
    });

    expect(joinOrReconnect).toHaveBeenCalledTimes(1);
  });

  it('진행 중이면 겹쳐 부르지 않는다', async () => {
    renderRoom({ gameType: 'ONE_CARD' });
    await screen.findByText('테스트 방');
    let resolve!: (v: JoinOrReconnectResponse) => void;
    joinOrReconnect.mockReturnValue(new Promise<JoinOrReconnectResponse>((r) => { resolve = r; }));

    // 콜백 타입은 `() => void` 지만 방 화면의 구현은 끝을 기다릴 수 있는 Promise 를 돌려준다.
    let first: unknown;
    act(() => {
      first = resync()();
      void resync()();
    });
    expect(joinOrReconnect).toHaveBeenCalledTimes(2);

    await act(async () => {
      resolve({ mode: 'RECONNECTED', room: { ...ROOM, gameType: 'ONE_CARD' } });
      await first;
    });

    // 끝난 뒤에는 다시 부를 수 있다.
    joinOrReconnect.mockResolvedValue({ mode: 'RECONNECTED', room: { ...ROOM, gameType: 'ONE_CARD' } });
    await act(async () => {
      await resync()();
    });
    expect(joinOrReconnect).toHaveBeenCalledTimes(3);
  });
});

/**
 * D-135 — 방장 강제 종료 버튼은 게임판 머리 줄에 있다(예전 대기실 셸의 버튼은 게임 중에 그려지지 않는 죽은 코드였다). 방장이고 방장 말고
 * 모두 봇인 판에서만 게임판에 `onAbort` 를 넘긴다 — 서버도 같은 조건으로 거절한다(HUMAN_PLAYERS_PRESENT).
 */
describe('RoomPage — 게임판 강제 종료 (D-135)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    skullProps.length = 0;
    tichuProps.length = 0;
    oneCardProps.length = 0;
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  const botGame = (gameType: string, extra: Partial<Room> = {}) =>
    ({
      gameType,
      status: 'IN_GAME',
      playerIds: [1, 900, 901, 902],
      playerCount: 4,
      botSeats: [1, 2, 3],
      fillWithBots: true,
      ...extra,
    }) as Partial<Room>;

  it.each([
    ['ONE_CARD', 'onecard-table', oneCardProps],
    ['SKULL_KING', 'skullking-table', skullProps],
    ['TICHU', 'tichu-table', tichuProps],
  ] as const)('%s — 방장이고 나머지가 모두 봇이면 게임판에 강제 종료를 넘긴다', async (gameType, testId, props) => {
    loadGame.mockResolvedValue(game(gameType, []));
    renderRoom(botGame(gameType));
    await screen.findByTestId(testId);

    expect(typeof props.at(-1)!.onAbort).toBe('function');
  });

  it('사람이 한 명이라도 더 있으면 넘기지 않는다', async () => {
    loadGame.mockResolvedValue(game('ONE_CARD', []));
    renderRoom(botGame('ONE_CARD', { playerIds: [1, 2, 900, 901], botSeats: [2, 3] }));
    await screen.findByTestId('onecard-table');

    expect(oneCardProps.at(-1)!.onAbort).toBeUndefined();
  });

  it('방장이 아니면 넘기지 않는다', async () => {
    loadGame.mockResolvedValue(game('SKULL_KING', []));
    renderRoom(botGame('SKULL_KING', { hostId: 900 } as Partial<Room>));
    await screen.findByTestId('skullking-table');

    expect(skullProps.at(-1)!.onAbort).toBeUndefined();
  });

  it('관전 중이면 넘기지 않는다', async () => {
    loadGame.mockResolvedValue(game('TICHU', []));
    renderRoom(botGame('TICHU', { hostId: 1, playerIds: [5, 900, 901, 902], spectatorIds: [1] } as Partial<Room>), 'SPECTATING');
    await screen.findByTestId('tichu-table');

    expect(tichuProps.at(-1)!.onAbort).toBeUndefined();
  });

  it('누르면 확인을 받은 뒤 강제 종료를 부르고, 취소하면 부르지 않는다', async () => {
    const { roomsApi } = await import('@/api/rooms');
    loadGame.mockResolvedValue(game('ONE_CARD', []));
    renderRoom(botGame('ONE_CARD'));
    await screen.findByTestId('onecard-table');
    const onAbort = oneCardProps.at(-1)!.onAbort as () => Promise<void>;

    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false);
    await act(async () => {
      await onAbort();
    });
    expect(roomsApi.abort).not.toHaveBeenCalled();

    confirm.mockReturnValueOnce(true);
    await act(async () => {
      await onAbort();
    });
    expect(roomsApi.abort).toHaveBeenCalledWith('tok', 'r1');
    confirm.mockRestore();
  });

  it('대기실 셸에는 강제 종료 버튼이 없다 — 게임 중에는 셸이 그려지지 않는다', async () => {
    loadGame.mockResolvedValue(game('ONE_CARD', []));
    renderRoom({ gameType: 'ONE_CARD', status: 'WAITING' });
    await screen.findByText('테스트 방');

    expect(screen.queryByRole('button', { name: '게임 종료' })).toBeNull();
  });
});

/**
 * D-136 — 친구와 하기 대기실의 "지금 N명으로 시작". 방장이고, 사람이 게임 최소 인원 이상이며 정원 미만이고, 방장 말고 모두 준비했을 때만
 * 보인다(서버도 같은 조건 — room_start_now.lua). 티츄는 4인 고정이라 없다. 봇 자동 준비 안내는 친구와 하기에 봇이 없어 지웠다.
 */
describe('RoomPage — 지금 인원으로 시작 (D-136)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  const oneCard = { ...game('ONE_CARD', []), minPlayers: 2, maxPlayers: 6 };
  const tichu = { ...game('TICHU', ['TEAMS']), minPlayers: 4, maxPlayers: 4 };
  const waiting = (extra: Partial<Room>) =>
    ({ gameType: 'ONE_CARD', status: 'WAITING', capacity: 4, playerIds: [1, 2, 3], playerCount: 3, readyUserIds: [2, 3], ...extra }) as Partial<Room>;

  it('방장이고 방장 말고 모두 준비했으며 최소 인원 이상·정원 미만이면 "지금 3명으로 시작" — 누르면 시작을 부른다', async () => {
    const { roomsApi } = await import('@/api/rooms');
    vi.mocked(roomsApi.startNow).mockResolvedValue({ ...ROOM, ...waiting({ status: 'IN_GAME', capacity: 3 }) } as Room);
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({}));

    const button = await screen.findByRole('button', { name: '지금 3명으로 시작' });
    await act(async () => {
      fireEvent.click(button);
    });
    expect(roomsApi.startNow).toHaveBeenCalledWith('tok', 'r1');
  });

  it('준비 안 한 사람이 있으면 버튼 대신 안내만', async () => {
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({ readyUserIds: [2] }));

    expect(await screen.findByText('2명 이상 모이고 모두 준비하면 지금 인원으로 시작할 수 있어요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /명으로 시작/ })).toBeNull();
  });

  it('방장이 아니면 버튼도 안내도 없다', async () => {
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({ hostId: 2 } as Partial<Room>));
    await screen.findByText('테스트 방');

    expect(screen.queryByRole('button', { name: /명으로 시작/ })).toBeNull();
    expect(screen.queryByText(/명 이상 모이고 모두 준비하면/)).toBeNull(); // 방장용 안내(방장 아닌 사람에게는 FR-5 의 준비 권유)
  });

  it('정원이 다 찼으면 없다 — 모두 준비하면 저절로 시작한다', async () => {
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({ capacity: 3 }));
    await screen.findByText('테스트 방');

    expect(screen.queryByRole('button', { name: /명으로 시작/ })).toBeNull();
  });

  it('티츄(4인 고정)는 없다', async () => {
    loadGame.mockResolvedValue(tichu);
    renderRoom(waiting({ gameType: 'TICHU' }));
    await screen.findByText('팀 배정');

    expect(screen.queryByRole('button', { name: /명으로 시작/ })).toBeNull();
    expect(screen.queryByText(/지금 인원으로 시작할 수 있어요/)).toBeNull();
  });

  it('경합으로 거절되면 서버 문구가 아니라 한국어로 알린다', async () => {
    const { roomsApi } = await import('@/api/rooms');
    vi.mocked(roomsApi.startNow).mockRejectedValue(new ApiError(409, { error: { code: 'PLAYERS_NOT_READY', message: 'Every player must be ready' } }));
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({}));

    const button = await screen.findByRole('button', { name: '지금 3명으로 시작' });
    await act(async () => {
      fireEvent.click(button);
    });
    expect(screen.getByText('아직 준비하지 않은 사람이 있어요')).toBeInTheDocument();
    expect(screen.queryByText('Every player must be ready')).toBeNull();
  });

  it('대기실 안내에 "봇은 자동 준비"가 없다 — 친구와 하기에는 봇이 없다', async () => {
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({}));
    await screen.findByText('테스트 방');

    expect(screen.queryByText(/봇은 자동 준비/)).toBeNull();
  });
});

describe('RoomPage — 게임판에 연습 표시 넘기기 (D-136)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    oneCardProps.length = 0;
    skullProps.length = 0;
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  it('봇과 연습 방이면 게임판에 practice 를 넘긴다', async () => {
    loadGame.mockResolvedValue(game('ONE_CARD', []));
    renderRoom({ gameType: 'ONE_CARD', status: 'IN_GAME', fillWithBots: true, playerIds: [1, 900], botSeats: [1] } as Partial<Room>);
    await screen.findByTestId('onecard-table');

    expect(oneCardProps.at(-1)!.practice).toBe(true);
  });

  it('친구와 하기 방이면 넘기지 않는다', async () => {
    loadGame.mockResolvedValue(game('SKULL_KING', []));
    renderRoom({ gameType: 'SKULL_KING', status: 'IN_GAME', fillWithBots: false, playerIds: [1, 2] } as Partial<Room>);
    await screen.findByTestId('skullking-table');

    expect(skullProps.at(-1)!.practice).toBe(false);
  });
});

/** D-136 최종 리뷰 FR-4·FR-5 — 지금 시작 오류 줄의 수명·네트워크 오류·요청 중 잠금, 방장 아닌 사람의 준비 권유와 방장에게 준비 안 한 사람 이름. */
describe('RoomPage — 지금 시작 안내·오류 (D-136 후속)', () => {
  const metaCallback = () => vi.mocked(useRoomMeta).mock.calls.at(-1)![2];

  beforeEach(() => {
    vi.clearAllMocks();
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 1, username: 'host' }, { userId: 2, username: '철수' }, { userId: 3, username: '영희' }] });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'host' } as never });
  });

  const oneCard = { ...game('ONE_CARD', []), minPlayers: 2, maxPlayers: 6 };
  const waiting = (extra: Partial<Room>) =>
    ({ ...ROOM, gameType: 'ONE_CARD', status: 'WAITING', capacity: 4, playerIds: [1, 2, 3], playerCount: 3, readyUserIds: [2, 3], ...extra }) as Room;

  it('거절 안내는 준비 상태가 바뀌면 사라진다', async () => {
    const { roomsApi } = await import('@/api/rooms');
    vi.mocked(roomsApi.startNow).mockRejectedValue(new ApiError(409, { error: { code: 'PLAYERS_NOT_READY', message: 'x' } }));
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({}));
    const button = await screen.findByRole('button', { name: '지금 3명으로 시작' });
    await act(async () => {
      fireEvent.click(button);
    });
    expect(screen.getByText('아직 준비하지 않은 사람이 있어요')).toBeInTheDocument();

    act(() => {
      metaCallback()(waiting({ readyUserIds: [2] }));
    });
    expect(screen.queryByText('아직 준비하지 않은 사람이 있어요')).toBeNull();
  });

  it('네트워크 오류도 알린다', async () => {
    const { roomsApi } = await import('@/api/rooms');
    vi.mocked(roomsApi.startNow).mockRejectedValue(new TypeError('Failed to fetch'));
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({}));
    const button = await screen.findByRole('button', { name: '지금 3명으로 시작' });
    await act(async () => {
      fireEvent.click(button);
    });

    expect(screen.getByText('시작하지 못했어요 — 다시 눌러 주세요')).toBeInTheDocument();
  });

  it('요청 중에는 버튼이 잠긴다', async () => {
    const { roomsApi } = await import('@/api/rooms');
    vi.mocked(roomsApi.startNow).mockReturnValue(new Promise(() => {}));
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({}));
    const button = await screen.findByRole('button', { name: '지금 3명으로 시작' });
    await act(async () => {
      fireEvent.click(button);
    });

    expect(button).toBeDisabled();
  });

  it('방장에게는 준비 안 한 사람 이름을 알려 준다', async () => {
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({ readyUserIds: [2] }));

    expect(await screen.findByText('준비 안 한 사람: 영희')).toBeInTheDocument();
  });

  it('방장이 아닌 사람에게는 준비해 두라고 권한다 — 방장이 지금 인원으로 시작할 수 있다', async () => {
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({ hostId: 2, readyUserIds: [] } as Partial<Room>));

    expect(await screen.findByText('방장이 지금 인원으로 시작할 수 있어요 — 준비해 두세요')).toBeInTheDocument();
  });

  it('이미 준비했으면 권하지 않는다', async () => {
    loadGame.mockResolvedValue(oneCard);
    renderRoom(waiting({ hostId: 2, readyUserIds: [1] } as Partial<Room>));
    await screen.findByText('테스트 방');

    expect(screen.queryByText(/준비해 두세요/)).toBeNull();
  });
});

/**
 * D-137 — 대기실을 관전 중이고 빈자리가 있으면 [자리에 앉기](`/join` — 서버가 같은 원자 구간에서 관전자 목록에서 뺀다). 진행 중이거나 꽉 찼으면
 * 없다. 대기실 화면 재동기화(D-134)는 관전자를 빈자리에 저절로 앉히지 않고 관전으로 돌려놓는다.
 */
describe('RoomPage — 관전자 자리에 앉기 (D-137)', () => {
  const resync = () => vi.mocked(useRoomMeta).mock.calls.at(-1)![4]!;

  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    Object.values(GAME_TUTORIALS).forEach((t) => markTutorialSeen(t.seenKey));
    names.mockResolvedValue({ names: [{ userId: 2, username: '방장' }] });
    loadGame.mockResolvedValue({ ...game('ONE_CARD', []), minPlayers: 2, maxPlayers: 6 });
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: 'me' } as never });
  });

  const watching = (extra: Partial<Room> = {}) =>
    ({ gameType: 'ONE_CARD', status: 'WAITING', hostId: 2, capacity: 4, playerIds: [2, 3], playerCount: 2, spectatorIds: [1], ...extra }) as Partial<Room>;

  it('빈자리가 있는 대기실을 관전 중이면 [자리에 앉기] — 누르면 입장하고 내 자리가 된다', async () => {
    const { roomsApi } = await import('@/api/rooms');
    vi.mocked(roomsApi.join).mockResolvedValue({ ...ROOM, ...watching({ playerIds: [2, 3, 1], playerCount: 3, spectatorIds: [] }) } as Room);
    renderRoom(watching(), 'SPECTATING');

    const sit = await screen.findByRole('button', { name: '자리에 앉기' });
    await act(async () => {
      fireEvent.click(sit);
    });

    expect(roomsApi.join).toHaveBeenCalledWith('tok', 'r1');
    expect(screen.queryByRole('button', { name: '자리에 앉기' })).toBeNull();
    expect(screen.getByRole('button', { name: '준비' })).toBeInTheDocument();
  });

  it('꽉 찬 대기실이면 없다', async () => {
    renderRoom(watching({ playerIds: [2, 3, 4, 5], playerCount: 4 }), 'SPECTATING');
    await screen.findByText('테스트 방');

    expect(screen.queryByRole('button', { name: '자리에 앉기' })).toBeNull();
  });

  it('플레이어에게는 없다', async () => {
    renderRoom(watching({ playerIds: [2, 1], spectatorIds: [] }));
    await screen.findByText('테스트 방');

    expect(screen.queryByRole('button', { name: '자리에 앉기' })).toBeNull();
  });

  it('그사이 자리가 차면 서버 문구가 아니라 한국어로 알리고 관전을 이어 간다', async () => {
    const { roomsApi } = await import('@/api/rooms');
    vi.mocked(roomsApi.join).mockRejectedValue(new ApiError(409, { error: { code: 'ROOM_FULL', message: 'Room is full' } }));
    renderRoom(watching(), 'SPECTATING');

    const sit = await screen.findByRole('button', { name: '자리에 앉기' });
    await act(async () => {
      fireEvent.click(sit);
    });

    expect(screen.getByText('자리가 방금 찼어요 — 관전을 이어 가요')).toBeInTheDocument();
    expect(screen.queryByText('Room is full')).toBeNull();
    expect(screen.getByText(/관전 중/)).toBeInTheDocument();
  });

  it('재동기화는 관전자를 관전으로 돌려놓는다 — 빈자리에 저절로 앉히지 않는다', async () => {
    renderRoom(watching(), 'SPECTATING');
    await screen.findByText('테스트 방');
    joinOrReconnect.mockClear();
    spectate.mockResolvedValue({ ...ROOM, ...watching() });

    await act(async () => {
      resync()();
    });

    expect(spectate).toHaveBeenCalledWith('tok', 'r1');
    expect(joinOrReconnect).not.toHaveBeenCalled();
  });
});
