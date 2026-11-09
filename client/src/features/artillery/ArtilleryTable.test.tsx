import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArtilleryTable, shotSummary } from './ArtilleryTable';
import { useArtilleryStore } from './artilleryStore';
import { artilleryRoomSink } from './artilleryRoomSink';
import { useAuthStore } from '@/features/auth/authStore';
import { hitReport, seedStore, unit } from './testFixtures';

const sendAction = vi.fn();
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
    requestResync: vi.fn(),
  }),
}));

const PLAYERS = [10, 20];
const NAMES = { 10: '보라', 20: '민수' };

function renderTable(props: Partial<Parameters<typeof ArtilleryTable>[0]> = {}) {
  return render(
    <ArtilleryTable roomId="r-1" playerIds={PLAYERS} myUserId={10} usernames={NAMES} turnSeconds={30} onExit={vi.fn()} {...props} />,
  );
}

describe('ArtilleryTable', () => {
  beforeAll(() => {
    // jsdom 에는 Canvas 가 없다 — 그리기는 건너뛰고 화면 구조만 본다.
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
  });
  beforeEach(() => {
    sendAction.mockReset();
    socketConnected = true;
    useAuthStore.setState({ token: 't' } as never);
    seedStore();
  });

  it('내 차례면 조작부가 뜨고 [발사]가 이동·각도·힘·탄을 한 번에 보낸다', () => {
    renderTable();
    expect(screen.getByText('내 차례')).toBeInTheDocument();
    expect(screen.getByText(/바람 → 3/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('힘'), { target: { value: '72' } });
    fireEvent.click(screen.getByRole('radio', { name: /호박탄/ }));
    fireEvent.click(screen.getByRole('button', { name: '각도 1도 왼쪽으로' }));
    fireEvent.keyDown(screen.getByRole('button', { name: '오른쪽으로 걷기(누르고 있기)' }), { key: 'Enter' });
    fireEvent.keyDown(screen.getByRole('button', { name: '오른쪽으로 걷기(누르고 있기)' }), { key: 'Enter' });
    fireEvent.click(screen.getByRole('button', { name: '발사' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'FIRE', moveDx: 2, angle: 46, power: 72, shell: 'PUMPKIN' });
    // 답이 오기 전 연타는 막는다
    fireEvent.click(screen.getByRole('button', { name: '발사' }));
    expect(sendAction).toHaveBeenCalledTimes(1);
  });

  it('남의 차례면 조작부가 없고 누가 조준 중인지 보인다', () => {
    seedStore({ turnSeat: 1 });
    renderTable();
    expect(screen.queryByRole('button', { name: '발사' })).toBeNull();
    expect(screen.getByText('민수 조준 중')).toBeInTheDocument();
  });

  it('넘기기는 SKIP 을 보낸다', () => {
    renderTable();
    fireEvent.click(screen.getByRole('button', { name: '넘기기' }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'SKIP' });
  });

  it('호박탄이 없으면 고를 수 없다', () => {
    seedStore({ units: [unit(0, 100, { pumpkins: 0 }), unit(1, 500)] });
    renderTable();
    expect(screen.getByRole('radio', { name: /호박탄 ×0/ })).toBeDisabled();
  });

  it('대본이 오면 낭독 문장을 만들고, 거절은 문구로 보인다', () => {
    renderTable();
    act(() => {
      useArtilleryStore.getState().applyEvent({ type: 'SHOT_RESOLVED', payload: { report: hitReport() } });
    });
    expect(screen.getByText('보라의 콩알 — 민수 30 피해(직격)')).toBeInTheDocument();
    act(() => {
      artilleryRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'NO_AMMO', message: 'x' } } as never);
    });
    expect(screen.getByRole('alert')).toHaveTextContent('호박탄이 남지 않았어요.');
  });

  it('끝나면 순위 카드', () => {
    seedStore({
      phase: 'ENDED',
      turnSeat: -1,
      result: {
        reason: 'LAST_STANDING',
        placements: [
          { seat: 0, rank: 1, hp: 64, deserted: false },
          { seat: 1, rank: 2, hp: 0, deserted: false },
        ],
      },
    });
    renderTable({ onBackToRoom: vi.fn() });
    expect(screen.getByRole('heading', { name: '🌻 이겼어요!' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '대기실로' })).toBeInTheDocument();
  });

  it('shotSummary — 빗나감·구름·밖', () => {
    const nameOf = (s: number) => ['보라', '민수'][s];
    expect(shotSummary(hitReport({ damages: [], falls: [], shot: { ...hitReport().shot!, end: 'OUT', crater: null } }), nameOf)).toBe(
      '보라의 콩알 — 전장 밖으로',
    );
    expect(
      shotSummary(hitReport({ damages: [], falls: [], eliminated: [{ seat: 1, cause: 'CLOUD_SEA' }] }), nameOf),
    ).toBe('보라의 콩알 — 민수 추락');
  });
});
