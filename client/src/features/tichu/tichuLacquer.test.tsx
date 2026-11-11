import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArenaFxLayer, wishLabel } from './ArenaFxLayer';
import { EffectsOverlay } from './EffectsOverlay';
import { SeatCardStack } from './SeatCardStack';
import { ARENA_FX_DURATION, ARENA_FX_MAX, useEffectStore } from './effectStore';
import { useTichuStore } from './tichuStore';
import { LACQUER_MIN_VISIBLE_STEP, TICHU_THEME, lacquerTheme, tichuCardClass, tichuThemeClass } from './tichuTheme';
import { MIN_VISIBLE_STEP, shouldWrapHand } from './useHandOverlap';
import type { TableView } from '@/types/tichu';

vi.mock('@/features/sound/useSfx', () => ({ useSfx: () => ({ play: vi.fn(), muted: true, toggleMute: vi.fn(), playChime: vi.fn() }) }));

const fx = () => useEffectStore.getState();

beforeEach(() => {
  vi.useFakeTimers();
  fx().clear();
  fx().clearArenaFx();
});
afterEach(() => vi.useRealTimers());

/** D-241 — "옻칠 찻집" 테마 층과 경기장 연출 줄. */
describe('티츄 옻칠 테마 층', () => {
  it('기본 테마는 옻칠이고 루트·카드에 클래스 하나씩만 얹는다', () => {
    expect(TICHU_THEME).toBe('lacquer');
    expect(lacquerTheme).toBe(true);
    expect(tichuThemeClass).toBe(' tc-theme-lacquer');
    expect(tichuCardClass).toBe(' tc-card');
  });

  it('손패 겹침 하한을 낮춰 375px 에서 14장이 한 줄에 든다 — 기본 하한이면 줄을 바꿨다', () => {
    // 375px 화면의 손패 칸 ≈337px, 카드 48px.
    expect(shouldWrapHand(337, 48, 14, MIN_VISIBLE_STEP)).toBe(true);
    expect(shouldWrapHand(337, 48, 14, LACQUER_MIN_VISIBLE_STEP)).toBe(false);
    // 아주 좁으면(320px) 여전히 줄을 바꾼다.
    expect(shouldWrapHand(282, 48, 14, LACQUER_MIN_VISIBLE_STEP)).toBe(true);
  });

  it('좌석 카드 더미는 옻칠 뒷면(인라인 SVG)이고 장수·부채 수는 그대로다', () => {
    const { container } = render(<SeatCardStack count={8} viewPos="n" />);
    expect(container.querySelectorAll('.seat-cardback')).toHaveLength(6);
    expect(container.querySelectorAll('.seat-cardback .tc-back svg')).toHaveLength(6);
    expect(container.querySelector('img')).toBeNull();
  });
});

describe('effectStore — 경기장 연출 줄(arenaFx)', () => {
  it('넣으면 id·만료 시각이 붙고, 만료분은 prune 이 걷는다', () => {
    fx().pushArenaFx({ kind: 'WISH', rank: 7 });
    const [first] = fx().arenaFx;
    expect(first.kind).toBe('WISH');
    expect(first.expiresAt - Date.now()).toBe(ARENA_FX_DURATION.WISH);
    vi.advanceTimersByTime(ARENA_FX_DURATION.WISH - 1);
    fx().pruneArenaFx();
    expect(fx().arenaFx).toHaveLength(1);
    vi.advanceTimersByTime(2);
    fx().pruneArenaFx();
    expect(fx().arenaFx).toHaveLength(0);
  });

  it('연출 층이 없어도 쌓이지 않는다 — 넣을 때 만료분을 걷고 최대 개수를 지킨다', () => {
    for (let i = 0; i < ARENA_FX_MAX + 5; i++) fx().pushArenaFx({ kind: 'DECLARE_STAMP', seat: i % 4 });
    expect(fx().arenaFx).toHaveLength(ARENA_FX_MAX);
    vi.advanceTimersByTime(ARENA_FX_DURATION.DECLARE_STAMP + 1);
    fx().pushArenaFx({ kind: 'WISH', rank: 3 });
    expect(fx().arenaFx.map((f) => f.kind)).toEqual(['WISH']);
  });

  it('화면 전역 배너(active)와 따로 돈다 — 그랜드 여부가 실린다', () => {
    fx().trigger('TICHU_DECLARED', '그랜드', undefined, true);
    fx().pushArenaFx({ kind: 'DECLARE_STAMP', seat: 2, grand: true });
    expect(fx().active?.grand).toBe(true);
    fx().clear();
    expect(fx().arenaFx).toHaveLength(1);
  });
});

