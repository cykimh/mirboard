import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DiceTray, ROLL_ANIMATION_MS } from './DiceTray';
import { YachtDie, YachtDieCube } from './YachtDie';
import { SeatDice } from './SeatDice';
import { YACHT_THEME, seatAccent, yachtThemeClass } from './yachtTheme';

/** D-203 — "미드나잇 살롱" 테마 층: SVG 주사위·정육면체 굴림·테마 손잡이. */
describe('요트 살롱 테마', () => {
  afterEach(() => vi.useRealTimers());

  it('기본 테마는 살롱이고 루트 클래스 하나로 얹는다', () => {
    expect(YACHT_THEME).toBe('salon');
    expect(yachtThemeClass).toBe(' yt-theme-salon');
    expect(seatAccent(0)).not.toBe(seatAccent(1));
    expect(seatAccent(4)).toBe(seatAccent(0));
  });

  it('주사위는 SVG 로 눈 수만큼 점을 그린다 — 빈 자리는 점이 없다', () => {
    for (let face = 1; face <= 6; face++) {
      const { container, unmount } = render(<YachtDie face={face} />);
      expect(container.querySelectorAll('.yt-die-svg-pip, .yt-die-svg-one')).toHaveLength(face);
      unmount();
    }
    const { container } = render(<YachtDie face={null} mini />);
    expect(container.querySelectorAll('circle')).toHaveLength(0);
  });

  it('한 문서의 주사위끼리 그라데이션 id 가 겹치지 않는다', () => {
    const { container } = render(
      <>
        <YachtDie face={3} />
        <YachtDie face={3} />
      </>,
    );
    const ids = [...container.querySelectorAll('[id]')].map((e) => e.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('굴러오는 정육면체는 앞면이 서버 눈이고 마주 보는 면의 합이 7 이다', () => {
    const { container } = render(<YachtDieCube face={2} />);
    const pips = (cls: string) => container.querySelectorAll(`.${cls} circle.yt-die-svg-pip, .${cls} circle.yt-die-svg-one`).length;
    expect(pips('yt-cube-front')).toBe(2);
    expect(pips('yt-cube-back')).toBe(5);
    expect(pips('yt-cube-right') + pips('yt-cube-left')).toBe(7);
    expect(pips('yt-cube-top') + pips('yt-cube-bottom')).toBe(7);
  });

  it('굴림 신호가 오면 고정 안 한 주사위만 정육면체로 굴리고 ROLL_ANIMATION_MS 뒤 평면으로 돌아온다 — 그동안에도 눌린다', () => {
    vi.useFakeTimers();
    const onToggle = vi.fn();
    const props = {
      dice: [4, 4, 2, 4, 6] as [number, number, number, number, number],
      held: [true, true, false, true, false] as [boolean, boolean, boolean, boolean, boolean],
      rolls: 2,
      canHold: true,
      canRoll: true,
      animate: true,
      onToggle,
      onRoll: () => {},
      turnName: null,
    };
    const { container, rerender } = render(<DiceTray {...props} rollNonce={0} />);
    expect(container.querySelectorAll('.yt-cube')).toHaveLength(0);
    rerender(<DiceTray {...props} rollNonce={1} />);
    expect(container.querySelectorAll('.yt-die-rolling .yt-cube')).toHaveLength(2);
    // 보조기기 이름은 굴러오는 동안에도 서버가 준 눈이다.
    screen.getByRole('button', { name: '주사위 3: 2' }).click();
    expect(onToggle).toHaveBeenCalledWith(2);
    act(() => {
      vi.advanceTimersByTime(ROLL_ANIMATION_MS);
    });
    expect(container.querySelectorAll('.yt-cube')).toHaveLength(0);
  });

  it('다섯 개가 같게 멈추면 주사위 줄에 반짝임 클래스가 붙는다(굴리기 전·서로 다르면 없다)', () => {
    const base = { held: [false, false, false, false, false] as [boolean, boolean, boolean, boolean, boolean], rollNonce: 0, canHold: false, canRoll: false, animate: true, turnName: null };
    const same = render(<DiceTray {...base} dice={[5, 5, 5, 5, 5]} rolls={1} />);
    expect(same.container.querySelector('.yt-dice-same')).not.toBeNull();
    same.unmount();
    const mixed = render(<DiceTray {...base} dice={[5, 5, 5, 5, 4]} rolls={1} />);
    expect(mixed.container.querySelector('.yt-dice-same')).toBeNull();
  });

  it('좌석 띠 작은 주사위도 같은 SVG 다 — 고정은 클래스로, 안 굴렸으면 빈 자리', () => {
    const { container } = render(
      <SeatDice turn={{ seat: 1, dice: [1, 2, 3, 4, 5], held: [true, false, false, false, false], rolls: 1, pending: true, candidates: {} }} />,
    );
    expect(container.querySelectorAll('.yt-mini-die svg.yt-die-svg-mini')).toHaveLength(5);
    expect(container.querySelectorAll('.yt-mini-die-held')).toHaveLength(1);
    expect(screen.getByRole('img', { name: '주사위 1, 2, 3, 4, 5 · 굴림 1/3' })).toBeInTheDocument();
  });
});
