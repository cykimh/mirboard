import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { CardChip } from './CardChip';
import { useColorblindStore } from '@/features/theme/colorblindStore';
import type { Card } from '@/types/tichu';

const JADE_2: Card = { suit: 'JADE', rank: 2, special: null };
const DRAGON: Card = { suit: null, rank: 0, special: 'DRAGON' };

describe('CardChip', () => {
  // 이전 테스트 언마운트(RTL cleanup) 후 리셋 → 마운트된 컴포넌트 재렌더 act 경고 방지.
  beforeEach(() => useColorblindStore.setState({ enabled: false }));

  it('renders a suited card as intuitive rank + suit glyph (no pip image)', () => {
    render(<CardChip card={JADE_2} />);
    // 트럼프 핍 SVG 대신 숫자 + 슈트 문양만 — 이미지 없음.
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('◆')).toBeInTheDocument();
  });

  it('uses the special card image and emoji fallback', () => {
    render(<CardChip card={DRAGON} />);
    const img = screen.getByRole('img') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/cards/dragon.svg');
    fireEvent.error(img);
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('🐉')).toBeInTheDocument();
  });

  it('keeps a single suit glyph in colorblind mode (no duplicate, still no image)', () => {
    useColorblindStore.setState({ enabled: true });
    render(<CardChip card={JADE_2} />);
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getAllByText('◆')).toHaveLength(1);
  });

  it('preserves selected + aria semantics', () => {
    render(<CardChip card={JADE_2} selected />);
    const button = screen.getByRole('button');
    expect(button).toHaveAttribute('aria-pressed', 'true');
    expect(button.className).toContain('selected');
    expect(button).toHaveAttribute('aria-label', 'JADE 2');
  });
});
