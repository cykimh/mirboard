import { render } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it } from 'vitest';
import { GAME_ICON_IDS, GameIcon, hasGameIcon } from './GameIcon';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';

/** 게임판이 있는 일곱 게임(pages/gameTables 의 키와 같다). */
const GAMES = ['TICHU', 'SKULL_KING', 'ONE_CARD', 'YACHT', 'OMOK', 'CLOUD_ROUTE', 'ARTILLERY'];

const svgOf = (el: HTMLElement) => el.querySelector('svg') as SVGSVGElement;

describe('GameIcon (D-188)', () => {
  beforeEach(() => {
    useCardAnimStore.setState({ enabled: true });
  });

  it('일곱 게임 모두 전용 아이콘이 있다 (매핑 누락 없음)', () => {
    expect([...GAME_ICON_IDS].sort()).toEqual([...GAMES].sort());
    for (const id of GAMES) {
      expect(hasGameIcon(id), id).toBe(true);
      const { container } = render(<GameIcon gameId={id} />);
      expect(svgOf(container).getAttribute('data-game-icon')).toBe(id);
    }
  });

  it('게임마다 그림이 다르다', () => {
    const marks = GAMES.map((id) =>
      renderToStaticMarkup(<GameIcon gameId={id} size={48} />).replace(new RegExp(id, 'g'), ''),
    );
    expect(new Set(marks).size).toBe(GAMES.length);
  });

  it('모르는 id·빈 값이면 기본 아이콘', () => {
    for (const id of ['NOPE', '', null, undefined, 'toString']) {
      expect(hasGameIcon(id)).toBe(false);
      const { container } = render(<GameIcon gameId={id} />);
      expect(svgOf(container).getAttribute('data-game-icon')).toBe('DEFAULT');
    }
  });

  it('size 가 한 변 길이가 된다 (기본 24)', () => {
    const { container } = render(
      <>
        <GameIcon gameId="YACHT" />
        <GameIcon gameId="YACHT" size={96} />
      </>,
    );
    const [a, b] = Array.from(container.querySelectorAll('svg'));
    expect(a.getAttribute('width')).toBe('24');
    expect(b.getAttribute('width')).toBe('96');
    expect(b.getAttribute('height')).toBe('96');
  });

  it('작은 크기는 단순형이다 — 잔 장식·그림자가 없다', () => {
    const small = renderToStaticMarkup(<GameIcon gameId="OMOK" size={16} />);
    const large = renderToStaticMarkup(<GameIcon gameId="OMOK" size={48} />);
    expect(small).not.toContain('<filter');
    expect(large).toContain('<filter');
    expect(small.length).toBeLessThan(large.length);
  });

  it('title 이 없으면 장식(aria-hidden), 있으면 이름 붙은 그림', () => {
    const { container, getByRole } = render(
      <>
        <GameIcon gameId="OMOK" />
        <GameIcon gameId="OMOK" title="오목" />
      </>,
    );
    expect(container.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
    expect(getByRole('img', { name: '오목' })).toBeTruthy();
  });

  it('한 화면의 아이콘끼리 defs id 가 겹치지 않는다', () => {
    const { container } = render(
      <>
        {GAMES.map((id) => (
          <GameIcon key={id} gameId={id} size={48} />
        ))}
        <GameIcon gameId="TICHU" size={48} />
      </>,
    );
    const ids = Array.from(container.querySelectorAll('[id]')).map((e) => e.id);
    expect(ids.length).toBeGreaterThan(GAMES.length);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('interactive 는 움직임 클래스를 붙이고, 연출 끔이면 붙이지 않는다', () => {
    const on = render(<GameIcon gameId="TICHU" size={48} interactive />);
    expect(svgOf(on.container).getAttribute('class')).toContain('group-hover');
    on.unmount();

    useCardAnimStore.setState({ enabled: false });
    const off = render(<GameIcon gameId="TICHU" size={48} interactive />);
    expect(off.container.innerHTML).not.toContain('group-hover');
    off.unmount();

    useCardAnimStore.setState({ enabled: true });
    const plain = render(<GameIcon gameId="TICHU" size={48} />);
    expect(plain.container.innerHTML).not.toContain('group-hover');
  });
});
