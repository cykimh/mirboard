import { useId } from 'react';
import type { Tier } from '@/api/users';

/** 금속 색 — 그라데이션 세 점(밝음 → 본색 → 그림자)과 테두리. */
type Metal = 'bronze' | 'silver' | 'gold';
const METAL: Record<Metal, { stops: [string, string, string]; edge: string }> = {
  bronze: { stops: ['#f6c49a', '#c47a3c', '#7a4320'], edge: '#5a2f14' },
  silver: { stops: ['#ffffff', '#c3cad3', '#7d8794'], edge: '#55606d' },
  gold: { stops: ['#fff6bf', '#f2c230', '#b07d08'], edge: '#7a5600' },
};

type Shape =
  | { kind: 'skull' }
  | { kind: 'bullet'; count: number }
  | { kind: 'missile'; count: number }
  | { kind: 'star'; metal: Metal; count: number }
  | { kind: 'medal'; metal: Metal }
  | { kind: 'badge'; metal: Metal }
  | { kind: 'crown'; metal: Metal; royal?: boolean };

/** D-166 — 계급마다 그림. 총알·미사일 I~III 은 개수로, 금속 계급은 색으로 구분한다(포트리스2 이름·순서, 그림은 우리가 그렸다). */
export const TIER_SHAPE: Record<Tier, Shape> = {
  SKULL: { kind: 'skull' },
  BULLET_1: { kind: 'bullet', count: 1 },
  BULLET_2: { kind: 'bullet', count: 2 },
  BULLET_3: { kind: 'bullet', count: 3 },
  MISSILE_1: { kind: 'missile', count: 1 },
  MISSILE_2: { kind: 'missile', count: 2 },
  MISSILE_3: { kind: 'missile', count: 3 },
  BRONZE_STAR: { kind: 'star', metal: 'bronze', count: 1 },
  SILVER_STAR: { kind: 'star', metal: 'silver', count: 1 },
  GOLD_STAR: { kind: 'star', metal: 'gold', count: 1 },
  DOUBLE_GOLD_STAR: { kind: 'star', metal: 'gold', count: 2 },
  BRONZE_MEDAL: { kind: 'medal', metal: 'bronze' },
  SILVER_MEDAL: { kind: 'medal', metal: 'silver' },
  GOLD_MEDAL: { kind: 'medal', metal: 'gold' },
  BRONZE_BADGE: { kind: 'badge', metal: 'bronze' },
  SILVER_BADGE: { kind: 'badge', metal: 'silver' },
  GOLD_BADGE: { kind: 'badge', metal: 'gold' },
  BRONZE_CROWN: { kind: 'crown', metal: 'bronze' },
  SILVER_CROWN: { kind: 'crown', metal: 'silver' },
  GOLD_CROWN: { kind: 'crown', metal: 'gold' },
  STAR_CROWN: { kind: 'crown', metal: 'gold', royal: true },
};

function Gradient({ id, metal }: { id: string; metal: Metal }) {
  const [a, b, c] = METAL[metal].stops;
  return (
    <linearGradient id={id} x1="0" y1="0" x2="0.4" y2="1">
      <stop offset="0" stopColor={a} />
      <stop offset="0.55" stopColor={b} />
      <stop offset="1" stopColor={c} />
    </linearGradient>
  );
}

const STAR_PATH = 'M12 2.2l2.9 6 6.6.8-4.9 4.5 1.3 6.5L12 16.8 6.1 20l1.3-6.5L2.5 9l6.6-.8z';

