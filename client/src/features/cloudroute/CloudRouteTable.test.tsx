import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CloudRouteTable } from './CloudRouteTable';
import { useCloudRouteStore } from './cloudrouteStore';
import { useAuthStore } from '@/features/auth/authStore';
import { seatOf, snapshotOf, tableOf, tilesOf } from './testTables';
import type { CloudRouteTableView } from '@/types/cloudroute';

// 소켓만 모킹하고 스토어는 실물을 seed 한다(원카드 게임판 테스트와 같은 방식).
const sendAction = vi.fn();
const requestResync = vi.fn();
let socketConnected = true;
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: socketConnected,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendChat: vi.fn(),
    sendReaction: vi.fn(),
    sendVoice: vi.fn(),
    subscribeVoice: () => () => {},
    chatPanelOpenRef: { current: false },
    requestResync,
  }),
}));

const USERS = { 10: '나', 20: '민' };

function seed(table: CloudRouteTableView) {
  useCloudRouteStore.getState().reset('r-1');
  useCloudRouteStore.getState().applySnapshot(snapshotOf(table));
}

function renderTable(props: Partial<Parameters<typeof CloudRouteTable>[0]> = {}) {
  return render(
    <CloudRouteTable roomId="r-1" playerIds={[10, 20]} myUserId={10} usernames={USERS} onExit={vi.fn()} {...props} />,
  );
}

beforeEach(() => {
  sendAction.mockReset();
  requestResync.mockReset();
  socketConnected = true;
  useAuthStore.setState({ token: 't' });
});

