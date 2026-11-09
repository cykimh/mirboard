import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CreateRoomModal } from './CreateRoomModal';
import { roomsApi } from '@/api/rooms';
import type { GameSummary } from '@/types/api';

vi.mock('@/api/rooms', () => ({ roomsApi: { create: vi.fn() } }));

const createMock = roomsApi.create as ReturnType<typeof vi.fn>;

/** 티츄 — min=max=4 (인원 고정), 방 옵션 3종 전부 사용(D-106). */
const FIXED: GameSummary = {
  id: 'TICHU',
  displayName: '티츄',
  shortDescription: '',
  minPlayers: 4,
  maxPlayers: 4,
  status: 'AVAILABLE',
  supportedRoomOptions: ['TARGET_SCORE', 'TEAMS', 'BETTING'],
};

/** 스컬킹 — 2~8인 가변, 방 옵션 없음(10R 고정·개인전·칩 미지원). */
const VARIABLE: GameSummary = {
  id: 'SKULL_KING',
  displayName: '스컬킹',
  shortDescription: '',
  minPlayers: 2,
  maxPlayers: 8,
  status: 'AVAILABLE',
  supportedRoomOptions: [],
};

/** 원카드 — 2~6인 가변, 처음 선택을 선언한다(D-130: 4명·턴 제한 30초 — D-174 뒤 방 만들기는 쓰지 않는다). */
const DECLARED: GameSummary = {
  id: 'ONE_CARD',
  displayName: '원카드',
  shortDescription: '',
  minPlayers: 2,
  maxPlayers: 6,
  status: 'AVAILABLE',
  supportedRoomOptions: [],
  defaultPlayers: 4,
  defaultTurnSeconds: 30,
};

/** availableGames 를 한 개만 넘기면 모달이 그 게임을 자동 선택한다(Radix Select 조작 회피). */
function openModal(games: GameSummary[]) {
  render(
    <MemoryRouter>
      <CreateRoomModal
        open
        token="tok"
        availableGames={games}
        onClose={() => {}}
        onError={() => {}}
      />
    </MemoryRouter>,
  );
}

function submit() {
  fireEvent.change(screen.getByLabelText('방 이름'), { target: { value: '테스트 방' } });
  fireEvent.click(screen.getByRole('button', { name: '방 만들기' }));
}

function lastCreateOpts() {
  return createMock.mock.calls[0][3] as Record<string, unknown>;
}

/**
 * D-143 — 인원은 고르지 않는다. 최소·최대 인원만 알리고 정원은 게임 최대 인원(대기실에서 방장이 시작한다).
 */
describe('CreateRoomModal — 인원 (D-99·D-143)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createMock.mockResolvedValue({ roomId: 'room-1' });
  });

  it('인원 선택이 없고 최소·최대 인원과 방장 시작을 알린다', () => {
    openModal([VARIABLE]);
    expect(screen.queryByRole('group', { name: '인원 선택' })).toBeNull();
    expect(screen.getByText('최소 2명 · 최대 8명 — 대기실에서 방장이 시작해요')).toBeInTheDocument();
  });

  it('정원은 게임 최대 인원이다 — 게임이 선언한 처음 인원이 아니다', async () => {
    openModal([DECLARED]);
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBe(6);
  });

  it('인원 고정 게임은 인원만 알리고 capacity 를 보내지 않는다 (요청 본문 무변경)', async () => {
    openModal([FIXED]);
    expect(screen.getByText('4명 — 모이면 대기실에서 방장이 시작해요')).toBeInTheDocument();
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBeUndefined();
  });
});

/**
 * D-161·D-174 — 게임·방 이름만 고른다. 턴 제한·목표 점수·판돈은 대기실에서 방장이 바꾸고(`RoomSettingsPanel`), 봇도 대기실의
 * [빈자리 봇으로 채우기]로 앉힌다 — 요청에 싣지 않는다(서버가 게임 선언 기본으로 연다).
 */
describe('CreateRoomModal — 방 종류 없이 게임·이름만 (D-174)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createMock.mockResolvedValue({ roomId: 'room-1' });
  });

  it('방 종류·인원 선택·턴 제한·목표 점수·판돈이 없고, 봇과 설정은 대기실에서라고 알린다', () => {
    openModal([FIXED]);
    expect(screen.queryByRole('group', { name: '방 종류' })).toBeNull();
    expect(screen.queryByRole('radio', { name: '봇과 연습' })).toBeNull();
    expect(screen.queryByText('턴 제한')).toBeNull();
    expect(screen.queryByText('목표 점수')).toBeNull();
    expect(screen.queryByLabelText('판돈(내기) 켜기/끄기')).toBeNull();
    expect(screen.getByText(/대기실에서 \[빈자리 봇으로 채우기\]/)).toBeInTheDocument();
  });

  it('fillWithBots·targetScore·stake·turnSeconds 를 보내지 않는다', async () => {
    openModal([FIXED]);
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    const opts = lastCreateOpts();
    expect(opts.fillWithBots).toBeUndefined();
    expect(opts.targetScore).toBeUndefined();
    expect(opts.stake).toBeUndefined();
    expect(opts.turnSeconds).toBeUndefined();
    expect(createMock.mock.calls[0][1]).toBe('테스트 방');
  });

  it('방 이름이 없으면 만들 수 없다', () => {
    openModal([FIXED]);
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeDisabled();
  });

  it('D-176 — 정원을 방 설정이 정하는 게임(값을 가진 방 옵션)은 capacity 를 보내지 않는다', async () => {
    const BY_SETTINGS: GameSummary = {
      ...VARIABLE,
      id: 'OMOK',
      displayName: '오목',
      minPlayers: 2,
      maxPlayers: 4,
      roomOptionChoices: [{ option: 'TEAM_MODE', values: ['SOLO', 'TEAMS_2V2'], defaultValue: 'SOLO' }],
    };
    openModal([BY_SETTINGS]);
    expect(screen.getByText('2~4명 — 대기실의 게임 설정(모드)이 정원을 정해요')).toBeInTheDocument();
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBeUndefined();
  });
});
