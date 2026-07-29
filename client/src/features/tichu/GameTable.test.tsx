import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Card, PrivateHand, Suit, TableView } from '@/types/tichu';

/**
 * D-87 특성화 테스트 — GameTable 분해 **전에** 현재 동작을 고정하는 안전망.
 *
 * 목적은 "무엇이 옳은가"가 아니라 "지금 무엇을 하는가"를 못 박는 것이다. 분해 중
 * 이 파일이 빨개지면 리팩토링이 동작을 바꿨다는 뜻이므로, 테스트를 고치지 말고
 * 추출을 되돌린다. (기대값 자체를 바꿔야 한다면 그건 별도 결정 사항이다.)
 *
 * STOMP 만 모킹한다 — 나머지(store/SortableHand/CardChip/좌석)는 실물로 렌더해야
 * 조립 결과를 검증할 수 있기 때문.
 */
const { sendAction, sendChat, sendReaction } = vi.hoisted(() => ({
  sendAction: vi.fn(),
  sendChat: vi.fn(),
  sendReaction: vi.fn(),
}));

vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: true,
    sendAction,
    sendChat,
    sendReaction,
    chatPanelOpenRef: { current: false },
  }),
}));

import { GameTable } from './GameTable';
import { useTichuStore, type TichuRoomState } from './tichuStore';

const PLAYER_IDS = [10, 20, 30, 40];
const MY_USER_ID = 10; // mySeat = 0 (팀 A, 남쪽 좌석)

const card = (suit: Suit, rank: number): Card => ({ suit, rank, special: null });

function tableView(over: Partial<TableView> = {}): TableView {
  return {
    phase: 'PLAYING',
    dealingCardCount: 14,
    readySeats: [],
    passingSubmittedSeats: [],
    currentTurnSeat: 0,
    handCounts: { 0: 14, 1: 14, 2: 14, 3: 14 },
    currentTop: null,
    currentTopSeat: -1,
    declarations: { 0: 'NONE', 1: 'NONE', 2: 'NONE', 3: 'NONE' },
    roundScores: {},
    matchScores: { A: 0, B: 0 },
    roundNumber: 1,
    finishingOrder: [],
    activeWishRank: null,
    ...over,
  };
}

function privateHand(cards: Card[]): PrivateHand {
  return { seat: 0, cards };
}

function seed(state: Partial<TichuRoomState>) {
  useTichuStore.getState().reset('room-1');
  useTichuStore.setState(state as Partial<TichuRoomState>);
}

function renderTable(props: Partial<Parameters<typeof GameTable>[0]> = {}) {
  return render(
    <GameTable
      roomId="room-1"
      playerIds={PLAYER_IDS}
      myUserId={MY_USER_ID}
      {...props}
    />,
  );
}

beforeEach(() => {
  sendAction.mockClear();
  sendChat.mockClear();
  sendReaction.mockClear();
  useTichuStore.getState().reset('room-1');
});

describe('GameTable — 로딩', () => {
  it('tableView 가 없으면 로딩 문구만 렌더한다', () => {
    const { container } = renderTable();
    expect(screen.getByText('로드 중...')).toBeInTheDocument();
    expect(container.querySelector('.game-table')).toBeNull();
  });
});

describe('GameTable — 관전자 모드', () => {
  it('관전자에게는 손패(.my-hand)와 좌석 액션 버튼을 렌더하지 않는다', () => {
    seed({
      tableView: tableView(),
      privateHand: privateHand([card('JADE', 5), card('SWORD', 9)]),
    });

    const { container } = renderTable({ spectator: true, myUserId: 99 });

    expect(container.querySelector('.my-hand')).toBeNull();
    expect(container.querySelector('.arena-seat-actions')).toBeNull();
    // 공개 정보(경기장/좌석)는 관전자에게도 보인다.
    expect(container.querySelector('.table-arena')).not.toBeNull();
    expect(container.querySelectorAll('.seat')).toHaveLength(4);
  });

  it('참가자에게는 손패와 좌석 액션 버튼을 렌더한다 (관전자와 대비)', () => {
    seed({
      tableView: tableView(),
      privateHand: privateHand([card('JADE', 5), card('SWORD', 9)]),
    });

    const { container } = renderTable();

    expect(container.querySelector('.my-hand')).not.toBeNull();
    expect(container.querySelector('.arena-seat-actions')).not.toBeNull();
  });
});

describe('GameTable — 딜링 단계', () => {
  it('8장 시점에 그랜드 티츄/준비 버튼을 렌더하고, 준비 클릭 시 READY 를 보낸다', () => {
    seed({
      tableView: tableView({ phase: 'DEALING', dealingCardCount: 8 }),
      privateHand: privateHand([card('JADE', 5), card('SWORD', 9)]),
    });

    renderTable();

    expect(
      screen.getByRole('button', { name: 'Grand Tichu 선언 (+200/-200)' }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '선언 안 함 — 다음으로' }));

    expect(sendAction).toHaveBeenCalledTimes(1);
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'READY' });
  });

  it('이미 준비한 좌석에는 버튼 대신 대기 문구를 렌더한다', () => {
    seed({
      tableView: tableView({ phase: 'DEALING', dealingCardCount: 8, readySeats: [0] }),
      privateHand: privateHand([card('JADE', 5)]),
    });

    renderTable();

    expect(screen.getByText('다른 좌석을 대기 중...')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '선언 안 함 — 다음으로' })).toBeNull();
  });
});

