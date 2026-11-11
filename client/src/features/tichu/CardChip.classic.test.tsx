import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Card } from '@/types/tichu';

/**
 * D-241 — 테마 상수를 `'classic'` 으로 되돌렸을 때(되돌리기 한 줄)의 모습을 고정한다. 테마 층이 기본 모습을 지우지 않았는지
 * 보는 테스트라, 여기 단언은 테마를 얹기 전 `CardChip.test` 그대로다.
 */
vi.mock('./tichuTheme', () => ({
  TICHU_THEME: 'classic',
  lacquerTheme: false,
  tichuThemeClass: '',
  tichuCardClass: '',
  LACQUER_MIN_VISIBLE_STEP: 21,
  prefersReducedMotion: () => false,
}));

const { CardChip } = await import('./CardChip');
const { SeatCardStack } = await import('./SeatCardStack');

const JADE_2: Card = { suit: 'JADE', rank: 2, special: null };
const DRAGON: Card = { suit: null, rank: 0, special: 'DRAGON' };

describe('CardChip (기본 모습 — TICHU_THEME = classic)', () => {
  it('일반 카드는 숫자 + 무늬 글리프, 테마 클래스 없음', () => {
    const { container } = render(<CardChip card={JADE_2} />);
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('◆')).toBeInTheDocument();
    expect(container.querySelector('.tc-card')).toBeNull();
  });

  it('특수 카드는 이미지 + 이모지 폴백', () => {
    render(<CardChip card={DRAGON} />);
    const img = screen.getByRole('img') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/cards/dragon.svg');
    fireEvent.error(img);
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.getByText('🐉')).toBeInTheDocument();
  });

  it('좌석 카드 더미는 back.svg 이미지다', () => {
    const { container } = render(<SeatCardStack count={3} viewPos="n" />);
    const backs = container.querySelectorAll('img.seat-cardback');
    expect(backs).toHaveLength(3);
    expect(backs[0].getAttribute('src')).toBe('/cards/back.svg');
    expect(container.querySelector('.tc-back')).toBeNull();
  });
});
