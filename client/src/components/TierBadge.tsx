import type { Tier } from '@/api/users';

interface TierBadgeProps {
  tier: Tier;
  rating?: number;
}

/** D-164 — 색 계열(테마 변수 `--tier-*`, 라이트·다크 각자 값 — D-147). */
type Family = 'skull' | 'iron' | 'bronze' | 'silver' | 'gold' | 'royal';
/** 아이콘 모양 — 우리가 그린 단순 SVG(포트리스2 원본 그림은 쓰지 않는다 — 저작권). */
type Shape = 'skull' | 'bullet' | 'missile' | 'star' | 'stars' | 'medal' | 'badge' | 'crown';

const TIER_INFO: Record<Tier, { label: string; family: Family; shape: Shape }> = {
  SKULL: { label: '해골', family: 'skull', shape: 'skull' },
  BULLET_1: { label: '총알 I', family: 'iron', shape: 'bullet' },
  BULLET_2: { label: '총알 II', family: 'iron', shape: 'bullet' },
  BULLET_3: { label: '총알 III', family: 'iron', shape: 'bullet' },
  MISSILE_1: { label: '미사일 I', family: 'iron', shape: 'missile' },
  MISSILE_2: { label: '미사일 II', family: 'iron', shape: 'missile' },
  MISSILE_3: { label: '미사일 III', family: 'iron', shape: 'missile' },
  BRONZE_STAR: { label: '동별', family: 'bronze', shape: 'star' },
  SILVER_STAR: { label: '은별', family: 'silver', shape: 'star' },
  GOLD_STAR: { label: '금별', family: 'gold', shape: 'star' },
  DOUBLE_GOLD_STAR: { label: '쌍금별', family: 'gold', shape: 'stars' },
  BRONZE_MEDAL: { label: '동메달', family: 'bronze', shape: 'medal' },
  SILVER_MEDAL: { label: '은메달', family: 'silver', shape: 'medal' },
  GOLD_MEDAL: { label: '금메달', family: 'gold', shape: 'medal' },
  BRONZE_BADGE: { label: '동훈장', family: 'bronze', shape: 'badge' },
  SILVER_BADGE: { label: '은훈장', family: 'silver', shape: 'badge' },
  GOLD_BADGE: { label: '금훈장', family: 'gold', shape: 'badge' },
  BRONZE_CROWN: { label: '동왕관', family: 'bronze', shape: 'crown' },
  SILVER_CROWN: { label: '은왕관', family: 'silver', shape: 'crown' },
  GOLD_CROWN: { label: '금왕관', family: 'gold', shape: 'crown' },
  STAR_CROWN: { label: '별왕관', family: 'royal', shape: 'crown' },
};

const STAR = 'M8 1.5l1.9 4 4.3.5-3.2 3 .9 4.3L8 11.2 4.1 13.3 5 9 1.8 6l4.3-.5z';

/** 16×16 단순 아이콘. 색은 글자색(currentColor). */
function TierIcon({ shape }: { shape: Shape }) {
  const common = { width: 14, height: 14, viewBox: '0 0 16 16', 'aria-hidden': true, fill: 'currentColor' } as const;
  switch (shape) {
    case 'skull':
      return (
        <svg {...common}>
          <path d="M8 1.5C4.7 1.5 2.5 3.8 2.5 6.6c0 1.7.8 2.9 2 3.6V12h1.5v1.5h1.2V12h1.6v1.5H10V12h1.5v-1.8c1.2-.7 2-1.9 2-3.6 0-2.8-2.2-5.1-5.5-5.1zM5.8 8.6a1.3 1.3 0 110-2.6 1.3 1.3 0 010 2.6zm4.4 0a1.3 1.3 0 110-2.6 1.3 1.3 0 010 2.6z" />
        </svg>
      );
    case 'bullet':
      return (
        <svg {...common}>
          <path d="M8 1c1.8 1.4 2.6 3.4 2.6 5.5V12H5.4V6.5C5.4 4.4 6.2 2.4 8 1zM5 13h6v2H5z" />
        </svg>
      );
    case 'missile':
      return (
        <svg {...common}>
          <path d="M8 .8c1.3 1.1 2 2.8 2 4.6V10l2.2 2.4V14L10 13H6l-2.2 1v-1.6L6 10V5.4c0-1.8.7-3.5 2-4.6zM7 14h2v1.5H7z" />
        </svg>
      );
    case 'star':
      return (
        <svg {...common}>
          <path d={STAR} />
        </svg>
      );
    case 'stars':
      return (
        <svg {...common} width={20} viewBox="0 0 22 16">
          <path d={STAR} transform="translate(-1 0) scale(0.9)" />
          <path d={STAR} transform="translate(8 1.5) scale(0.9)" />
        </svg>
      );
    case 'medal':
      return (
        <svg {...common}>
          <path d="M4 1h3l1 3 1-3h3L9.6 6.2A4.5 4.5 0 118 15.5 4.5 4.5 0 016.4 6.2z" />
        </svg>
      );
    case 'badge':
      return (
        <svg {...common}>
          <path d="M8 1l2 2.6 3.3-.3-.3 3.3L15.6 8 13 9.4l.3 3.3-3.3-.3L8 15l-2-2.6-3.3.3.3-3.3L.4 8 3 6.6 2.7 3.3l3.3.3z" />
        </svg>
      );
    case 'crown':
      return (
        <svg {...common}>
          <path d="M1.5 4.5l3.5 3 3-5 3 5 3.5-3-1.3 8H2.8zM2.8 13.5h10.4V15H2.8z" />
        </svg>
      );
  }
}

/** D-147 — 테마 변수(`theme.css` 의 `--tier-*`, 라이트·다크 각자 값). D-164 — 계급마다가 아니라 색 계열마다 하나. */
function familyColor(family: Family): string {
  return `var(--tier-${family})`;
}

/** 화면 이름(예: "미사일 I") — 아이콘 없이 글로 쓸 때. */
export function tierLabel(tier: Tier): string {
  return TIER_INFO[tier]?.label ?? tier;
}

/**
 * Phase 8D — ELO 계급 표식. D-164 — 포트리스2식 21단계(해골 → 총알 → 미사일 → 별 → 메달 → 훈장 → 왕관). 아이콘은 우리가 그린
 * 단순 SVG 이고 동·은·금 색을 입힌다. rating 도 주면 옆에 숫자를 보인다. 좁은 랭킹 표에서 줄이 꺾이지 않게 한 줄로 둔다.
 */
export function TierBadge({ tier, rating }: TierBadgeProps) {
  const info = TIER_INFO[tier] ?? { label: tier, family: 'iron' as Family, shape: 'bullet' as Shape };
  const color = familyColor(info.family);
  return (
    <span
      title={rating !== undefined ? `${info.label} · ${rating}점` : info.label}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        padding: '2px 8px',
        borderRadius: 6,
        // 배경은 글자색을 아주 옅게 — 흰 배경에선 연한 색 띠, 다크에선 은은한 빛(예전 흰 5% 는 라이트에서 안 보였다).
        background: `color-mix(in srgb, ${color} 8%, transparent)`,
        borderWidth: 1,
        borderStyle: 'solid',
        borderColor: color,
        color,
        fontSize: 12,
        fontWeight: 600,
        whiteSpace: 'nowrap',
      }}
    >
      <TierIcon shape={info.shape} />
      <span>{info.label}</span>
      {rating !== undefined && <span style={{ opacity: 0.7, fontWeight: 400 }}>{rating}</span>}
    </span>
  );
}