/** 한 칸(24×24) 그림. 같은 그림 여러 개는 겹쳐 늘어놓는다. */
function Glyph({ shape, gid, size }: { shape: Shape; gid: string; size: number }) {
  const svg = { width: size, height: size, viewBox: '0 0 24 24', 'aria-hidden': true } as const;
  switch (shape.kind) {
    case 'skull':
      return (
        <svg {...svg}>
          <path
            d="M12 2.5c-4.8 0-8 3.3-8 7.6 0 2.5 1.2 4.3 3 5.4V19h2.2v2h2v-2h1.6v2h2v-2H17v-3.5c1.8-1.1 3-2.9 3-5.4 0-4.3-3.2-7.6-8-7.6z"
            fill="#f4efe3"
            stroke="#3b3631"
            strokeWidth="1.2"
          />
          <circle cx="8.8" cy="10.8" r="2.2" fill="#2b2622" />
          <circle cx="15.2" cy="10.8" r="2.2" fill="#2b2622" />
          <path d="M12 13.2l-1.2 2.2h2.4z" fill="#2b2622" />
        </svg>
      );
    case 'bullet':
      return (
        <svg {...svg}>
          <defs>
            <linearGradient id={gid} x1="0" y1="0" x2="1" y2="0">
              <stop offset="0" stopColor="#fbe39a" />
              <stop offset="0.6" stopColor="#d9a43a" />
              <stop offset="1" stopColor="#9a6a12" />
            </linearGradient>
          </defs>
          <path d="M12 1.5c2.6 2 3.6 4.8 3.6 7.6V10H8.4v-.9c0-2.8 1-5.6 3.6-7.6z" fill="#c8794a" stroke="#6b3b1d" strokeWidth="0.8" />
          <rect x="8.4" y="10" width="7.2" height="10" fill={`url(#${gid})`} stroke="#6b4a0a" strokeWidth="0.8" />
          <rect x="7.6" y="20" width="8.8" height="2.5" rx="0.6" fill="#b0812a" stroke="#6b4a0a" strokeWidth="0.8" />
        </svg>
      );
    case 'missile':
      return (
        <svg {...svg}>
          <path d="M12 1c2.3 1.6 3.4 4.3 3.4 7v9H8.6V8c0-2.7 1.1-5.4 3.4-7z" fill="#f5f6f8" stroke="#4a4f57" strokeWidth="0.8" />
          <path d="M12 1c1.6 1.1 2.6 2.8 3.1 4.6H8.9C9.4 3.8 10.4 2.1 12 1z" fill="#e53935" />
          <path d="M8.6 12.5L5.5 16.5V18.5l3.1-1.5zM15.4 12.5l3.1 4v2l-3.1-1.5z" fill="#e53935" stroke="#7f1d1d" strokeWidth="0.6" />
          <path d="M10.2 17h3.6l-.8 3.2-1 2.3-1-2.3z" fill="#ffb02e" />
        </svg>
      );
    case 'star':
      return (
        <svg {...svg}>
          <defs>
            <Gradient id={gid} metal={shape.metal} />
          </defs>
          <path d={STAR_PATH} fill={`url(#${gid})`} stroke={METAL[shape.metal].edge} strokeWidth="1" strokeLinejoin="round" />
        </svg>
      );
    case 'medal':
      return (
        <svg {...svg}>
          <defs>
            <Gradient id={gid} metal={shape.metal} />
          </defs>
          <path d="M6 1.5h4.5L12 7 13.5 1.5H18L14.6 9.5H9.4z" fill="#3b6fd8" />
          <path d="M10.5 1.5L12 7l1.5-5.5h-1z" fill="#d64545" />
          <circle cx="12" cy="15" r="7" fill={`url(#${gid})`} stroke={METAL[shape.metal].edge} strokeWidth="1" />
          <circle cx="12" cy="15" r="4.3" fill="none" stroke={METAL[shape.metal].edge} strokeWidth="0.8" opacity="0.6" />
        </svg>
      );
    case 'badge':
      return (
        <svg {...svg}>
          <defs>
            <Gradient id={gid} metal={shape.metal} />
          </defs>
          <path
            d="M12 1.5l2.4 4.2 4.7-1.3-1.3 4.7 4.2 2.4-4.2 2.4 1.3 4.7-4.7-1.3L12 21.5l-2.4-4.2-4.7 1.3 1.3-4.7-4.2-2.4 4.2-2.4-1.3-4.7 4.7 1.3z"
            fill={`url(#${gid})`}
            stroke={METAL[shape.metal].edge}
            strokeWidth="0.9"
            strokeLinejoin="round"
          />
          <circle cx="12" cy="11.5" r="3.4" fill="#c62828" stroke={METAL[shape.metal].edge} strokeWidth="0.8" />
          <circle cx="12" cy="11.5" r="1.4" fill="#ffd54f" />
        </svg>
      );
    case 'crown':
      return (
        <svg {...svg}>
          <defs>
            <Gradient id={gid} metal={shape.metal} />
          </defs>
          {shape.royal && <path d={STAR_PATH} transform="translate(7.6 -0.6) scale(0.37)" fill="#fff59d" stroke="#b07d08" strokeWidth="2" />}
          <path
            d="M3 8.5l4.5 4 4.5-7 4.5 7 4.5-4-1.8 10.5H4.8z"
            fill={`url(#${gid})`}
            stroke={METAL[shape.metal].edge}
            strokeWidth="1"
            strokeLinejoin="round"
          />
          <rect x="4.8" y="19" width="14.4" height="2.6" rx="0.8" fill={shape.royal ? '#7b3fbf' : `url(#${gid})`} stroke={METAL[shape.metal].edge} strokeWidth="0.8" />
          <circle cx="8" cy="15.6" r="1.3" fill={shape.royal ? '#ab47bc' : '#e53935'} />
          <circle cx="12" cy="15" r="1.5" fill={shape.royal ? '#e53935' : '#1e88e5'} />
          <circle cx="16" cy="15.6" r="1.3" fill={shape.royal ? '#43a047' : '#e53935'} />
        </svg>
      );
  }
}

/** 같은 그림 개수(총알·미사일 I~III, 쌍금별). */
function countOf(shape: Shape): number {
  return 'count' in shape ? shape.count : 1;
}

/**
 * D-166 — 계급 색 그림 아이콘. 글자는 없고 이름은 `label`(title·aria-label)로만 — 길게 누르거나 마우스를 올리면 보인다.
 */
export function TierIcon({ tier, label, size = 20 }: { tier: Tier; label: string; size?: number }) {
  const gid = `tier-${useId().replace(/:/g, '')}`;
  const shape = TIER_SHAPE[tier] ?? TIER_SHAPE.MISSILE_1;
  const n = countOf(shape);
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      data-tier={tier}
      style={{ display: 'inline-flex', alignItems: 'center', lineHeight: 0, flexShrink: 0 }}
    >
      {Array.from({ length: n }, (_, i) => (
        <span key={i} style={{ marginLeft: i === 0 ? 0 : -size * 0.35, display: 'inline-flex' }}>
          <Glyph shape={shape} gid={`${gid}-${i}`} size={size} />
        </span>
      ))}
    </span>
  );
}
