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

/** 원카드 — 2~6인 가변, 방 만들기 처음 선택을 선언한다(D-130: 4명·턴 제한 30초). */
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
 * D-143 — 친구와 하기는 인원을 고르지 않는다. 최소·최대 인원만 알리고 정원은 게임 최대 인원(대기실에서 방장이 시작한다). 봇과 연습만 봇 수를
 * 정하려고 인원을 고른다(아래 D-136 묶음).
 */
describe('CreateRoomModal — 인원 (D-99·D-143)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createMock.mockResolvedValue({ roomId: 'room-1' });
  });

  it('친구와 하기에는 인원 선택이 없고 최소·최대 인원과 방장 시작을 알린다', () => {
    openModal([VARIABLE]);
    expect(screen.queryByRole('group', { name: '인원 선택' })).toBeNull();
    expect(screen.getByText('최소 2명 · 최대 8명 — 대기실에서 방장이 시작해요')).toBeInTheDocument();
  });

  it('친구와 하기의 정원은 게임 최대 인원이다 — 게임이 선언한 처음 인원이 아니다', async () => {
    openModal([DECLARED]);
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBe(6);
  });

  it('인원 고정 게임은 인원만 알리고 capacity 를 보내지 않는다 (요청 본문 무변경)', async () => {
    openModal([FIXED]);
    expect(screen.queryByRole('group', { name: '인원 선택' })).toBeNull();
    expect(screen.getByText('4명 — 모이면 대기실에서 방장이 시작해요')).toBeInTheDocument();
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBeUndefined();
  });

  it('봇과 연습에는 min~max 범위의 인원 선택이 있다', () => {
    openModal([VARIABLE]);
    fireEvent.click(screen.getByRole('radio', { name: '봇과 연습' }));
    const picker = screen.getByRole('group', { name: '인원 선택' });
    const seats = Array.from(picker.querySelectorAll('button')).map((b) => b.textContent);
    expect(seats).toEqual(['2', '3', '4', '5', '6', '7', '8']);
    expect(screen.queryByText(/대기실에서 방장이 시작해요/)).toBeNull();
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

  it('게임과 무관한 옵션(턴 제한)은 계속 노출한다', () => {
    openModal([VARIABLE]);
    expect(screen.getByText('턴 제한')).toBeTruthy();
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

/**
 * D-136 — 방 만들기 두 갈래. "친구와 하기"(처음 선택 — 게스트 포함, 봇 없음)와 "봇과 연습"(방장 + 봇으로 곧바로 시작 — 이름·목표 점수·
 * 판돈 없음). 요청의 `fillWithBots` 가 곧 연습 방이다(서버가 방장 준비까지 넣어 바로 시작하고 목록에 올리지 않는다).
 */
describe('CreateRoomModal — 친구와 하기 / 봇과 연습 (D-136)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createMock.mockResolvedValue({ roomId: 'room-1' });
  });

  const practice = () => fireEvent.click(screen.getByRole('radio', { name: '봇과 연습' }));

  it('처음 선택은 친구와 하기 — 봇 옵션이 없고 fillWithBots=false 로 보낸다', async () => {
    openModal([FIXED]);
    expect(screen.getByRole('radio', { name: '친구와 하기' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByText(/봇으로 채우기/)).toBeNull();
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().fillWithBots).toBe(false);
  });

  it('봇과 연습은 이름·목표 점수·판돈 없이 "연습 시작" — fillWithBots=true, 이름은 "{게임} 연습"', async () => {
    openModal([FIXED]);
    practice();
    expect(screen.queryByLabelText('방 이름')).toBeNull();
    expect(screen.queryByText('목표 점수')).toBeNull();
    expect(screen.queryByLabelText('판돈(내기) 켜기/끄기')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '연습 시작' }));
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    const [, name, gameType, opts] = createMock.mock.calls[0];
    expect(name).toBe('티츄 연습');
    expect(gameType).toBe('TICHU');
    expect(opts.fillWithBots).toBe(true);
    expect(opts.stake).toBeUndefined();
    expect(opts.targetScore).toBeUndefined();
  });

  it('봇과 연습도 인원 가변 게임은 인원을 고르고(봇 수 안내) 그대로 보낸다', async () => {
    openModal([DECLARED]);
    practice();
    expect(screen.getByText('봇 3명과 함께')).toBeTruthy();
    fireEvent.click(screen.getByRole('radio', { name: '2' }));
    expect(screen.getByText('봇 1명과 함께')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '연습 시작' }));
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().capacity).toBe(2);
    expect(lastCreateOpts().turnSeconds).toBe(30);
  });

  // 최종 리뷰 FR-1 — 티츄는 연습이면 목표 점수·판돈을 숨기는데, 그 게이팅이 바뀔 때 턴 제한까지 처음 값으로 돌아갔다(원카드는 그대로).
  it('방 종류를 바꿔도 고른 턴 제한은 그대로다 (티츄)', async () => {
    openModal([FIXED]);
    fireEvent.click(screen.getByRole('radio', { name: '60초' }));
    practice();
    expect(screen.getByRole('radio', { name: '60초' })).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(screen.getByRole('button', { name: '연습 시작' }));
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().turnSeconds).toBe(60);
  });

  it('다시 열면 처음 선택(친구와 하기)으로 돌아간다', () => {
    const view = render(
      <MemoryRouter>
        <CreateRoomModal open token="tok" availableGames={[FIXED]} onClose={() => {}} onError={() => {}} />
      </MemoryRouter>,
    );
    practice();
    view.rerender(
      <MemoryRouter>
        <CreateRoomModal open={false} token="tok" availableGames={[FIXED]} onClose={() => {}} onError={() => {}} />
      </MemoryRouter>,
    );
    view.rerender(
      <MemoryRouter>
        <CreateRoomModal open token="tok" availableGames={[FIXED]} onClose={() => {}} onError={() => {}} />
      </MemoryRouter>,
    );

    expect(screen.getByRole('radio', { name: '친구와 하기' })).toHaveAttribute('aria-checked', 'true');
  });

  it('판돈을 켠 뒤 봇과 연습으로 바꾸면 판돈을 보내지 않는다 (판돈 방 봇 금지)', async () => {
    openModal([FIXED]);
    fireEvent.click(screen.getByLabelText('판돈(내기) 켜기/끄기'));
    practice();
    fireEvent.click(screen.getByRole('button', { name: '연습 시작' }));
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().stake).toBeUndefined();
    expect(lastCreateOpts().fillWithBots).toBe(true);
  });
});