const TABLE: TableView = {
  phase: 'PLAYING',
  dealingCardCount: 14,
  readySeats: [],
  passingSubmittedSeats: [],
  currentTurnSeat: 1,
  handCounts: { 0: 13, 1: 14, 2: 14, 3: 14 },
  currentTop: { type: 'SINGLE', cards: [{ suit: 'JADE', rank: 9, special: null }], rank: 9, length: 1 },
  currentTopSeat: 0,
  declarations: {},
  roundScores: {},
  matchScores: {},
  roundNumber: 1,
  finishingOrder: [],
  activeWishRank: null,
};

describe('tichuStore → 경기장 연출 (화면만 — 리듀서 결과는 그대로)', () => {
  beforeEach(() => {
    useTichuStore.getState().reset('r');
    useTichuStore.getState().applySnapshot({ tableView: TABLE, privateHand: { seat: 0, cards: [] } });
  });
  const apply = (type: string, payload: unknown) =>
    useTichuStore.getState().applyEvent({ seq: 1, type, payload });

  it('TRICK_TAKEN — 가운데 카드와 점수를 실어 가져간 좌석으로 쓸어 간다', () => {
    expect(apply('TRICK_TAKEN', { takerSeat: 2, trickPoints: 25 })).toBe('applied');
    const [sweep] = fx().arenaFx;
    expect(sweep).toMatchObject({ kind: 'TRICK_SWEEP', seat: 2, points: 25 });
    expect(sweep.cards).toHaveLength(1);
    expect(useTichuStore.getState().tableView?.currentTop).toBeNull();
    expect(useTichuStore.getState().tableView?.roundScores.A).toBe(25);
  });

  it('TICHU_DECLARED·WISH_MADE·DRAGON_GIVEN 이 각자의 연출을 넣는다', () => {
    apply('TICHU_DECLARED', { seat: 3, kind: 'GRAND_TICHU' });
    apply('WISH_MADE', { rank: 12 });
    apply('DRAGON_GIVEN', { fromSeat: 0, toSeat: 1 });
    expect(fx().arenaFx.map((f) => f.kind)).toEqual(['DECLARE_STAMP', 'WISH', 'DRAGON_GIVEN']);
    expect(fx().arenaFx[0]).toMatchObject({ seat: 3, grand: true });
    expect(fx().arenaFx[2]).toMatchObject({ fromSeat: 0, seat: 1 });
    expect(fx().active).toMatchObject({ kind: 'TICHU_DECLARED', grand: true });
    expect(useTichuStore.getState().tableView?.activeWishRank).toBe(12);
  });
});

describe('ArenaFxLayer', () => {
  const layer = (over: Partial<Parameters<typeof ArenaFxLayer>[0]> = {}) => (
    <ArenaFxLayer mySeat={0} isInDealing={false} dealingCardCount={0} cardAnimEnabled {...over} />
  );

  it('연출이 없으면 아무것도 그리지 않는다', () => {
    const { container } = render(layer());
    expect(container.firstChild).toBeNull();
  });

  it('좌석 방향은 좌석 클래스와 같은 매핑 — +1 은 왼쪽(e), +2 는 위(n)', () => {
    const { container } = render(layer());
    act(() => {
      fx().pushArenaFx({ kind: 'TRICK_SWEEP', seat: 1, cards: TABLE.currentTop!.cards, points: 10 });
      fx().pushArenaFx({ kind: 'DECLARE_STAMP', seat: 2, grand: true });
      fx().pushArenaFx({ kind: 'DRAGON_GIVEN', fromSeat: 0, seat: 3 });
      fx().pushArenaFx({ kind: 'WISH', rank: 14 });
    });
    expect(container.querySelector('.tc-fx-layer')?.getAttribute('aria-hidden')).toBe('true');
    expect(container.querySelector('.tc-fx-sweep.tc-to-e .tc-card-static')).not.toBeNull();
    expect(container.querySelector('.tc-fx-points.tc-to-e')?.textContent).toBe('+10');
    expect(container.querySelector('.tc-fx-stamp.tc-to-n.tc-fx-stamp-grand .tc-seal')?.textContent).toBe('그랜드');
    expect(container.querySelector('.tc-fx-dragon.tc-from-s.tc-to-w')).not.toBeNull();
    expect(container.querySelector('.tc-fx-wish-tile b')?.textContent).toBe('A');
    expect(container.querySelector('button')).toBeNull();
  });

  it('0점 트릭은 점수를 띄우지 않는다', () => {
    const { container } = render(layer());
    act(() => fx().pushArenaFx({ kind: 'TRICK_SWEEP', seat: 2, cards: [], points: 0 }));
    expect(container.querySelector('.tc-fx-sweep')).not.toBeNull();
    expect(container.querySelector('.tc-fx-points')).toBeNull();
  });

  it('받은 장수가 바뀔 때마다 딜 연출을 한 번 — 네 좌석으로 뒷면이 날아가고, 시간이 지나면 걷힌다', () => {
    const { container, rerender } = render(layer({ isInDealing: true, dealingCardCount: 8 }));
    expect(container.querySelectorAll('.tc-fx-deal')).toHaveLength(1);
    for (const pos of ['s', 'e', 'n', 'w']) {
      expect(container.querySelectorAll(`.tc-fx-deal-card.tc-to-${pos} .tc-back`)).toHaveLength(3);
    }
    rerender(layer({ isInDealing: true, dealingCardCount: 8 }));
    expect(fx().arenaFx).toHaveLength(1);
    rerender(layer({ isInDealing: true, dealingCardCount: 14 }));
    expect(fx().arenaFx).toHaveLength(2);
    act(() => {
      vi.advanceTimersByTime(ARENA_FX_DURATION.DEAL + 100);
    });
    expect(fx().arenaFx).toHaveLength(0);
    expect(container.firstChild).toBeNull();
  });

  it("카드 애니메이션을 끄면 카드가 움직이는 연출은 없다 — 인장·소원 패만 남는다", () => {
    const { container } = render(layer({ isInDealing: true, dealingCardCount: 8, cardAnimEnabled: false }));
    expect(fx().arenaFx).toHaveLength(0);
    act(() => {
      fx().pushArenaFx({ kind: 'TRICK_SWEEP', seat: 1, cards: [], points: 5 });
      fx().pushArenaFx({ kind: 'DECLARE_STAMP', seat: 1 });
    });
    expect(container.querySelector('.tc-fx-sweep')).toBeNull();
    expect(container.querySelector('.tc-fx-stamp .tc-seal')?.textContent).toBe('티츄');
  });

  it('게임판을 떠나면 남은 연출을 버린다', () => {
    const { unmount } = render(layer());
    act(() => fx().pushArenaFx({ kind: 'WISH', rank: 2 }));
    unmount();
    expect(fx().arenaFx).toHaveLength(0);
  });

  it('소원 표기 — 11~14 는 J·Q·K·A', () => {
    expect([2, 10, 11, 12, 13, 14].map(wishLabel)).toEqual(['2', '10', 'J', 'Q', 'K', 'A']);
    expect(wishLabel(null)).toBe('');
  });
});

