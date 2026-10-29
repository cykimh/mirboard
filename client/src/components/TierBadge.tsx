import type { Tier } from '@/api/users';

interface TierBadgeProps {
  tier: Tier;
  rating?: number;
}

const TIER_LABEL: Record<Tier, string> = {
  BRONZE: '브론즈',
  SILVER: '실버',
  GOLD: '골드',
  PLATINUM: '플래티넘',
  DIAMOND: '다이아',
  MASTER: '마스터',
};

/** D-147 — 테마 변수(`theme.css` 의 `--tier-*`, 라이트·다크 각자 값). */
function tierColor(tier: Tier): string {
  return `var(--tier-${tier.toLowerCase()})`;
}

/**
 * Phase 8D — ELO 티어 시각 표식. 색은 테마별 변수(D-147 — 라이트는 진하게, 다크는 밝게). rating 도 같이 노출하면 옆에 숫자 표시.
 */
export function TierBadge({ tier, rating }: TierBadgeProps) {
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        padding: '2px 8px',
        borderRadius: 6,
        // 배경은 글자색을 아주 옅게 — 흰 배경에선 연한 색 띠, 다크에선 은은한 빛(예전 흰 5% 는 라이트에서 안 보였다).
        background: `color-mix(in srgb, ${tierColor(tier)} 8%, transparent)`,
        borderWidth: 1,
        borderStyle: 'solid',
        borderColor: tierColor(tier),
        color: tierColor(tier),
        fontSize: 12,
        fontWeight: 600,
      }}
    >
      {TIER_LABEL[tier]}
      {rating !== undefined && (
        <span style={{ opacity: 0.7, fontWeight: 400 }}>{rating}</span>
      )}
    </span>
  );
}
