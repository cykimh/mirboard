import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArtilleryTable, shotSummary } from './ArtilleryTable';
import { useArtilleryStore } from './artilleryStore';
import { artilleryRoomSink } from './artilleryRoomSink';
import { useAuthStore } from '@/features/auth/authStore';
import { DELAY_TABLE, flatRuns, hitReport, hitShot, hitStage, seedStore, unit } from './testFixtures';
import { ArtilleryMatchEnd } from './ArtilleryMatchEnd';

const sendAction = vi.fn();
const sendPreview = vi.fn();
let socketConnected = true;
vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: socketConnected,
    sendAction: (a: Record<string, unknown>) => sendAction(a),
    sendPreview: (p: Record<string, unknown>) => sendPreview(p),
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
    sendPreview.mockReset();
    socketConnected = true;
    useAuthStore.setState({ token: 't' } as never);
    seedStore();
  });

  it('내 차례면 조작부가 뜨고, 박자 전에 누른 [발사]는 모은 걸음을 FIRE.moveDx 로 싣는다', () => {
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

  it('특수탄·아이템 — 내 유닛 특수탄 칩, 아이템은 한 차례 하나를 FIRE.item 으로 (D-224)', () => {
    seedStore({ units: [unit(0, 100, { kind: 'DANDELION', special: 'SPORE', specials: 1, maxHp: 85, hp: 85, fuel: 130 }), unit(1, 500)] });
    renderTable();
    expect(screen.queryByRole('radio', { name: /덩굴탄/ })).toBeNull();
    fireEvent.click(screen.getByRole('radio', { name: '홀씨탄 ×1' }));
    fireEvent.click(screen.getByRole('button', { name: /바람개비/ }));
    fireEvent.click(screen.getByRole('button', { name: /겹씨앗/ }));
    expect(screen.getByRole('button', { name: /겹씨앗/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /바람개비/ })).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(screen.getByRole('button', { name: '발사' }));
    expect(sendAction).toHaveBeenCalledWith(
      expect.objectContaining({ '@action': 'FIRE', shell: 'SPORE', item: 'TWIN_SEED' }),
    );
  });

  it('쓴 아이템은 누를 수 없다', () => {
    seedStore({ units: [unit(0, 100, { items: ['PINWHEEL'] }), unit(1, 500)] });
    renderTable();
    expect(screen.getByRole('button', { name: /물뿌리개/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /바람개비/ })).toBeEnabled();
  });

  it('고르기 단계 — 카드를 누르면 PICK 을 보내고, 남의 선택은 "골랐어요"만 보인다 (D-224)', () => {
    seedStore({
      phase: 'PICKING',
      turnSeat: -1,
      pickRemainingMs: 12_000,
      units: [
        unit(0, 100, { kind: null, picked: false, special: null, specials: 0, items: [] }),
        unit(1, 500, { kind: null, picked: true, special: null, specials: 0, items: [] }),
      ],
    });
    renderTable();
    expect(screen.getByRole('heading', { name: '포대를 고르세요' })).toBeInTheDocument();
    expect(screen.getByText('민수 — 골랐어요')).toBeInTheDocument();
    expect(screen.getByRole('timer')).toHaveTextContent('12초');
    expect(screen.queryByRole('button', { name: '발사' })).toBeNull();
    fireEvent.click(screen.getByRole('radio', { name: /도토리포/ }));
    expect(sendAction).toHaveBeenCalledWith({ '@action': 'PICK', unit: 'ACORN' });
    expect(screen.getByText('보라 (나) — 도토리포')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /민들레포/ })).toBeDisabled();
  });

  it('관전자는 고를 수 없다', () => {
    seedStore({ phase: 'PICKING', turnSeat: -1, units: [unit(0, 100, { kind: null, picked: false }), unit(1, 500, { kind: null, picked: false })] });
    renderTable({ spectator: true, myUserId: 99 });
    expect(screen.getByRole('radio', { name: /새싹포/ })).toBeDisabled();
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
    expect(screen.getByRole('alert')).toHaveTextContent('그 탄이 남지 않았어요.');
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
    expect(
      shotSummary(
        hitReport({ stages: [hitStage({ shots: [hitShot({ end: 'OUT', crater: null })], damages: [], falls: [] })] }),
        nameOf,
      ),
    ).toBe('보라의 콩알 — 전장 밖으로');
    expect(
      shotSummary(
        hitReport({ stages: [hitStage({ damages: [], falls: [] })], eliminated: [{ seat: 1, cause: 'CLOUD_SEA' }] }),
        nameOf,
      ),
    ).toBe('보라의 콩알 — 민수 추락');
    // 겹씨앗 두 단계 피해는 합쳐서, 아이템·회복도 붙인다
    const twice = hitReport({
      item: 'TWIN_SEED',
      stages: [hitStage(), hitStage({ damages: [{ seat: 1, amount: 12, hpAfter: 58, direct: false }], falls: [] })],
    });
    expect(shotSummary(twice, nameOf)).toBe('보라의 콩알 (겹씨앗) — 민수 42 피해(직격)');
    expect(
      shotSummary(hitReport({ item: 'WATERING_CAN', heal: { amount: 25, hpAfter: 90 } }), nameOf),
    ).toBe('보라의 콩알 (물뿌리개 체력 +25) — 민수 30 피해(직격)');
    expect(
      shotSummary(
        hitReport({
          shell: 'VINE',
          stages: [hitStage({ shots: [hitShot({ end: 'TERRAIN', hitSeat: -1, crater: { x: 1, y: 1, r: 16, fill: true } })], damages: [], falls: [] })],
        }),
        nameOf,
      ),
    ).toBe('보라의 덩굴탄 — 둔덕이 솟았다');
  });

  it('딜레이 — 좌석 ⏱ 는 가장 적은 화분과의 차이, 조작부 ⏱ +N 은 탄·아이템·걸음을 반영, 다음 차례 줄 (D-226)', () => {
    seedStore({
      delays: DELAY_TABLE,
      queue: [0, 1],
      units: [unit(0, 100, { delay: 40 }), unit(1, 500, { delay: 160 })],
    });
    renderTable();
    expect(screen.getByText('⏱0')).toBeInTheDocument();
    expect(screen.getByText('⏱120')).toBeInTheDocument();
    expect(screen.getByText('⏱ +100')).toBeInTheDocument();
    expect(screen.getByLabelText('다음 차례 민수')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('radio', { name: /호박탄/ }));
    fireEvent.click(screen.getByRole('button', { name: /겹씨앗/ }));
    for (let i = 0; i < 4; i++) {
      fireEvent.keyDown(screen.getByRole('button', { name: '오른쪽으로 걷기(누르고 있기)' }), { key: 'Enter' });
    }
    expect(screen.getByText('⏱ +192')).toBeInTheDocument(); // 130 + 4/2 + 60
  });

  it('2:2 팀전 — 머리말·좌석에 팀 칩 (D-225)', () => {
    seedStore({
      teams: true,
      seatCount: 4,
      units: [unit(0, 100), unit(1, 250), unit(2, 400), unit(3, 550)],
    });
    renderTable({ playerIds: [10, 20, 30, 40], usernames: { 10: '보라', 20: '민수', 30: '지수', 40: '하늘' } });
    expect(screen.getByText('2:2 팀전')).toBeInTheDocument();
    expect(screen.getAllByText('A팀')).toHaveLength(2);
    expect(screen.getAllByText('B팀')).toHaveLength(2);
  });

  it('팀전 결과 — 우리 팀 승·상대 팀 승·무승부, 줄마다 승/패와 팀 (D-225)', () => {
    const nameOf = (seat: number) => ['보라', '민수', '지수', '하늘'][seat];
    const placements = [
      { seat: 0, rank: 1, hp: 40, deserted: false },
      { seat: 2, rank: 1, hp: 0, deserted: false },
      { seat: 1, rank: 3, hp: 0, deserted: false },
      { seat: 3, rank: 3, hp: 0, deserted: false },
    ];
    const won = render(
      <ArtilleryMatchEnd result={{ reason: 'LAST_STANDING', placements, teams: true, winnerTeam: 0 }} mySeat={2} nameOf={nameOf} />,
    );
    expect(screen.getByText('🌻 우리 팀이 이겼어요!')).toBeInTheDocument();
    expect(screen.getAllByText('승')).toHaveLength(2);
    expect(screen.getAllByText('패')).toHaveLength(2);
    won.unmount();
    const lost = render(
      <ArtilleryMatchEnd result={{ reason: 'LAST_STANDING', placements, teams: true, winnerTeam: 0 }} mySeat={1} nameOf={nameOf} />,
    );
    expect(screen.getByText('A팀 승리')).toBeInTheDocument();
    lost.unmount();
    render(
      <ArtilleryMatchEnd
        result={{ reason: 'ALL_DOWN', placements: placements.map((p) => ({ ...p, rank: 1 })), teams: true, winnerTeam: null }}
        mySeat={0}
        nameOf={nameOf}
      />,
    );
    expect(screen.getByText('무승부')).toBeInTheDocument();
    expect(screen.getAllByText('무')).toHaveLength(4);
  });

  describe('실시간 걷기·조준 공유 (S6b, D-251)', () => {
    const right = () => fireEvent.keyDown(screen.getByRole('button', { name: '오른쪽으로 걷기(누르고 있기)' }), { key: 'Enter' });
    const movedEvent = (seat: number, toX: number, seq: number, fuelLeft: number) => ({
      type: 'UNIT_MOVED',
      seq,
      payload: { seat, turn: 0, path: [toX, 299], toX, toY: 299, fuelLeft, fall: null, hpAfter: 100, eliminated: null },
    });
    beforeEach(() => {
      vi.useFakeTimers();
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    it('걸음은 250ms 박자로 MOVE 하나씩 — 답이 오기 전엔 다음을 안 보내고, [발사]는 답 뒤에 남은 걸음을 싣는다', () => {
      renderTable();
      expect(screen.queryByRole('button', { name: '제자리' })).toBeNull();   // 되돌리기 없음
      right();
      right();
      right();
      act(() => {
        vi.advanceTimersByTime(250);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'MOVE', dx: 3 });
      right();
      right();
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(sendAction).toHaveBeenCalledTimes(1);   // 답을 기다린다
      fireEvent.click(screen.getByRole('button', { name: '발사' }));
      expect(sendAction).toHaveBeenCalledTimes(1);   // FIRE 도 MOVE 를 앞지르지 않는다
      expect(screen.getByRole('button', { name: '발사' })).toBeDisabled();
      act(() => {
        useArtilleryStore.getState().applyEvent(movedEvent(0, 103, 2, 97));
      });
      expect(sendAction).toHaveBeenCalledTimes(2);
      expect(sendAction).toHaveBeenLastCalledWith(expect.objectContaining({ '@action': 'FIRE', moveDx: 2 }));
    });

    it('꾹 누르고 걸어도 낭떠러지 앞에서 멈춘다 — 타이머가 매번 지금 자리에서 다시 잰다(브라우저에서 잡은 회귀)', () => {
      // 95열부터 땅 — 100 에 선 포대는 93 까지(받침 반폭 2)만 걸을 수 있다
      seedStore({ terrain: flatRuns().map((run, x) => (x >= 95 ? run : [])) });
      renderTable();
      const left = screen.getByRole('button', { name: '왼쪽으로 걷기(누르고 있기)' });
      fireEvent.pointerDown(left);
      act(() => {
        vi.advanceTimersByTime(2000);
      });
      fireEvent.pointerUp(left);
      expect(screen.getByTitle('이동 연료 93/100')).toBeInTheDocument();
      expect(sendAction).toHaveBeenCalledTimes(1);
      expect((sendAction.mock.calls[0][0] as { dx: number }).dx).toBeGreaterThanOrEqual(-7);
    });

    it('BUSY 면 같은 MOVE 를 한 번 다시 보내고 띠를 띄우지 않는다, CANNOT_MOVE 면 서버 자리로', () => {
      renderTable();
      right();
      right();
      act(() => {
        vi.advanceTimersByTime(250);
      });
      expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'MOVE', dx: 2 });
      act(() => {
        artilleryRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'BUSY', message: 'x' } } as never);
      });
      expect(sendAction).toHaveBeenCalledTimes(2);
      expect(sendAction).toHaveBeenLastCalledWith({ '@action': 'MOVE', dx: 2 });
      expect(screen.queryByRole('alert')).toBeNull();
      act(() => {
        artilleryRoomSink.applyPrivateEvent({ type: 'ERROR', payload: { code: 'CANNOT_MOVE', message: 'x' } } as never);
      });
      expect(useArtilleryStore.getState().moveInFlight).toBe(false);
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(sendAction).toHaveBeenCalledTimes(2);
    });

    it('조준 미리보기 — 차례가 열리면 바로, 바뀌면 250ms 간격, 안 바뀌어도 2초마다. 힘은 보내지 않는다', () => {
      renderTable();
      expect(sendPreview).toHaveBeenCalledTimes(1);
      expect(sendPreview).toHaveBeenLastCalledWith({ '@action': 'PREVIEW', angle: 45, shell: 'PEA', n: 1 });
      fireEvent.click(screen.getByRole('button', { name: '각도 1도 왼쪽으로' }));
      fireEvent.click(screen.getByRole('radio', { name: /호박탄/ }));
      fireEvent.change(screen.getByLabelText('힘'), { target: { value: '90' } });
      act(() => {
        vi.advanceTimersByTime(260);
      });
      expect(sendPreview).toHaveBeenCalledTimes(2);
      expect(sendPreview).toHaveBeenLastCalledWith({ '@action': 'PREVIEW', angle: 46, shell: 'PUMPKIN', n: 2 });
      act(() => {
        vi.advanceTimersByTime(1500);
      });
      expect(sendPreview).toHaveBeenCalledTimes(2);   // 힘만 바뀐 것은 보내지 않는다
      act(() => {
        vi.advanceTimersByTime(600);
      });
      expect(sendPreview).toHaveBeenCalledTimes(3);   // 유지
      for (const call of sendPreview.mock.calls) expect(call[0]).not.toHaveProperty('power');
    });

    it('남의 차례 — 미리보기 탄 칩이 보이고, 내 미리보기·걷기는 보내지 않는다', () => {
      seedStore({ turnSeat: 1 });
      renderTable();
      expect(sendPreview).not.toHaveBeenCalled();
      act(() => {
        useArtilleryStore.getState().applyEvent({ type: 'AIM_PREVIEW', payload: { seat: 1, turn: 0, angle: 130, shell: 'PUMPKIN', n: 1 } });
        useArtilleryStore.getState().applyEvent(movedEvent(1, 497, 2, 97));
      });
      expect(screen.getByText('호박탄')).toBeInTheDocument();
      expect(useArtilleryStore.getState().units[1].x).toBe(497);
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(sendAction).not.toHaveBeenCalled();
    });

    it('걸으면 연료·⏱ +N 이 예측 자리를 따르고, 남은 시간은 엔진 값', () => {
      seedStore({ fuel: 90, turnRemainingMs: 9_000, delays: DELAY_TABLE }, 29_000);
      renderTable();
      expect(screen.getByRole('timer')).toHaveTextContent('9초');
      expect(screen.getByText('⏱ +105')).toBeInTheDocument();   // 콩알 100 + 이미 걸은 10칸 /2
      right();
      right();
      expect(screen.getByText('⏱ +106')).toBeInTheDocument();
    });
  });
});