describe('EffectsOverlay (옻칠 테마 그림)', () => {
  it('폭탄 — 파편과 문구, 스트레이트 플러시는 따로 표시', () => {
    const { container } = render(<EffectsOverlay />);
    act(() => fx().trigger('BOMB'));
    expect(container.querySelector('.tc-fx-bomb .tc-fx-bomb-label')?.textContent).toBe('폭탄!');
    expect(container.querySelectorAll('.tc-fx-shard').length).toBeGreaterThan(12);
    act(() => fx().trigger('STRAIGHT_FLUSH_BOMB'));
    expect(container.querySelector('.tc-fx-bomb.tc-fx-bomb-sf .tc-fx-bomb-label')?.textContent).toContain('스트레이트 플러시');
  });

  it('선언 — 큰 인장, 그랜드는 한 줄 더', () => {
    const { container } = render(<EffectsOverlay />);
    act(() => fx().trigger('TICHU_DECLARED', '🔔 티츄! · 시트 1', undefined, false));
    expect(container.querySelector('.tc-fx-declare .tc-seal-big')?.textContent).toBe('티츄');
    act(() => fx().trigger('TICHU_DECLARED', '👑 그랜드 티츄! · 시트 1', undefined, true));
    expect(container.querySelector('.tc-fx-declare-grand .tc-seal-big')?.textContent).toBe('그랜드티츄');
  });

  it('매치 끝 — 이기면 금가루·등불, 지면 등불 하나만. 노출 시간이 지나면 걷힌다', () => {
    const { container } = render(<EffectsOverlay />);
    act(() => fx().trigger('MATCH_VICTORY', '🏆 승리!', 'win'));
    expect(container.querySelector('.tc-fx-plaque')?.textContent).toBe('승리!');
    expect(container.querySelectorAll('.tc-fx-dust').length).toBeGreaterThan(20);
    expect(container.querySelectorAll('.tc-fx-lantern')).toHaveLength(5);
    act(() => fx().trigger('MATCH_VICTORY', '아쉽게 패배', 'lose'));
    expect(container.querySelector('.tc-fx-victory-lose .tc-fx-plaque')?.textContent).toBe('아쉽게 패배');
    expect(container.querySelectorAll('.tc-fx-dust')).toHaveLength(0);
    expect(container.querySelectorAll('.tc-fx-lantern')).toHaveLength(1);
    act(() => {
      vi.advanceTimersByTime(3600);
    });
    expect(container.querySelector('.tc-fx-screen')).toBeNull();
  });
});