describe('CreateRoomModal — 게임이 선언한 처음 선택 (D-130)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createMock.mockResolvedValue({ roomId: 'room-1' });
  });

  const practice = () => fireEvent.click(screen.getByRole('radio', { name: '봇과 연습' }));

  it('게임이 선언한 턴 제한을 처음부터 골라 두고, 봇과 연습의 인원도 선언값이다', () => {
    openModal([DECLARED]);
    expect(screen.getByRole('radio', { name: '30초' })).toHaveAttribute('aria-checked', 'true');

    practice();
    expect(screen.getByRole('radio', { name: '4' })).toHaveAttribute('aria-checked', 'true');
  });

  it('바꾸지 않으면 선언한 턴 제한으로 보낸다', async () => {
    openModal([DECLARED]);
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());

    expect(lastCreateOpts().turnSeconds).toBe(30);
  });

  it('사용자가 바꾼 턴 제한을 그대로 보낸다', async () => {
    openModal([DECLARED]);
    fireEvent.click(screen.getByRole('radio', { name: '끔' }));
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());

    expect(lastCreateOpts().turnSeconds).toBe(0);
  });

  /**
   * 게임을 바꾸면 이전 게임에서 고른 인원·턴 제한을 새 게임의 처음 선택으로 되돌린다 — 티츄·스컬킹 사용자에게 걸리는 유일한
   * 동작 변화(예전엔 티츄에서 고른 60초가 스컬킹으로 바꿔도 남았다). 폼 안의 Radix Select 는 숨은 네이티브 select 를 함께
   * 렌더하므로 그걸로 게임을 바꾼다. 인원은 봇과 연습에만 있다(D-143).
   */
  function switchGame(id: string) {
    const native = document.querySelector('select[aria-hidden="true"]') as HTMLSelectElement;
    fireEvent.change(native, { target: { value: id } });
  }
  const checked = (name: string) => screen.getByRole('radio', { name }).getAttribute('aria-checked');

  it('게임을 바꾸면 고른 인원·턴 제한이 새 게임의 처음 선택으로 되돌아간다', async () => {
    openModal([DECLARED, VARIABLE]);
    practice();
    fireEvent.click(screen.getByRole('radio', { name: '6' }));
    fireEvent.click(screen.getByRole('radio', { name: '60초' }));

    switchGame('SKULL_KING');

    await waitFor(() => expect(checked('끔')).toBe('true'));
    expect(checked('8')).toBe('true');
  });

  it('선언 없는 게임에서 고른 턴 제한은 선언한 게임으로 바꾸면 선언값으로 바뀐다', async () => {
    openModal([VARIABLE, DECLARED]);
    practice();
    fireEvent.click(screen.getByRole('radio', { name: '60초' }));

    switchGame('ONE_CARD');

    await waitFor(() => expect(checked('30초')).toBe('true'));
    expect(checked('4')).toBe('true');
  });
});

/** D-154 — 턴 제한 선택지도 게임이 선언한다(카탈로그 `turnSecondsChoices`). 원카드는 끔·5·10·20·30초, 없으면 끔·30·60·90. */
describe('CreateRoomModal — 게임이 선언한 턴 제한 선택지 (D-154)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    createMock.mockResolvedValue({ roomId: 'room-1' });
  });

  const labels = () =>
    screen
      .getAllByRole('radio')
      .map((r) => r.textContent)
      .filter((t) => t === '끔' || /초$/.test(t ?? ''));

  it('원카드는 짧은 선택지 — 5초를 골라 보낼 수 있다', async () => {
    openModal([{ ...DECLARED, turnSecondsChoices: [0, 5, 10, 20, 30] }]);
    expect(labels()).toEqual(['끔', '5초', '10초', '20초', '30초']);

    fireEvent.click(screen.getByRole('radio', { name: '5초' }));
    submit();
    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(lastCreateOpts().turnSeconds).toBe(5);
  });

  it('선택지를 안 실은 게임은 끔·30·60·90초', () => {
    openModal([FIXED]);
    expect(labels()).toEqual(['끔', '30초', '60초', '90초']);
  });
});
