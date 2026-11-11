import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DiceTray, LAND_ORDER, ROLL_ANIMATION_MS, ROLL_DIE_MS, ROLL_STAGGER_MS } from './DiceTray';
import { SEAT_ROLL_MS, SeatDice } from './SeatDice';
import { CHIP_FLIGHT_MS, MID_PARTY_MS, YACHT_PARTY_MS, YachtTable } from './YachtTable';
import { END_FLIP_GAP_MS, END_FLIP_MS, END_FLIP_START_MS, YachtMatchEnd, firstRevealMs } from './YachtMatchEnd';
import { celebrationDice } from './yachtLabels';
import { useYachtStore } from './yachtStore';
import { useAuthStore } from '@/features/auth/authStore';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';
import { responsiveCssSource, yachtCssSource } from '@/styles/cssSources';
import type { Dice, Held, YachtSeatTurn, YachtSeatView, YachtTableView } from '@/types/yacht';

vi.mock('@/ws/useStompRoom', () => ({
  useStompRoom: () => ({
    connected: true,
    sendAction: vi.fn(),
    sendChat: vi.fn(),
    sendReaction: vi.fn(),
    sendVoice: vi.fn(),
    subscribeVoice: () => () => {},
    chatPanelOpenRef: { current: false },
    requestResync: vi.fn(),
  }),
}));
vi.mock('@/api/users', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/users')>();
  return { ...actual, usersApi: { ...actual.usersApi, stats: () => Promise.resolve({ games: [] }) } };
});

const NONE: Held = [false, false, false, false, false];
const seat = (n: number, over: Partial<YachtSeatView> = {}): YachtSeatView => ({
  seat: n,
  scores: {},
  upperSubtotal: 0,
  bonus: 0,
  total: 0,
  deserted: false,
  ...over,
});

function seed(over: Partial<YachtTableView> = {}) {
  const t: YachtTableView = {
    phase: 'PLAYING',
    seats: [seat(0), seat(1)],
    round: 1,
    turnSeat: 0,
    dice: null,
    held: NONE,
    rolls: 0,
    candidates: {},
    turnRemainingMs: null,
    result: null,
    ...over,
  };
  useYachtStore.getState().reset('r-1');
  useYachtStore.getState().applySnapshot({
    roomId: 'r-1',
    phase: t.phase,
    eventSeq: 1,
    tableView: t,
    privateHand: null,
    disconnectedSeats: [],
  });
}

const renderTable = () =>
  render(<YachtTable roomId="r-1" playerIds={[10, 20]} myUserId={10} usernames={{ 10: '민지', 20: '준호' }} onExit={vi.fn()} />);

function scored(seatNo: number, category: string, points: number, over: Record<string, unknown> = {}) {
  act(() => {
    useYachtStore.getState().applyEvent({
      type: 'SCORED',
      seq: 9,
      payload: { seat: seatNo, category, points, upperSubtotal: 0, bonus: 0, total: points, auto: false, ...over },
    });
  });
}

