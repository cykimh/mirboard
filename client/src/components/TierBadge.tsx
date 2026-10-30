import type { Tier } from '@/api/users';
import { TierIcon } from './TierIcon';

interface TierBadgeProps {
  tier: Tier;
  /** `detailed` 일 때만 옆에 보인다. */
  rating?: number;
  /** D-166 — 이름·점수를 글로도 보인다(프로필의 게임별 전적처럼 자세히 보는 곳만). 기본은 아이콘만. */
  detailed?: boolean;
  /** 아이콘 크기(px). */
  size?: number;
}

const TIER_LABEL: Record<Tier, string> = {
  SKULL: '해골',
  BULLET_1: '총알 I',
  BULLET_2: '총알 II',
  BULLET_3: '총알 III',
  MISSILE_1: '미사일 I',
  MISSILE_2: '미사일 II',
  MISSILE_3: '미사일 III',
  BRONZE_STAR: '동별',
  SILVER_STAR: '은별',
  GOLD_STAR: '금별',
  DOUBLE_GOLD_STAR: '쌍금별',
  BRONZE_MEDAL: '동메달',
  SILVER_MEDAL: '은메달',
  GOLD_MEDAL: '금메달',
  BRONZE_BADGE: '동훈장',
  SILVER_BADGE: '은훈장',
  GOLD_BADGE: '금훈장',
  BRONZE_CROWN: '동왕관',
  SILVER_CROWN: '은왕관',
  GOLD_CROWN: '금왕관',
  STAR_CROWN: '별왕관',
};

/** 화면 이름(예: "미사일 I"). */
export function tierLabel(tier: Tier): string {
  return TIER_LABEL[tier] ?? tier;
}

/**
 * Phase 8D — ELO 계급 표식. D-164 — 포트리스2식 21단계. D-166 — 색 그림 아이콘만 보이고(랭킹·메인·대기실·게임판), 이름은
 * title·aria-label 로만 둔다. `detailed` 면 이름과 점수를 옆에 글로 붙인다.
 */
export function TierBadge({ tier, rating, detailed = false, size = 20 }: TierBadgeProps) {
  const label = tierLabel(tier);
  if (!detailed) {
    return <TierIcon tier={tier} label={label} size={size} />;
  }
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap', fontSize: 13, fontWeight: 600 }}>
      <TierIcon tier={tier} label={label} size={size} />
      <span>{label}</span>
      {rating !== undefined && <span style={{ opacity: 0.6, fontWeight: 400 }}>{rating}</span>}
    </span>
  );
}
