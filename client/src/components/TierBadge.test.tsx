import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TierBadge, tierLabel } from './TierBadge';
import { TIER_SHAPE } from './TierIcon';
import type { Tier } from '@/api/users';

const ALL_TIERS = Object.keys(TIER_SHAPE) as Tier[];

/** D-166 — 계급은 색 그림 아이콘만. 이름은 title·aria-label 로만(길게 누르기·보조기기). 프로필만 이름·점수를 글로. */
describe('TierBadge — 색 그림 아이콘 (D-166)', () => {
  it('기본은 아이콘만 — 글자·점수가 없고 이름은 보조기기·툴팁으로', () => {
    const { container } = render(<TierBadge tier="MISSILE_1" rating={1000} />);

    const icon = screen.getByRole('img', { name: '미사일 I' });
    expect(icon).toHaveAttribute('title', '미사일 I');
    expect(container.textContent).toBe('');
    expect(container.querySelectorAll('svg')).toHaveLength(1);
  });

  it('총알·미사일 I~III 은 그림 개수로, 쌍금별은 별 둘', () => {
    const count = (tier: Tier) => {
      const { container, unmount } = render(<TierBadge tier={tier} />);
      const n = container.querySelectorAll('svg').length;
      unmount();
      return n;
    };
    expect([count('BULLET_1'), count('BULLET_2'), count('BULLET_3')]).toEqual([1, 2, 3]);
    expect([count('MISSILE_1'), count('MISSILE_3')]).toEqual([1, 3]);
    expect(count('DOUBLE_GOLD_STAR')).toBe(2);
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

  it('21단계 모두 이름과 그림이 있다', () => {
    expect(ALL_TIERS).toHaveLength(21);
    for (const t of ALL_TIERS) {
      expect(tierLabel(t), t).not.toBe(t);
      const { container, unmount } = render(<TierBadge tier={t} />);
      expect(container.querySelector('svg'), t).not.toBeNull();
      unmount();
    }
  });
});
