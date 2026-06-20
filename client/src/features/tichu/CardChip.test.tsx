import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CardChip } from './CardChip';
import type { Card } from '@/types/tichu';

const JADE_2: Card = { suit: 'JADE', rank: 2, special: null };
const DRAGON: Card = { suit: null, rank: 0, special: 'DRAGON' };

describe('CardChip', () => {
  it('renders the card image by default (no glyph)', () => {
    render(<CardChip card={JADE_2} />);
    const img = screen.getByRole('img') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/cards/jade-2.svg');
    // 이미지 모드에서는 텍스트 글리프를 쓰지 않는다.
    expect(screen.queryByText('◆')).toBeNull();
  });

  it('falls back to glyph + rank when the image fails to load', () => {
    render(<CardChip card={JADE_2} />);
    fireEvent.error(screen.getByRole('img'));
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('◆')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('uses the special card image and emoji fallback', () => {
    render(<CardChip card={DRAGON} />);
    const img = screen.getByRole('img') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/cards/dragon.svg');
    fireEvent.error(img);
    expect(screen.getByText('🐉')).toBeInTheDocument();
  });

  it('preserves selected + aria semantics', () => {
    render(<CardChip card={JADE_2} selected />);
    const button = screen.getByRole('button');
    expect(button).toHaveAttribute('aria-pressed', 'true');
    expect(button.className).toContain('selected');
    expect(button).toHaveAttribute('aria-label', 'JADE 2');
  });
});