describe('CloudRouteTable — 결정 패널 (D-213)', () => {
  it('내 차례 굴리기는 ROLL 하나를 보낸다', () => {
    seed(tableOf());
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: /굴리기/ }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'ROLL' });
  });

  it('구매는 같이 지을 단계를 골라 합계 가격과 함께 한 번에 보낸다', () => {
    seed(tableOf({ pending: { kind: 'PURCHASE', tile: 4, maxLevel: 2 } }));
    renderTable();
    expect(screen.getByRole('button', { name: '사기 160G' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: '땅만' }));
    fireEvent.click(screen.getByRole('button', { name: '사기 80G' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'BUY', buildTo: 0 });
    fireEvent.click(screen.getByRole('button', { name: '안 사기' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'DECLINE' });
  });

  it('건설은 지금 단계 다음부터 고른다', () => {
    seed(tableOf({ tiles: tilesOf({ 4: [0, 1] }), pending: { kind: 'BUILD', tile: 4, minLevel: 2, maxLevel: 3 } }));
    renderTable();
    expect(screen.queryByRole('radio', { name: '천막' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '풍등탑까지 80G' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'BUILD', buildTo: 3 });
  });

  it('소용돌이에서는 할 수 있는 선택만 보인다', () => {
    seed(tableOf({ pending: { kind: 'WHIRLPOOL', canPay: true, canUseBuoy: false } }));
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '50G 내고 나가기' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PAY_ESCAPE' });
    expect(screen.queryByRole('button', { name: /구명 부표/ })).toBeNull();
  });

  it('인수는 주인과 가격을 밝히고 TAKEOVER 를 보낸다', () => {
    seed(tableOf({ tiles: tilesOf({ 7: [1, 2] }), pending: { kind: 'TAKEOVER', tile: 7, owner: 1, price: 300 } }));
    renderTable();
    expect(screen.getByText(/민의/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '인수 300G' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'TAKEOVER' });
  });

  it('비행은 판의 후보 칸을 눌러 고른다 — 후보 밖 칸은 칸 시트를 연다', () => {
    const candidates = Array.from({ length: 24 }, (_, t) => t).filter((t) => t !== 18);
    seed(tableOf({ pending: { kind: 'FLIGHT', candidates } }));
    renderTable();
    const board = screen.getByRole('group', { name: '판' });
    fireEvent.click(within(board).getByRole('button', { name: /^구름왕관 정원/ }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'FLY', tile: 22 });
    fireEvent.click(within(board).getByRole('button', { name: /^바람 정류장/ }));
    expect(screen.getByRole('dialog', { name: '바람 정류장 자세히' })).toBeInTheDocument();
  });

  it('축제는 패널 버튼으로도 고를 수 있다', () => {
    seed(tableOf({ tiles: tilesOf({ 1: [0, 0] }), pending: { kind: 'FESTIVAL', candidates: [1] } }));
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '이끼 나루' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'HOST_FESTIVAL', tile: 1 });
  });

  it('등불 선물은 패널이나 판의 후보 칸으로 고른다', () => {
    seed(tableOf({ tiles: tilesOf({ 4: [0, 1], 7: [0, 0] }), pending: { kind: 'GIFT', candidates: [4, 7] } }));
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '조개 모래톱' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'GIFT_BUILD', tile: 4 });
    fireEvent.click(within(screen.getByRole('group', { name: '판' })).getByRole('button', { name: /^버섯 숲길/ }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'GIFT_BUILD', tile: 7 });
    expect(screen.queryByRole('button', { name: '건너뛰기' })).toBeNull();
  });

  it('무료권 비행은 비용 없이 고른다고 알린다', () => {
    seed(tableOf({ pending: { kind: 'FLIGHT', candidates: [1, 2], free: true } }));
    renderTable();
    expect(screen.getByText(/정류장 무료권/)).toBeInTheDocument();
  });

  it('돈이 모자라면 팔 땅을 고른다 — 다음 단위와 받을 돈을 보여 준다', () => {
    seed(
      tableOf({
        seats: [seatOf(0, { cash: 10 }), seatOf(1)],
        tiles: tilesOf({ 4: [0, 2], 7: [0, 0] }),
        pending: { kind: 'SALE', debt: 140, creditor: 1, reason: 'TOLL', tile: 9, monopoly: false, festival: false, candidates: [4, 7] },
      }),
    );
    renderTable();
    expect(screen.getByText(/민에게/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '조개 모래톱 상점 한 단계 +20G' }));
    expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'SELL', tile: 4 });
    expect(screen.getByRole('button', { name: '버섯 숲길 땅 +50G' })).toBeInTheDocument();
  });

  it('남의 차례에는 무엇을 기다리는지만 보이고 버튼이 없다', () => {
    seed(tableOf({ turnSeat: 1, pending: { kind: 'PURCHASE', tile: 4, maxLevel: 2 } }));
    renderTable();
    expect(screen.getByText('민 — 살지 고르는 중')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /사기/ })).toBeNull();
  });

  it('관전자에게는 결정 패널 버튼이 없다', () => {
    seed(tableOf());
    renderTable({ spectator: true, myUserId: 99 });
    expect(screen.queryByRole('button', { name: /굴리기/ })).toBeNull();
    expect(screen.getByText('관전 모드')).toBeInTheDocument();
  });

  it('끊긴 동안은 결정 버튼을 잠근다', () => {
    socketConnected = false;
    seed(tableOf());
    renderTable();
    expect(screen.getByRole('button', { name: /굴리기/ })).toBeDisabled();
  });
});