/** D-204 — 살롱 연출 고도화: 컵에서 쏟는 굴림, 족보 축하 두 단계, 숫자 비행, 보너스, 좌석 띠 따라 굴림, 순위 뒤집기. */
describe('요트 연출 고도화 (D-204)', () => {
  beforeEach(() => {
    useAuthStore.setState({ token: 't' } as never);
    useCardAnimStore.setState({ enabled: true });
  });
  afterEach(() => vi.useRealTimers());

  describe('굴림', () => {
    it('착지 순서는 0~4 를 한 번씩 쓰고, 마지막 주사위도 ROLL_ANIMATION_MS(봇 템포 700ms 안)에 멈춘다', () => {
      expect([...LAND_ORDER].sort()).toEqual([0, 1, 2, 3, 4]);
      expect(4 * ROLL_STAGGER_MS + ROLL_DIE_MS).toBeLessThanOrEqual(ROLL_ANIMATION_MS);
      expect(ROLL_ANIMATION_MS).toBeLessThan(700);
    });

    it('굴리는 동안 컵이 나오고, 굴러오는 주사위에는 착지 고리가, 고정한 주사위에는 잠김 빛이 붙는다 — 끝나면 컵·고리는 사라진다', () => {
      vi.useFakeTimers();
      const props = {
        dice: [4, 4, 2, 4, 6] as Dice,
        held: [true, true, false, true, false] as Held,
        rolls: 2,
        canHold: true,
        canRoll: true,
        animate: true,
        turnName: null,
      };
      const { container, rerender } = render(<DiceTray {...props} rollNonce={0} />);
      expect(container.querySelector('.yt-cup')).toBeNull();
      // 고정한 주사위는 쉬는 동안에도 빛 조각을 갖는다(고정하는 순간 한 번 훑는다).
      expect(container.querySelectorAll('.yt-die-held .yt-die-sheen')).toHaveLength(3);
      rerender(<DiceTray {...props} rollNonce={1} />);
      expect(container.querySelector('.yt-cup svg')).not.toBeNull();
      expect(container.querySelectorAll('.yt-die-rolling .yt-die-impact')).toHaveLength(2);
      expect(container.querySelectorAll('.yt-die-lock')).toHaveLength(3);
      expect(container.querySelectorAll('.yt-die-rolling.yt-die-lock')).toHaveLength(0);
      const dice = [...container.querySelectorAll<HTMLElement>('.yt-die')];
      expect(dice.map((d) => d.style.getPropertyValue('--yt-o'))).toEqual(LAND_ORDER.map(String));
      act(() => {
        vi.advanceTimersByTime(ROLL_ANIMATION_MS);
      });
      expect(container.querySelector('.yt-cup')).toBeNull();
      expect(container.querySelector('.yt-die-impact')).toBeNull();
      expect(container.querySelector('.yt-die-lock')).toBeNull();
    });

    it('연출을 끄면 컵도 구르는 주사위도 없다', () => {
      const { container, rerender } = render(
        <DiceTray dice={[1, 2, 3, 4, 5]} held={NONE} rolls={1} rollNonce={0} canHold canRoll animate={false} turnName={null} />,
      );
      rerender(<DiceTray dice={[1, 2, 3, 4, 5]} held={NONE} rolls={1} rollNonce={1} canHold canRoll animate={false} turnName={null} />);
      expect(container.querySelector('.yt-cup, .yt-cube, .yt-die-impact')).toBeNull();
    });
  });

  describe('좌석 띠 따라 굴림', () => {
    const turn = (rolls: number, held: Held = NONE): YachtSeatTurn => ({
      seat: 1,
      dice: [1, 2, 3, 4, 5],
      held,
      rolls,
      pending: true,
      candidates: {},
    });

    it('굴림 수가 오르면 고정 안 한 작은 주사위만 잠깐 구른다 — 처음 그릴 때·홀드만 바뀔 때는 구르지 않는다', () => {
      vi.useFakeTimers();
      const { container, rerender } = render(<SeatDice turn={turn(1)} />);
      expect(container.querySelector('.yt-mini-die-rolling')).toBeNull();
      rerender(<SeatDice turn={turn(1, [true, false, false, false, false])} />);
      expect(container.querySelector('.yt-mini-die-rolling')).toBeNull();
      rerender(<SeatDice turn={turn(2, [true, false, false, false, false])} />);
      expect(container.querySelectorAll('.yt-mini-die-rolling')).toHaveLength(4);
      // 보조기기 이름은 구르는 동안에도 서버 눈이다.
      expect(screen.getByRole('img', { name: '주사위 1, 2, 3, 4, 5 · 굴림 2/3' })).toBeInTheDocument();
      act(() => {
        vi.advanceTimersByTime(SEAT_ROLL_MS);
      });
      expect(container.querySelector('.yt-mini-die-rolling')).toBeNull();
    });

    it('연출을 끄면 구르지 않는다', () => {
      const { container, rerender } = render(<SeatDice turn={turn(1)} animate={false} />);
      rerender(<SeatDice turn={turn(2)} animate={false} />);
      expect(container.querySelector('.yt-mini-die-rolling')).toBeNull();
    });
  });

  describe('celebrationDice — 현수막 주사위는 그 족보가 맞을 때만', () => {
    it('스트레이트는 오름차순, 풀 하우스는 3개 묶음 먼저', () => {
      expect(celebrationDice('LARGE_STRAIGHT', [5, 3, 6, 2, 4])).toEqual([2, 3, 4, 5, 6]);
      expect(celebrationDice('FULL_HOUSE', [6, 2, 6, 2, 2])).toEqual([2, 2, 2, 6, 6]);
      expect(celebrationDice('FULL_HOUSE', [1, 5, 5, 1, 5])).toEqual([5, 5, 5, 1, 1]);
      expect(celebrationDice('FULL_HOUSE', [4, 4, 4, 4, 4])).toEqual([4, 4, 4, 4, 4]);
      expect(celebrationDice('YACHT', [3, 3, 3, 3, 3])).toEqual([3, 3, 3, 3, 3]);
    });

    it('기억한 굴림이 낡아 족보와 안 맞으면 그리지 않는다', () => {
      expect(celebrationDice('LARGE_STRAIGHT', [1, 2, 3, 4, 6])).toBeNull();
      expect(celebrationDice('LARGE_STRAIGHT', [1, 2, 2, 3, 4])).toBeNull();
      expect(celebrationDice('FULL_HOUSE', [2, 2, 2, 2, 6])).toBeNull();
      expect(celebrationDice('FULL_HOUSE', [1, 2, 2, 3, 3])).toBeNull();
      expect(celebrationDice('YACHT', [3, 3, 3, 3, 4])).toBeNull();
      expect(celebrationDice('FULL_HOUSE', null)).toBeNull();
      expect(celebrationDice('CHOICE', [1, 2, 3, 4, 5])).toBeNull();
    });
  });

  describe('족보 축하 · 기록', () => {
    it('요트 칸 기록은 큰 축하 — 기포·리본·잔·현수막이 뜨고 YACHT_PARTY_MS 뒤 걷힌다', () => {
      vi.useFakeTimers();
      seed({ dice: [6, 6, 6, 6, 6], rolls: 1, candidates: { YACHT: 50 } });
      const { container } = renderTable();
      scored(0, 'YACHT', 50);
      const fx = container.querySelector('.yt-fx-yacht');
      expect(fx).not.toBeNull();
      expect(fx!.querySelectorAll('.yt-fx-bubbles i').length).toBeGreaterThanOrEqual(12);
      expect(fx!.querySelectorAll('.yt-fx-ribbon svg')).toHaveLength(2);
      expect(fx!.querySelectorAll('.yt-fx-coupe svg')).toHaveLength(2);
      expect(fx!.querySelector('.yt-fx-banner')?.textContent).toContain('나 · 50점');
      expect(container.querySelector('.yt-felt-party')).not.toBeNull();
      act(() => {
        vi.advanceTimersByTime(YACHT_PARTY_MS);
      });
      expect(container.querySelector('.yt-fx')).toBeNull();
      expect(container.querySelector('.yt-felt-party')).toBeNull();
    });

    it('요트 칸에 0점으로 버리면 축하가 없다', () => {
      seed({ dice: [1, 2, 3, 4, 6], rolls: 1, candidates: { YACHT: 0 } });
      const { container } = renderTable();
      scored(0, 'YACHT', 0);
      expect(container.querySelector('.yt-fx')).toBeNull();
    });

    it('라지 스트레이트·풀 하우스는 중간 축하 — 그 굴림의 주사위 다섯 개가 현수막에 놓인다', () => {
      vi.useFakeTimers();
      seed({ dice: [5, 3, 6, 2, 4], rolls: 2, candidates: { LARGE_STRAIGHT: 30 } });
      const { container } = renderTable();
      scored(0, 'LARGE_STRAIGHT', 30);
      const mid = container.querySelector('.yt-fx-mid');
      expect(mid).not.toBeNull();
      expect(container.querySelector('.yt-fx-yacht')).toBeNull();
      expect(mid!.querySelector('.yt-fx-banner-mid b')?.textContent).toBe('라지 스트레이트');
      expect(mid!.querySelectorAll('.yt-fx-dice-stair .yt-fx-die')).toHaveLength(5);
      // 오름차순 2·3·4·5·6 = 눈 20개.
      expect(mid!.querySelectorAll('.yt-fx-die circle')).toHaveLength(20);
      expect(container.querySelector('.yt-felt-cheer')).not.toBeNull();
      expect(container.querySelector('.yt-felt-party')).toBeNull();
      act(() => {
        vi.advanceTimersByTime(MID_PARTY_MS);
      });
      expect(container.querySelector('.yt-fx')).toBeNull();
    });

    it('남의 풀 하우스도 그 사람 이름으로 축하한다(차례대로 — 가운데 판이 그 사람 주사위)', () => {
      seed({ turnSeat: 1, dice: [6, 2, 6, 2, 2], rolls: 3, candidates: { FULL_HOUSE: 18 } });
      const { container } = renderTable();
      scored(1, 'FULL_HOUSE', 18);
      const banner = container.querySelector('.yt-fx-mid .yt-fx-banner-mid');
      expect(banner?.textContent).toContain('풀 하우스');
      expect(banner?.textContent).toContain('준호 · 18점');
      expect(container.querySelectorAll('.yt-fx-dice-house .yt-fx-die')).toHaveLength(5);
      expect(screen.getByText('🎉 풀 하우스!')).toBeInTheDocument();
    });

    it('날아가는 것은 점수 숫자다 — 잔상은 숫자가 없다', () => {
      seed({ dice: [1, 1, 3, 4, 6], rolls: 1, candidates: { ONES: 2 } });
      const { container } = renderTable();
      scored(0, 'ONES', 2, { upperSubtotal: 2 });
      const chips = [...container.querySelectorAll('.yt-chip')];
      expect(chips.length).toBeGreaterThan(1);
      expect(chips[0].querySelector('b')?.textContent).toBe('2');
      expect(container.querySelectorAll('.yt-chip-ghost b')).toHaveLength(0);
      expect(container.querySelector('.yt-fx')).toBeNull();
    });

    it('상단 보너스가 처음 붙는 기록은 보너스 축하 — 칸에 도장, 펠트에 명패, 상태 줄 배지', () => {
      vi.useFakeTimers();
      seed({
        seats: [seat(0, { upperSubtotal: 57, total: 57 }), seat(1)],
        dice: [6, 6, 1, 2, 3],
        rolls: 1,
        candidates: { SIXES: 12 },
      });
      const { container } = renderTable();
      expect(container.querySelector('.yt-cell-bonus-new')).toBeNull();
      scored(0, 'SIXES', 12, { upperSubtotal: 69, bonus: 35, total: 104 });
      const cell = container.querySelector('.yt-cell-bonus-new');
      expect(cell?.getAttribute('data-yt-bonus')).toBe('0');
      expect(cell?.textContent).toBe('+35');
      const banner = container.querySelector('.yt-fx-bonus .yt-fx-banner-mid');
      expect(banner?.textContent).toContain('상단 보너스');
      expect(banner?.textContent).toContain('나 · +35');
      expect(screen.getByText('보너스 +35!')).toBeInTheDocument();
      act(() => {
        vi.advanceTimersByTime(MID_PARTY_MS);
      });
      expect(container.querySelector('.yt-cell-bonus-new')).toBeNull();
      expect(container.querySelector('.yt-fx')).toBeNull();
    });

    it('이미 보너스가 있던 좌석의 다음 기록·처음 그릴 때는 보너스 축하가 없다', () => {
      seed({
        seats: [seat(0, { upperSubtotal: 69, bonus: 35, total: 104 }), seat(1)],
        dice: [1, 1, 1, 2, 3],
        rolls: 1,
        candidates: { ONES: 3 },
      });
      const { container } = renderTable();
      expect(container.querySelector('.yt-cell-bonus-new')).toBeNull();
      scored(0, 'ONES', 3, { upperSubtotal: 72, bonus: 35, total: 107 });
      expect(container.querySelector('.yt-cell-bonus-new')).toBeNull();
      expect(container.querySelector('.yt-fx')).toBeNull();
    });

    it('연출을 끄면 축하 층은 없고 상태 줄 배지만 남는다', () => {
      useCardAnimStore.setState({ enabled: false });
      seed({ dice: [6, 6, 6, 6, 6], rolls: 1, candidates: { YACHT: 50 } });
      const { container } = renderTable();
      scored(0, 'YACHT', 50);
      expect(container.querySelector('.yt-fx, .yt-chip, .yt-felt-party')).toBeNull();
      expect(screen.getByText('🎉 요트!')).toBeInTheDocument();
    });
  });

  describe('매치 끝', () => {
    const result = {
      reason: 'FINISHED' as const,
      placements: [
        { seat: 1, rank: 1, total: 210, deserted: false },
        { seat: 0, rank: 2, total: 180, deserted: false },
        { seat: 2, rank: 3, total: 90, deserted: false },
      ],
    };

    it('순위 줄은 꼴찌부터 뒤집힌다 — 맨 위(1위) 줄이 마지막', () => {
      const { container } = render(<YachtMatchEnd result={result} mySeat={0} nameOf={(n) => `P${n}`} />);
      expect(container.querySelector('.yt-end-reveal')).not.toBeNull();
      const rows = [...container.querySelectorAll<HTMLElement>('.yt-end-row')];
      expect(rows.map((r) => r.style.getPropertyValue('--yt-r'))).toEqual(['2', '1', '0']);
      expect(rows[0].classList.contains('yt-end-first')).toBe(true);
    });

    it('1위 줄이 다 뒤집힌 뒤에 금가루가 터진다(내가 1위가 아니어도) — 연출을 끄면 뒤집기도 금가루도 없다', () => {
      vi.useFakeTimers();
      const { container, unmount } = render(<YachtMatchEnd result={result} mySeat={0} nameOf={(n) => `P${n}`} />);
      act(() => {
        vi.advanceTimersByTime(firstRevealMs(3) - 1);
      });
      expect(container.querySelector('.yt-burst')).toBeNull();
      act(() => {
        vi.advanceTimersByTime(1);
      });
      expect(container.querySelectorAll('.yt-burst')).toHaveLength(1);
      unmount();

      // 내가 1위면 양옆에서 두 번 더.
      const mine = render(<YachtMatchEnd result={result} mySeat={1} nameOf={(n) => `P${n}`} />);
      act(() => {
        vi.advanceTimersByTime(firstRevealMs(3));
      });
      expect(mine.container.querySelectorAll('.yt-burst')).toHaveLength(3);
      mine.unmount();

      useCardAnimStore.setState({ enabled: false });
      const off = render(<YachtMatchEnd result={result} mySeat={1} nameOf={(n) => `P${n}`} />);
      act(() => {
        vi.advanceTimersByTime(firstRevealMs(3) + 1000);
      });
      expect(off.container.querySelector('.yt-end-reveal, .yt-burst')).toBeNull();
    });

    it('뒤집기 박자가 CSS 와 같다', () => {
      const css = yachtCssSource.replace(/\/\*[\s\S]*?\*\//g, '');
      const row = /\.yt-theme-salon \.yt-end-reveal \.yt-end-row\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
      expect(row).toContain(`yt-salon-flip ${END_FLIP_MS}ms`);
      expect(row).toContain(`calc(${END_FLIP_START_MS}ms + var(--yt-r, 0) * ${END_FLIP_GAP_MS}ms)`);
      expect(firstRevealMs(4)).toBeGreaterThan(END_FLIP_START_MS + 3 * END_FLIP_GAP_MS);
      expect(firstRevealMs(4)).toBeLessThanOrEqual(END_FLIP_START_MS + 3 * END_FLIP_GAP_MS + END_FLIP_MS);
    });
  });

  describe('CSS 박자 · 움직임 줄이기', () => {
    const css = yachtCssSource.replace(/\/\*[\s\S]*?\*\//g, '');

    it('숫자 비행 길이·좌석 띠 굴림 길이가 상수와 같다', () => {
      const chip = /\.yt-theme-salon \.yt-chip\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
      expect(chip).toContain(`yt-salon-chip ${CHIP_FLIGHT_MS / 1000}s`);
      const mini = /\.yt-theme-salon \.yt-mini-die-rolling\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
      const [, dur] = /yt-salon-mini-roll (\d+)ms/.exec(mini) ?? [];
      const [, gap] = /\* (\d+)ms/.exec(mini) ?? [];
      expect(4 * Number(gap) + Number(dur)).toBeLessThanOrEqual(SEAT_ROLL_MS);
    });

    it('컵·착지 고리도 굴림 길이 안에 끝난다', () => {
      const impact = /\.yt-theme-salon \.yt-die-impact\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
      expect(impact).toContain(`${ROLL_DIE_MS}ms`);
      expect(impact).toContain(`* ${ROLL_STAGGER_MS}ms`);
      const cup = /\.yt-theme-salon \.yt-cup\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
      const [, ms] = /yt-salon-cup (\d+)ms/.exec(cup) ?? [];
      expect(Number(ms)).toBeGreaterThan(0);
      expect(Number(ms)).toBeLessThanOrEqual(ROLL_ANIMATION_MS);
    });

    it('새 연출 조각은 기본 모습에서 숨고, 움직임 줄이기에서 꺼진다', () => {
      const hidden = /\.yt-seat-chip,[^{]*\{\s*display: none;/.exec(css)?.[0] ?? '';
      for (const cls of ['.yt-die-impact', '.yt-die-sheen', '.yt-die-lockring', '.yt-cup', '.yt-fx', '.yt-chip']) {
        expect(hidden, cls).toContain(cls);
      }
      for (const needle of [
        '.yt-theme-salon .yt-cup',
        '.yt-theme-salon .yt-die-impact',
        '.yt-theme-salon .yt-die-sheen',
        '.yt-theme-salon .yt-die-lockring',
        '.yt-theme-salon .yt-felt-cheer',
        '.yt-theme-salon .yt-die:nth-child(3).yt-die-rolling .yt-cube',
        '.yt-theme-salon .yt-mini-die-rolling',
        '.yt-theme-salon .yt-cell-bonus-new .yt-num',
        '.yt-theme-salon .yt-end-reveal .yt-end-row',
      ]) {
        expect(responsiveCssSource, needle).toContain(needle);
      }
    });
  });
});
