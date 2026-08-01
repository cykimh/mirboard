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

describe('CreateRoomModal — 인원 선택 (D-99)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createMock.mockResolvedValue({ roomId: 'room-1' });
  });

  it('인원 고정 게임에는 인원 선택을 노출하지 않는다', () => {
    openModal([FIXED]);
    expect(screen.queryByRole('group', { name: '인원 선택' })).toBeNull();
  });

  it('인원 고정 게임은 capacity 를 보내지 않는다 (요청 본문 무변경)', async () => {
    openModal([FIXED]);
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBeUndefined();
  });

  it('인원 가변 게임에는 min~max 범위의 인원 선택을 노출한다', () => {
    openModal([VARIABLE]);
    const picker = screen.getByRole('group', { name: '인원 선택' });
    const seats = Array.from(picker.querySelectorAll('button')).map((b) => b.textContent);
    expect(seats).toEqual(['2', '3', '4', '5', '6', '7', '8']);
  });

  it('인원 가변 게임의 기본 인원은 maxPlayers (서버 기본값과 동일)', async () => {
    openModal([VARIABLE]);
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBe(8);
  });

  it('호스트가 고른 인원을 그대로 보낸다', async () => {
    openModal([VARIABLE]);
    fireEvent.click(screen.getByRole('radio', { name: '4' }));
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBe(4);
  });
});

describe('CreateRoomModal — 방 옵션 게이팅 (D-106)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createMock.mockResolvedValue({ roomId: 'room-1' });
  });

  it('옵션을 쓰는 게임에는 목표 점수·판돈을 노출한다', () => {
    openModal([FIXED]);
    expect(screen.getByText('목표 점수')).toBeTruthy();
    expect(screen.getByLabelText('판돈(내기) 켜기/끄기')).toBeTruthy();
  });

  it('옵션을 안 쓰는 게임에는 목표 점수·판돈을 노출하지 않는다', () => {
    openModal([VARIABLE]);
    expect(screen.queryByText('목표 점수')).toBeNull();
    expect(screen.queryByLabelText('판돈(내기) 켜기/끄기')).toBeNull();
  });

  it('게임과 무관한 옵션(턴 제한·봇 채우기)은 계속 노출한다', () => {
    openModal([VARIABLE]);
    expect(screen.getByText('턴 제한')).toBeTruthy();
    expect(screen.getByText(/빈 좌석 봇으로 채우기/)).toBeTruthy();
  });

  it('미지원 게임은 targetScore·stake 를 아예 보내지 않는다', async () => {
    openModal([VARIABLE]);
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    const opts = lastCreateOpts();
    expect(opts.targetScore).toBeUndefined();
    expect(opts.stake).toBeUndefined();
    // 게임 중립 옵션은 그대로 실려야 한다 — 게이팅이 과하게 먹지 않았는지.
    expect(opts.turnSeconds).toBe(0);
  });

  it('지원 게임은 targetScore·stake 를 실어 보낸다', async () => {
    openModal([FIXED]);
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    const opts = lastCreateOpts();
    expect(opts.targetScore).toBe(1000);
    expect(opts.stake).toBe(0);
  });
});