describe('GameTable — 카드 선택', () => {
  it('플레이 단계에서 카드를 클릭하면 선택이 토글된다', () => {
    seed({
      tableView: tableView({ phase: 'PLAYING' }),
      privateHand: privateHand([card('JADE', 5), card('SWORD', 9)]),
    });

    renderTable();

    const jade5 = screen.getByRole('button', { name: 'JADE 5' });
    expect(jade5).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(jade5);
    expect(screen.getByRole('button', { name: 'JADE 5' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(useTichuStore.getState().selectedCardKeys.has('N-JADE-5')).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'JADE 5' }));
    expect(screen.getByRole('button', { name: 'JADE 5' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(useTichuStore.getState().selectedCardKeys.size).toBe(0);
  });

  it('선택된 카드가 있으면 조합명 힌트를 표시한다', () => {
    seed({
      tableView: tableView({ phase: 'PLAYING' }),
      privateHand: privateHand([card('JADE', 5), card('SWORD', 5)]),
    });

    renderTable();

    fireEvent.click(screen.getByRole('button', { name: 'JADE 5' }));
    fireEvent.click(screen.getByRole('button', { name: 'SWORD 5' }));

    expect(screen.getByText(/선택:/)).toHaveTextContent('페어');
  });
});

describe('GameTable — 매치 종료', () => {
  it('매치 종료 시 .match-ended 패널에 승패·최종점수·라운드표를 렌더한다', () => {
    seed({
      tableView: tableView({ phase: 'ROUND_END' }),
      privateHand: privateHand([]),
      matchEnded: {
        winningTeam: 'A',
        finalScores: { A: 1010, B: 640 },
        roundsPlayed: 7,
        mvpUserId: null,
        mvpStat: null,
      },
      roundHistory: [
        { teamAScore: 60, teamBScore: 40, firstFinisherSeat: 0, doubleVictory: false },
        { teamAScore: 200, teamBScore: 0, firstFinisherSeat: 2, doubleVictory: true },
      ],
    });

    const { container } = renderTable();

    const panel = container.querySelector('.match-ended');
    expect(panel).not.toBeNull();
    // mySeat=0 → 팀 A → 승리 문구.
    expect(panel).toHaveTextContent('🏆 승리!');
    expect(panel).toHaveTextContent('1010');
    expect(panel).toHaveTextContent('640');
    // 라운드 히스토리 표: 헤더 + 2라운드 + 합계.
    expect(container.querySelectorAll('.score-history tbody tr')).toHaveLength(3);
    expect(panel).toHaveTextContent('더블 승');
  });

  it('호스트에게만 "한 판 더" 를 노출하고, 비호스트에는 안내 문구를 렌더한다', () => {
    seed({
      tableView: tableView({ phase: 'ROUND_END' }),
      privateHand: privateHand([]),
      matchEnded: {
        winningTeam: 'B',
        finalScores: { A: 300, B: 1000 },
        roundsPlayed: 5,
        mvpUserId: null,
        mvpStat: null,
      },
    });

    const { rerender } = renderTable({ isHost: true });
    expect(screen.getByRole('button', { name: '🔄 한 판 더' })).toBeInTheDocument();

    rerender(
      <GameTable
        roomId="room-1"
        playerIds={PLAYER_IDS}
        myUserId={MY_USER_ID}
        isHost={false}
      />,
    );
    expect(screen.queryByRole('button', { name: '🔄 한 판 더' })).toBeNull();
    expect(
      screen.getByText("호스트가 '한 판 더' 를 누르면 같은 테이블에서 다시 시작합니다."),
    ).toBeInTheDocument();
  });
});

describe('GameTable — 헤더', () => {
  it('솔로/턴제한/판돈/관전자 배지를 조건부로 렌더한다', () => {
    seed({ tableView: tableView(), privateHand: privateHand([]) });

    renderTable({
      fillWithBots: true,
      botSeats: [1, 2, 3],
      turnSeconds: 30,
      stake: 100,
      spectatorCount: 2,
    });

    expect(screen.getByText('🤖 솔로 모드 (봇 3명)')).toBeInTheDocument();
    expect(screen.getByText('⏱ 턴 제한 30초')).toBeInTheDocument();
    expect(screen.getByText('💰 판돈 100칩')).toBeInTheDocument();
    expect(screen.getByText('👁 관전 2')).toBeInTheDocument();
  });

  it('배지 조건이 꺼지면 렌더하지 않는다', () => {
    seed({ tableView: tableView(), privateHand: privateHand([]) });

    renderTable();

    expect(screen.queryByText(/솔로 모드/)).toBeNull();
    expect(screen.queryByText(/턴 제한/)).toBeNull();
    expect(screen.queryByText(/판돈/)).toBeNull();
    expect(screen.queryByText(/관전/)).toBeNull();
  });
});
