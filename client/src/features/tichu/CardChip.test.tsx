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

  it('renders the card image with an always-visible corner index (rank + suit)', () => {
    render(<CardChip card={JADE_2} />);
    const img = screen.getByRole('img') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/cards/jade-2.svg');
    // #1 — 겹쳐도 읽히도록 좌상단 코너 인덱스를 항상 노출(랭크 + 슈트 글리프 각 1개).
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getAllByText('◆')).toHaveLength(1);
  });

  it('falls back to glyph + rank when the image fails to load', () => {
    render(<CardChip card={JADE_2} />);
    fireEvent.error(screen.getByRole('img'));
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('◆')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('uses the special card image and emoji fallback (no suit corner index)', () => {
    render(<CardChip card={DRAGON} />);
    const img = screen.getByRole('img') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/cards/dragon.svg');
    // 특수카드(개/용/봉황/마작)는 일러스트가 고유 → 코너 인덱스 없음.
    expect(screen.queryByText('◆')).toBeNull();
    fireEvent.error(img);
    expect(screen.getByText('🐉')).toBeInTheDocument();
  });

  it('does not duplicate the suit glyph in colorblind mode (single corner index)', () => {
    useColorblindStore.setState({ enabled: true });
    render(<CardChip card={JADE_2} />);
    expect(screen.getByRole('img')).toBeInTheDocument();
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
