import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { TableView } from '@/types/tichu';

/**
 * D-126 — 티츄 플레이는 resync 없이 화면에 반영된다. 실제 훅(`useStompRoom`)·sink·스토어를 그대로
 * 묶고 STOMP 와 REST 만 가짜로 둔다. 서버는 이제 비공개 `HAND_DEALT` 에 순번을 쓰지 않으므로 공개
 * 순번이 구멍 없이 이어진다 — 예전에는 카드를 낼 때마다 다음 공개 이벤트가 구멍이라 전원 resync 했고,
 * 소원 해제처럼 이벤트가 없던 변화는 그 resync 가 우연히 고쳤다.
 */

const handlers = new Map<string, (frame: { body: string }) => void>();

vi.mock('@stomp/stompjs', () => ({
  Client: class {
    connected = false;
    private onConnect: () => void;
    constructor(cfg: { onConnect: () => void }) {
      this.onConnect = cfg.onConnect;
    }
    activate() {
      this.connected = true;
      this.onConnect();
    }
    subscribe(dest: string, cb: (frame: { body: string }) => void) {
      handlers.set(dest, cb);
      return { unsubscribe: () => {} };
    }
    publish() {}
    deactivate() {
      this.connected = false;
    }
  },
}));

const resyncMock = vi.fn();
vi.mock('@/api/rooms', () => ({
  roomsApi: { resync: (...args: unknown[]) => resyncMock(...args) },
}));

import { useStompRoom } from '@/ws/useStompRoom';
import { tichuRoomSink } from './tichuRoomSink';
import { useTichuStore } from './tichuStore';

const ROOM = 'r-d126';

function table(overrides: Partial<TableView> = {}): TableView {
  return {
    phase: 'PLAYING',
    dealingCardCount: 0,
    readySeats: [],
    passingSubmittedSeats: [],
    currentTurnSeat: 1,
    handCounts: { 0: 5, 1: 5, 2: 5, 3: 5 },
    currentTop: null,
    currentTopSeat: -1,
    declarations: { 0: 'NONE', 1: 'NONE', 2: 'NONE', 3: 'NONE' },
    roundScores: { A: 0, B: 0 },
    matchScores: { A: 0, B: 0 },
    roundNumber: 1,
    finishingOrder: [],
    activeWishRank: null,
    completedRounds: [],
    ...overrides,
  };
}

function snapshot(tableView: TableView) {
  return {
    roomId: ROOM,
    phase: tableView.phase,
    eventSeq: 10,
    tableView,
    privateHand: { seat: 0, cards: [{ suit: 'JADE', rank: 9, special: null }] },
    disconnectedSeats: [],
    chips: {},
  };
}

const publicEvent = (seq: number | undefined, type: string, payload: unknown) =>
  handlers.get(`/topic/room/${ROOM}`)!({ body: JSON.stringify({ type, seq, payload }) });
const privateEvent = (type: string, payload: unknown) =>
  handlers.get(`/user/queue/room/${ROOM}`)!({ body: JSON.stringify({ type, payload }) });

async function mountWith(tableView: TableView) {
  resyncMock.mockResolvedValue(snapshot(tableView));
  renderHook(() => useStompRoom(ROOM, 'tok', tichuRoomSink));
  await waitFor(() => expect(useTichuStore.getState().tableView).not.toBeNull());
  return resyncMock.mock.calls.length;
}

const seven = { suit: 'SWORD', rank: 7, special: null };

beforeEach(() => {
  handlers.clear();
  resyncMock.mockReset();
  useTichuStore.getState().reset(ROOM);
});

describe('티츄 플레이는 resync 없이 반영된다 (D-126)', () => {
  it('소원 숫자가 나오면 resync 없이 소원 표시가 사라진다', async () => {
    const afterMount = await mountWith(table({ activeWishRank: 7 }));

    publicEvent(11, 'PLAYED', {
      seat: 1,
      hand: { type: 'SINGLE', cards: [seven], rank: 7, length: 1 },
    });
    publicEvent(12, 'WISH_CLEARED', { rank: 7 });
    publicEvent(13, 'TURN_CHANGED', { currentTurnSeat: 2 });

    expect(resyncMock).toHaveBeenCalledTimes(afterMount);
    const t = useTichuStore.getState().tableView!;
    expect(t.activeWishRank).toBeNull();
    expect(t.currentTurnSeat).toBe(2);
    expect(t.handCounts[1]).toBe(4);
  });

  it('매치를 끝내는 마지막 플레이 — resync 없이 종료 패널이 뜨고 내 차례 표시가 남지 않는다', async () => {
    const afterMount = await mountWith(
      table({
        currentTurnSeat: 0,
        handCounts: { 0: 1, 1: 0, 2: 3, 3: 0 },
        finishingOrder: [1, 3],
        roundScores: { A: 40, B: 50 },
        matchScores: { A: 900, B: 950 },
        activeWishRank: 5,
        completedRounds: [
          { teamAScore: 100, teamBScore: 0, firstFinisherSeat: 0, doubleVictory: false },
        ],
      }),
    );

    publicEvent(11, 'PLAYED', {
      seat: 0,
      hand: { type: 'SINGLE', cards: [seven], rank: 7, length: 1 },
    });
    privateEvent('HAND_DEALT', { seat: 0, cards: [], phaseCardCount: 0 }); // 순번 없음
    publicEvent(12, 'PLAYER_FINISHED', { seat: 0, order: 3 });
    publicEvent(13, 'TRICK_TAKEN', { takerSeat: 0, trickPoints: 10 });
    publicEvent(14, 'ROUND_ENDED', {
      score: { teamAScore: 60, teamBScore: 40, firstFinisherSeat: 1, doubleVictory: false },
    });
    publicEvent(15, 'MATCH_ENDED', {
      winningTeam: 'A',
      finalScores: { A: 1010, B: 990 },
      roundsPlayed: 2,
    });

    expect(resyncMock).toHaveBeenCalledTimes(afterMount);
    const s = useTichuStore.getState();
    expect(s.matchEnded?.winningTeam).toBe('A');
    expect(s.roundEnded?.teamAScore).toBe(60);
    expect(s.privateHand?.cards).toEqual([]);
    const t = s.tableView!;
    expect(t.phase).toBe('ROUND_END');
    expect(t.currentTurnSeat).toBe(-1);
    expect(t.currentTop).toBeNull();
    expect(t.activeWishRank).toBeNull();
    expect(t.roundScores).toEqual({ A: 60, B: 40 });
    expect(t.matchScores).toEqual({ A: 1010, B: 990 });
  });
});