describe('CloudRouteTable — 판·좌석·결과', () => {
  it('칸에 주인·단계·축제·말이 보이고, 칸 시트에 통행료표가 있다', () => {
    seed(
      tableOf({
        seats: [seatOf(0, { position: 7 }), seatOf(1, { position: 7, holdsBuoy: true })],
        tiles: tilesOf({ 7: [1, 2], 8: [1, 0] }),
        festivalTile: 7,
        pending: null,
        turnSeat: 1,
      }),
    );
    renderTable();
    const board = screen.getByRole('group', { name: '판' });
    const tile = within(board).getByRole('button', { name: /^버섯 숲길, 민의 땅 상점, 축제 중, 말 2/ });
    fireEvent.click(tile);
    const sheet = screen.getByRole('dialog', { name: '버섯 숲길 자세히' });
    expect(within(sheet).getByText(/섬 독점 ×1.5/)).toBeInTheDocument();
    expect(within(sheet).getByText('140G')).toBeInTheDocument();
    expect(screen.getByText('부표')).toBeInTheDocument();
  });

  it('사건 줄과 알림이 같은 문장을 쓴다', () => {
    seed(tableOf());
    renderTable();
    act(() => {
      useCloudRouteStore.getState().applyEvent({
        type: 'TOLL_PAID',
        payload: { payer: 0, owner: 1, tile: 7, amount: 140, monopoly: false, festival: false, payerCash: 1360, ownerCash: 1640 },
      });
    });
    const log = screen.getByRole('list', { name: '방금 일어난 일' });
    expect(within(log).getByText('나 → 민 버섯 숲길 통행료 140G')).toBeInTheDocument();
    expect(screen.getByText('1,360G')).toBeInTheDocument();
  });

  it('판이 끝나면 순위·총자산과 대기실로 버튼', () => {
    const onBackToRoom = vi.fn();
    seed(
      tableOf({
        pending: null,
        turnSeat: -1,
        result: {
          reason: 'ROUND_LIMIT',
          rounds: 30,
          standings: [
            { seat: 1, rank: 1, totalAssets: 3400, cash: 900, status: 'ALIVE' },
            { seat: 0, rank: 2, totalAssets: 0, cash: 0, status: 'BANKRUPT' },
          ],
        },
      }),
    );
    renderTable({ roomFinished: true, onBackToRoom });
    const end = screen.getByRole('region', { name: '판 종료' });
    expect(within(end).getByText('총자산 3,400G')).toBeInTheDocument();
    expect(within(end).getByText('파산')).toBeInTheDocument();
    fireEvent.click(within(end).getByRole('button', { name: '대기실로' }));
    expect(onBackToRoom).toHaveBeenCalled();
  });

  it('서버 거절 문구를 보이고 닫을 수 있다', () => {
    seed(tableOf());
    useCloudRouteStore.getState().setError('돈이 모자랍니다.');
    renderTable();
    expect(screen.getByRole('alert')).toHaveTextContent('돈이 모자랍니다.');
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('CloudRouteTable — 결정 시계', () => {
  it('새 결정이 오면 방의 턴 제한부터 센다 — 턴 제한보다 큰 값이 보이지 않는다', () => {
    seed(tableOf());
    renderTable({ turnSeconds: 30 });
    act(() => {
      useCloudRouteStore.getState().applyEvent({ type: 'DECISION_REQUIRED', payload: { seat: 0, pending: { kind: 'ROLL' } } });
    });
    expect(screen.getByRole('timer')).toHaveAccessibleName('이번 결정 남은 시간 30초');
  });
});

describe('CloudRouteTable — 칸 시트', () => {
  it('내 결정이 새로 오면 칸 시트를 닫는다 — 결정 패널을 덮지 않게', () => {
    seed(tableOf({ turnSeat: 1, pending: { kind: 'ROLL' } }));
    renderTable();
    fireEvent.click(within(screen.getByRole('group', { name: '판' })).getByRole('button', { name: /^세관/ }));
    expect(screen.getByRole('dialog', { name: '세관 자세히' })).toBeInTheDocument();
    act(() => {
      useCloudRouteStore.getState().applyEvent({ type: 'TURN_CHANGED', payload: { seat: 0, round: 1 } });
      useCloudRouteStore.getState().applyEvent({ type: 'DECISION_REQUIRED', payload: { seat: 0, pending: { kind: 'ROLL' } } });
    });
    expect(screen.queryByRole('dialog', { name: '세관 자세히' })).toBeNull();
  });
});
