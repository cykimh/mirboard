import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RoomSettingsPanel } from './RoomSettingsPanel';
import type { GameSummary, Room } from '@/types/api';

/** D-161 — 대기실 게임 설정. 게임이 선언한 설정만 보이고(D-106), 방장만 바꾼다. */
const game = (id: string, options: GameSummary['supportedRoomOptions'], choices?: number[]): GameSummary => ({
  id,
  displayName: id,
  shortDescription: '',
  minPlayers: 2,
  maxPlayers: 4,
  status: 'AVAILABLE',
  supportedRoomOptions: options,
  turnSecondsChoices: choices,
});

const room = (extra: Partial<Room> = {}): Room =>
  ({
    roomId: 'r1',
    name: '방',
    gameType: 'TICHU',
    hostId: 1,
    status: 'WAITING',
    capacity: 4,
    playerCount: 2,
    playerIds: [1, 2],
    spectatorIds: [],
    teamPolicy: 'SEQUENTIAL',
    createdAt: 0,
    fillWithBots: false,
    botSeats: [],
    targetScore: 1000,
    turnSeconds: 0,
    stake: 0,
    readyUserIds: [],
    ...extra,
  }) as Room;

describe('RoomSettingsPanel (D-161)', () => {
  it('티츄는 턴 제한·목표 점수·판돈, 원카드! 버튼 크기는 없다', () => {
    render(<RoomSettingsPanel room={room()} game={game('TICHU', ['TARGET_SCORE', 'TEAMS', 'BETTING'])} canEdit onChange={() => {}} />);

    expect(screen.getByRole('group', { name: '턴 제한' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: '목표 점수' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: '💰 판돈' })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: '원카드! 버튼 크기' })).toBeNull();
  });

  it('원카드는 게임이 선언한 턴 선택지와 버튼 크기 — 지금 값이 골라져 있다', () => {
    render(
      <RoomSettingsPanel
        room={room({ gameType: 'ONE_CARD', turnSeconds: 10, raceButtonSize: 'LARGE' })}
        game={game('ONE_CARD', ['RACE_BUTTON_SIZE'], [0, 5, 10, 20, 30])}
        canEdit
        onChange={() => {}}
      />,
    );

    const turn = screen.getByRole('group', { name: '턴 제한' });
    expect(within(turn).getAllByRole('radio').map((r) => r.textContent)).toEqual(['끔', '5초', '10초', '20초', '30초']);
    expect(within(turn).getByRole('radio', { name: '10초' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: '크게' })).toHaveAttribute('aria-checked', 'true');
    // D-165 — 찾기 난이도는 게임이 선언해야 보인다(여기 원카드 픽스처는 크기만).
    expect(screen.queryByRole('group', { name: '원카드! 찾기 난이도' })).toBeNull();
    expect(screen.queryByRole('group', { name: '목표 점수' })).toBeNull();
  });

  it('고르면 바꾼 항목만 보낸다 — 지금 값을 다시 눌러도 보내지 않는다', () => {
    const onChange = vi.fn();
    render(<RoomSettingsPanel room={room()} game={game('TICHU', ['TARGET_SCORE', 'BETTING'])} canEdit onChange={onChange} />);

    fireEvent.click(screen.getByRole('radio', { name: '500' }));
    fireEvent.click(screen.getByRole('radio', { name: '100칩' }));
    fireEvent.click(screen.getByRole('radio', { name: '끔' }));

    expect(onChange.mock.calls).toEqual([[{ targetScore: 500 }], [{ stake: 100 }]]);
  });

  it('방장이 아니면 지금 값만 보이고 고를 수 없다', () => {
    render(
      <RoomSettingsPanel room={room({ stake: 50 })} game={game('TICHU', ['BETTING'])} canEdit={false} onChange={() => {}} />,
    );

    expect(screen.queryByRole('radio')).toBeNull();
    expect(screen.getByText('끔')).toBeInTheDocument();
    expect(screen.getByText('50칩')).toBeInTheDocument();
    expect(screen.getByText('게임 설정은 방장이 바꿔요.')).toBeInTheDocument();
  });

  it('D-165 — 찾기 난이도를 선언한 게임이면 보통·어려움·매우 어려움 중 고른다', () => {
    const onChange = vi.fn();
    render(
      <RoomSettingsPanel
        room={room({ gameType: 'ONE_CARD' })}
        game={game('ONE_CARD', ['RACE_BUTTON_SIZE', 'RACE_BUTTON_VISIBILITY'])}
        canEdit
        onChange={onChange}
      />,
    );

    const vis = screen.getByRole('group', { name: '원카드! 찾기 난이도' });
    expect(within(vis).getAllByRole('radio').map((r) => r.textContent)).toEqual(['보통', '어려움', '매우 어려움']);
    fireEvent.click(within(vis).getByRole('radio', { name: '매우 어려움' }));
    expect(onChange).toHaveBeenCalledWith({ raceButtonVisibility: 'VERY_HARD' });
  });
});
