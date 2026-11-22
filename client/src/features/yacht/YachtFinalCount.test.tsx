import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FINAL_COUNT_SECONDS, YachtFinalCount, YachtTurnCountdown, resetFinalCount } from './YachtTurnCountdown';
import { useYachtStore } from './yachtStore';
import type { Held, YachtTableView } from '@/types/yacht';

/**
 * D-293 — 내 차례 마지막 3초의 큰 3·2·1 과 "똑". 가짜 시계로 초를 넘기며 무엇이 울렸는지 본다(합성·음소거 규칙은 `tableSfx.test`).
 */
const playTableSfx = vi.fn();
vi.mock('@/features/sound/tableSfx', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/sound/tableSfx')>();
  return { ...actual, playTableSfx: (cue: string) => playTableSfx(cue) };
});

const NONE: Held = [false, false, false, false, false];
/** 서버 스냅샷 — 차례 남은 시간(ms)을 싣는다. */
function snapshot(turnRemainingMs: number | null, over: Partial<YachtTableView> = {}) {
  const t: YachtTableView = {
    phase: 'PLAYING',
    seats: [
      { seat: 0, scores: {}, upperSubtotal: 0, bonus: 0, total: 0, deserted: false },
      { seat: 1, scores: {}, upperSubtotal: 0, bonus: 0, total: 0, deserted: false },
    ],
    round: 1,
    turnSeat: 0,
    dice: null,
    held: NONE,
    rolls: 0,
    candidates: {},
    turnRemainingMs,
    result: null,
    ...over,
  } as YachtTableView;
  act(() => {
    useYachtStore.getState().applySnapshot({
      roomId: 'r-1',
      phase: t.phase,
      eventSeq: 1,
      tableView: t,
      privateHand: null,
      disconnectedSeats: [],
    });
  });
}
const pass = (ms: number) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });
const cues = () => playTableSfx.mock.calls.map(([c]) => c);

function mount(props: Partial<Parameters<typeof YachtFinalCount>[0]> = {}) {
  return render(
    <>
      <YachtTurnCountdown turnSeconds={45} />
      <YachtFinalCount turnSeconds={45} active turnKey="r-1:0:1" animate {...props} />
    </>,
  );
}
const big = (c: HTMLElement) => c.querySelector('.yt-final');

describe('얏추 D-293 — 마지막 3초', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000);
    playTableSfx.mockReset();
    resetFinalCount();
    useYachtStore.getState().reset('r-1');
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('3초 남기 전에는 조용하고, 3·2·1 이 한 번씩 울린다 — 3·2 는 "똑", 1 은 높은 소리', () => {
    snapshot(5_500);
    const { container } = mount();
    expect(big(container)).toBeNull();
    pass(2_000);
    expect(cues()).toEqual([]);
    pass(600);
    // 2.9초 남음 → 3
    expect(big(container)).toHaveTextContent('3');
    expect(cues()).toEqual(['clock.tick']);
    pass(1_000);
    expect(big(container)).toHaveTextContent('2');
    expect(cues()).toEqual(['clock.tick', 'clock.tick']);
    pass(1_000);
    expect(big(container)).toHaveTextContent('1');
    expect(big(container)).toHaveClass('yt-final-last');
    expect(cues()).toEqual(['clock.tick', 'clock.tick', 'clock.last']);
    pass(1_000);
    // 0초 — 큰 숫자가 걷히고 더 울리지 않는다.
    expect(big(container)).toBeNull();
    pass(3_000);
    expect(cues()).toHaveLength(FINAL_COUNT_SECONDS);
  });

  it('큰 숫자는 시계 배지와 같은 값이다', () => {
    snapshot(2_400);
    const { container, getByRole } = mount();
    expect(getByRole('timer')).toHaveAccessibleName('이번 차례 남은 시간 3초');
    expect(big(container)).toHaveTextContent('3');
    pass(500);
    expect(getByRole('timer')).toHaveAccessibleName('이번 차례 남은 시간 2초');
    expect(big(container)).toHaveTextContent('2');
  });

  it('resync 로 시계가 다시 와도(조금 물러나도) 같은 숫자는 다시 울리지 않는다 — 다시 그려도 마찬가지', () => {
    snapshot(2_900);
    const first = mount();
    expect(cues()).toEqual(['clock.tick']);
    pass(1_200);
    expect(cues()).toEqual(['clock.tick', 'clock.tick']);
    // 다시 붙음 — 서버는 2.4초 남았다고 한다(화면은 1.7초였다). 숫자는 3 으로 물러나지만 소리는 없다.
    snapshot(2_400);
    expect(big(first.container)).toHaveTextContent('3');
    expect(cues()).toHaveLength(2);
    // 게임판이 다시 그려져도(같은 차례) 울린 기록이 남아 있다.
    first.unmount();
    const again = mount();
    expect(big(again.container)).toHaveTextContent('3');
    expect(cues()).toHaveLength(2);
    pass(1_500);
    expect(cues()).toEqual(['clock.tick', 'clock.tick', 'clock.last']);
  });

  it('다음 차례(다른 turnKey)에는 다시 처음부터 울린다', () => {
    snapshot(2_900);
    const { rerender } = mount();
    expect(cues()).toEqual(['clock.tick']);
    snapshot(2_900, { round: 2 });
    rerender(
      <>
        <YachtTurnCountdown turnSeconds={45} />
        <YachtFinalCount turnSeconds={45} active turnKey="r-1:0:2" animate />
      </>,
    );
    expect(cues()).toEqual(['clock.tick', 'clock.tick']);
  });

  it('내 차례가 아니면(남의 차례·관전·기록을 마친 뒤) 숫자도 소리도 없다', () => {
    snapshot(2_900);
    const { container } = mount({ active: false });
    pass(2_500);
    expect(big(container)).toBeNull();
    expect(cues()).toEqual([]);
  });

  it("턴 제한이 '끔'이면 아무것도 하지 않는다", () => {
    snapshot(null);
    const { container } = mount({ turnSeconds: 0 });
    pass(10_000);
    expect(big(container)).toBeNull();
    expect(cues()).toEqual([]);
  });

  it('연출을 끄면 큰 숫자는 움직임 없이 바뀌기만 하고(yt-final-live 없음) 소리는 그대로 난다', () => {
    snapshot(2_900);
    const { container } = mount({ animate: false });
    expect(big(container)).toHaveTextContent('3');
    expect(big(container)).not.toHaveClass('yt-final-live');
    expect(cues()).toEqual(['clock.tick']);
  });
});
