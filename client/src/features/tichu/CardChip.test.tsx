import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CardBack, CardChip, CardFaceArt } from './CardChip';
import type { Card } from '@/types/tichu';

const JADE_2: Card = { suit: 'JADE', rank: 2, special: null };
const STAR_10: Card = { suit: 'STAR', rank: 10, special: null };
const DRAGON: Card = { suit: null, rank: 0, special: 'DRAGON' };
const SPECIALS: Card[] = (['MAHJONG', 'DOG', 'PHOENIX', 'DRAGON'] as const).map((special) => ({
  suit: null,
  rank: 0,
  special,
}));

/** D-241 — 옻칠 테마(기본)의 카드. 기본 모습은 `CardChip.classic.test.tsx` 가 테마 상수를 되돌려 본다. */
describe('CardChip (옻칠 테마)', () => {
  it('일반 카드는 왼쪽 위 모서리 표기(숫자 + 무늬)와 큰 무늬를 직접 그린 SVG 로 그린다 — 이미지 없음', () => {
    const { container } = render(<CardChip card={JADE_2} />);
    expect(screen.queryByRole('img')).toBeNull();
    expect(container.querySelector('.card-chip.tc-card')).not.toBeNull();
    expect(container.querySelector('.tc-face.tc-suit-jade')).not.toBeNull();
    expect(container.querySelector('.tc-index .tc-rank')?.textContent).toBe('2');
    expect(container.querySelector('.tc-index svg.tc-pip')).not.toBeNull();
    expect(container.querySelector('svg.tc-emblem')).not.toBeNull();
  });

  it('두 글자 숫자(10)는 좁게 쓰는 클래스가 붙는다 — 겹친 손패의 좁은 띠 안에 든다', () => {
    const { container } = render(<CardChip card={STAR_10} />);
    const rank = container.querySelector('.tc-rank');
    expect(rank?.textContent).toBe('10');
    expect(rank?.className).toContain('tc-rank-wide');
  });

  it('무늬 넷이 서로 다른 그림이다 — 색만이 아니라 모양으로도 구분된다', () => {
    const shapes = (['JADE', 'SWORD', 'PAGODA', 'STAR'] as const).map((suit) => {
      const { container, unmount } = render(<CardChip card={{ suit, rank: 7, special: null }} />);
      const html = container.querySelector('svg.tc-pip')?.innerHTML ?? '';
      unmount();
      return html;
    });
    expect(shapes.every((s) => s.length > 0)).toBe(true);
    expect(new Set(shapes).size).toBe(4);
  });

  it('특수 카드 넷은 인라인 SVG 그림 + 모서리 한 글자 — 외부 이미지를 부르지 않는다', () => {
    const labels: string[] = [];
    const arts: string[] = [];
    for (const card of SPECIALS) {
      const { container, unmount } = render(<CardChip card={card} />);
      expect(container.querySelector('img')).toBeNull();
      expect(container.querySelector(`.tc-face-special.tc-sp-${card.special!.toLowerCase()}`)).not.toBeNull();
      arts.push(container.querySelector('svg.tc-art')?.innerHTML ?? '');
      labels.push(container.querySelector('.tc-index .tc-rank')?.textContent ?? '');
      unmount();
    }
    expect(labels).toEqual(['1', '개', '봉', '용']);
    expect(arts.every((a) => a.length > 100)).toBe(true);
    expect(new Set(arts).size).toBe(4);
  });

  it('그림에 id 를 쓰지 않는다 — 같은 카드가 여러 장 놓여도 겹치지 않는다', () => {
    const { container } = render(
      <>
        {SPECIALS.map((c) => (
          <CardChip key={c.special} card={c} />
        ))}
        <CardChip card={JADE_2} />
        <CardBack />
      </>,
    );
    expect(container.querySelectorAll('[id]')).toHaveLength(0);
  });

  it('선택·aria 의미는 그대로다', () => {
    let clicked = 0;
    render(<CardChip card={JADE_2} selected onClick={() => clicked++} />);
    const button = screen.getByRole('button');
    expect(button).toHaveAttribute('aria-pressed', 'true');
    expect(button.className).toContain('selected');
    expect(button).toHaveAttribute('aria-label', 'JADE 2');
    fireEvent.click(button);
    expect(clicked).toBe(1);
    render(<CardChip card={DRAGON} />);
    expect(screen.getByLabelText('DRAGON 🐉')).toBeInTheDocument();
  });

  it('연출용 카드는 버튼이 아니고, 뒷면은 금박 문양 SVG 를 든다', () => {
    const { container } = render(
      <>
        <CardFaceArt card={JADE_2} />
        <CardBack className="x" />
      </>,
    );
    expect(screen.queryByRole('button')).toBeNull();
    expect(container.querySelector('span.card-chip.tc-card.tc-card-static .tc-face')).not.toBeNull();
    expect(container.querySelector('.tc-back.x svg.tc-back-art')).not.toBeNull();
  });
});
