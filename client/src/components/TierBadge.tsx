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

const TIER_COLOR: Record<Tier, string> = {
  BRONZE: '#a07045',
  SILVER: '#9aa3ad',
  GOLD: '#d9a93b',
  PLATINUM: '#5fd4c0',
  DIAMOND: '#5fb2ff',
  MASTER: '#cf6bff',
};

/**
 * Phase 8D — ELO 티어 시각 표식. 색상은 TIER_COLOR (다크 톤에서도 잘 보이게 채도
 * 낮춤). rating 도 같이 노출하면 옆에 숫자 표시.
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
        background: 'rgba(255,255,255,0.05)',
        border: `1px solid ${TIER_COLOR[tier]}`,
        color: TIER_COLOR[tier],
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
