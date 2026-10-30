import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TierBadge, tierLabel } from './TierBadge';
import { TIER_SHAPE } from './TierIcon';
import type { Tier } from '@/api/users';

const ALL_TIERS = Object.keys(TIER_SHAPE) as Tier[];

/** D-166 — 계급은 색 그림 아이콘만. 이름은 title·aria-label 로만(길게 누르기·보조기기). 프로필만 이름·점수를 글로. */
describe('TierBadge — 색 그림 아이콘 (D-166)', () => {
  it('기본은 아이콘만 — 글자·점수가 없고 이름은 보조기기·툴팁으로', () => {
    const { container } = render(<TierBadge tier="MISSILE" rating={1000} />);

    const icon = screen.getByRole('img', { name: '미사일' });
    expect(icon).toHaveAttribute('title', '미사일');
    expect(container.textContent).toBe('');
    expect(container.querySelectorAll('svg')).toHaveLength(1);
  });

  // D-167 — 그림이 여러 개인 계급은 없다. 모든 계급이 그림 하나다.
  it('모든 계급이 그림 하나다', () => {
    for (const t of ALL_TIERS) {
      const { container, unmount } = render(<TierBadge tier={t} />);
      expect(container.querySelectorAll('svg'), t).toHaveLength(1);
      unmount();
    }
  });

  it('금속 계급은 그라데이션으로 색을 칠한다 — 아이콘마다 그라데이션 id 가 겹치지 않는다', () => {
    const { container } = render(
      <>
        <TierBadge tier="GOLD_MEDAL" />
        <TierBadge tier="BRONZE_MEDAL" />
      </>,
    );
    const ids = Array.from(container.querySelectorAll('linearGradient')).map((g) => g.id);
    expect(ids).toHaveLength(2);
    expect(new Set(ids).size).toBe(2);
  });

  it('프로필(detailed)은 이름과 점수를 글로도 보인다', () => {
    render(<TierBadge tier="STAR_CROWN" rating={1612} detailed />);

    expect(screen.getByText('별왕관')).toBeInTheDocument();
    expect(screen.getByText('1612')).toBeInTheDocument();
  });

  it('16단계 모두 이름과 그림이 있다', () => {
    expect(ALL_TIERS).toHaveLength(16);
    for (const t of ALL_TIERS) {
      expect(tierLabel(t), t).not.toBe(t);
      const { container, unmount } = render(<TierBadge tier={t} />);
      expect(container.querySelector('svg'), t).not.toBeNull();
      unmount();
    }
  });
});
