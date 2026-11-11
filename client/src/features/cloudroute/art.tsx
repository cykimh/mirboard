import type { ReactNode } from 'react';
import type { CloudRouteLetter } from '@/types/cloudroute';
import type { Island, TileKind } from './board';

/**
 * 구름항로 그림 (D-218) — 전부 코드로 그린 SVG 다(외부 이미지·글꼴 없음). 모양만 여기서 정하고 칠·선 색은 CSS 가 정한다:
 * 칸 그림은 `cr-art-f`(본체)·`-a`(강조)·`-d`(밝은 무늬)·`-l`(선), 건물은 `cr-bld-f`(주인 색 본체)·`-a`(지붕·등)·`-w`(창)·`-l`(선).
 * 모든 그림은 장식이라 `aria-hidden` 이다 — 뜻은 옆의 글자·aria-label 이 전한다.
 */

const svg = (viewBox: string, className: string, children: ReactNode) => (
  <svg viewBox={viewBox} className={className} aria-hidden="true" focusable="false">
    {children}
  </svg>
);

const MOTIF: Record<Island | Exclude<TileKind, 'LAND'>, ReactNode> = {
  MOSS: (
    <>
      <path className="cr-art-f" d="M2 19Q5 8 12 8T22 19Z" />
      <path className="cr-art-l" d="M7 13v-3M12 12V7.5M17 13v-3" />
      <circle className="cr-art-a" cx="12" cy="5.5" r="1.6" />
    </>
  ),
  SHELL: (
    <>
      <path className="cr-art-f" d="M12 20L3 11A9 9 0 0 1 21 11Z" />
      <path className="cr-art-l" d="M12 20V3.5M12 20L6.5 5.5M12 20L17.5 5.5" />
    </>
  ),
  MUSHROOM: (
    <>
      <path className="cr-art-a" d="M10 12h4l1 8.5H9Z" />
      <path className="cr-art-f" d="M3 13A9 8.5 0 0 1 21 13Z" />
      <circle className="cr-art-d" cx="9" cy="9" r="1.4" />
      <circle className="cr-art-d" cx="14.5" cy="8" r="1.7" />
    </>
  ),
  WINDMILL: (
    <>
      <path className="cr-art-a" d="M9.5 21.5L11 11h2l1.5 10.5Z" />
      <g className="cr-art-spin">
        <path className="cr-art-f" d="M12 10L10 2h4ZM12 10L20 8v4ZM12 10L14 18h-4ZM12 10L4 12V8Z" />
      </g>
      <circle className="cr-art-d" cx="12" cy="10" r="1.3" />
    </>
  ),
  GLASS: (
    <>
      <path className="cr-art-f" d="M12 2L20 9L12 22L4 9Z" />
      <path className="cr-art-l" d="M4 9h16M12 2L9 9l3 13l3-13Z" />
    </>
  ),
  STAR_APPLE: (
    <>
      <circle className="cr-art-f" cx="12" cy="13.5" r="7.5" />
      <path className="cr-art-d" d="M12 9l1.2 3.1 3.3.2-2.6 2 .9 3.2L12 15.7 9.2 17.5l.9-3.2-2.6-2 3.3-.2Z" />
      <path className="cr-art-a" d="M12 6q1-4 5-4q0 4-5 4Z" />
    </>
  ),
  LAVA: (
    <>
      <path className="cr-art-f" d="M2 21L8 8h8l6 13Z" />
      <path className="cr-art-a" d="M8 8h8l-1.5 4-2-2-2 3-1.5-3Z" />
      <circle className="cr-art-a" cx="12" cy="4" r="1.5" />
    </>
  ),
  CROWN: (
    <>
      <path className="cr-art-f" d="M4.5 16L3 6l5 4 4-7 4 7 5-4-1.5 10Z" />
      <path className="cr-art-d" d="M2 20a3 3 0 0 1 4-3 4 4 0 0 1 7-1 3.5 3.5 0 0 1 6 1 3 3 0 0 1 3 3Z" />
    </>
  ),
  START: (
    <>
      <path className="cr-art-f" d="M3 14h18l-3 6.5H6Z" />
      <path className="cr-art-l" d="M12 14V3" />
      <path className="cr-art-a" d="M12 3l7 4-7 4Z" />
    </>
  ),
  LETTER: (
    <>
      <rect className="cr-art-f" x="3" y="6" width="18" height="13" rx="2" />
      <path className="cr-art-l" d="M3.5 8l8.5 6 8.5-6" />
    </>
  ),
  WHIRLPOOL: (
    <g className="cr-art-swirl">
      <path className="cr-art-l" d="M10 12a2 2 0 1 0 4 0a4 4 0 1 0-8 0a6 6 0 1 0 12 0a8 8 0 1 0-16 0" />
    </g>
  ),
  CUSTOMS: (
    <>
      <path className="cr-art-f" d="M4 21V9l8-5 8 5v12h-5v-7H9v7Z" />
      <circle className="cr-art-a" cx="12" cy="9.5" r="2" />
    </>
  ),
  FESTIVAL: (
    <>
      <path className="cr-art-l" d="M12 2v3M12 19v3" />
      <rect className="cr-art-f" x="6" y="5" width="12" height="14" rx="5" />
      <path className="cr-art-l" d="M10 5.5v13M14 5.5v13" />
    </>
  ),
  STATION: (
    <>
      <path className="cr-art-l" d="M5 22V3" />
      <path className="cr-art-f" d="M5 4l14 2.5v5L5 14Z" />
      <path className="cr-art-l" d="M10 5v8.2M15 5.8v6.4" />
    </>
  ),
};

/** 칸 그림 — 땅은 섬 모티프, 특수 칸은 종류 모티프. */
export function TileArt({ kind, island }: { kind: TileKind; island: Island | null }) {
  const key = kind === 'LAND' ? (island ?? 'MOSS') : kind;
  return svg('0 0 24 24', 'cr-art', MOTIF[key]);
}

/** 특수 칸 아이콘(패널·칸 시트의 글줄 안). */
export function KindIcon({ kind }: { kind: Exclude<TileKind, 'LAND'> }) {
  return svg('0 0 24 24', 'cr-art cr-icon', MOTIF[kind]);
}

const BUILDING: ReactNode[] = [
  <>
    <path className="cr-bld-l" d="M7 22V3" />
    <path className="cr-bld-f" d="M7 4l12 4-12 4Z" />
  </>,
  <>
    <path className="cr-bld-f" d="M3 21L12 6l9 15Z" />
    <path className="cr-bld-w" d="M10 21l2-7 2 7Z" />
    <path className="cr-bld-l" d="M12 6V2l4 1.5-4 1.5" />
  </>,
  <>
    <path className="cr-bld-f" d="M4 22V11h16v11Z" />
    <path className="cr-bld-a" d="M2 11l3-6h14l3 6Z" />
    <path className="cr-bld-w" d="M10 22v-6h4v6Z" />
  </>,
  <>
    <path className="cr-bld-f" d="M8 23l1.5-11h5L16 23Z" />
    <rect className="cr-bld-a" x="6.5" y="3" width="11" height="9" rx="3.5" />
    <path className="cr-bld-w" d="M10 5.5h4v4h-4Z" />
    <path className="cr-bld-l" d="M12 3V1" />
  </>,
];

/** 건물 단계 0 깃발 · 1 천막 · 2 상점 · 3 풍등탑. 본체 색은 조상 요소의 `--cr-c`(주인 좌석 색). */
export function Building({ level }: { level: number }) {
  return svg('0 0 24 24', 'cr-bld', BUILDING[Math.max(0, Math.min(3, level))]);
}

const BALLOON_BODY: ReactNode[] = [
  // 0 — 동그란 풍선(무늬 없음)
  <>
    <circle className="cr-bal-f" cx="16" cy="11" r="10" />
    <path className="cr-bal-shine" d="M9 8.2a7.6 7.6 0 0 1 4.4-4.4" />
  </>,
  // 1 — 꼭지가 뾰족한 물방울 + 세로 줄무늬
  <>
    <path className="cr-bal-f" d="M16 .6C21.5 4.2 26 7.4 26 12a10 10 0 0 1-20 0C6 7.4 10.500 4.200 16 .6Z" />
    <path className="cr-bal-mark" d="M16 1.6c-2.600 3.400-4 6.800-4 10.400s1.400 6.600 4 9c2.600-2.400 4-5.400 4-9s-1.400-7-4-10.400Z" />
  </>,
  // 2 — 옆으로 넓은 비행선 + 가로 띠
  <>
    <ellipse className="cr-bal-f" cx="16" cy="12" rx="13" ry="8.6" />
    <path className="cr-bal-mark" d="M3.300 10.200h25.400a13 8.600 0 0 1 0 3.600H3.300a13 8.600 0 0 1 0-3.600Z" />
  </>,
  // 3 — 네모난 등 모양 + 물방울 무늬
  <>
    <rect className="cr-bal-f" x="5.500" y="1.500" width="21" height="19.500" rx="6.500" />
    <circle className="cr-bal-mark" cx="10" cy="6" r="1.700" />
    <circle className="cr-bal-mark" cx="22" cy="6" r="1.700" />
    <circle className="cr-bal-mark" cx="16" cy="4.300" r="1.400" />
    <circle className="cr-bal-mark" cx="9.500" cy="17" r="1.400" />
    <circle className="cr-bal-mark" cx="22.500" cy="17" r="1.400" />
  </>,
];

const BALLOON_MOUTH = ['M13.4 14.2q2.6 2.4 5.2 0', 'M13.6 14.8h4.8', 'M13.4 14.2q2.6 3.4 5.2 0Z', 'M13.4 15.4q2.6-2 5.2 0'];

/**
 * 열기구 말 — 좌석은 색만이 아니라 **풍선 모양 4종 + 무늬 + 바구니의 좌석 번호**로 구분한다(색약). 색은 조상의 `--cr-c`.
 */
export function Balloon({ seat }: { seat: number }) {
  const k = ((seat % 4) + 4) % 4;
  return svg(
    '0 0 32 33',
    'cr-balloon',
    <>
      <path className="cr-bal-rope" d="M9.5 19.5l2.2 3.200M22.500 19.500l-2.200 3.200" />
      {BALLOON_BODY[k]}
      <circle className="cr-bal-eye" cx="12.6" cy="11" r="1.3" />
      <circle className="cr-bal-eye" cx="19.4" cy="11" r="1.3" />
      <path className={`cr-bal-mouth${k === 2 ? ' cr-bal-mouth-open' : ''}`} d={BALLOON_MOUTH[k]} />
      <rect className="cr-bal-basket" x="10" y="22.3" width="12" height="9.2" rx="2.4" />
      <text className="cr-bal-no" x="16" y="29.7" textAnchor="middle">
        {seat + 1}
      </text>
    </>,
  );
}

const PIPS: Record<number, number[]> = {
  1: [4],
  2: [0, 8],
  3: [0, 4, 8],
  4: [0, 2, 6, 8],
  5: [0, 2, 4, 6, 8],
  6: [0, 2, 3, 5, 6, 8],
};

/** 주사위 한 면 — 눈은 숫자 글자가 아니라 점 그림. */
export function DieFace({ value }: { value: number }) {
  const on = PIPS[value] ?? [];
  return svg(
    '0 0 36 36',
    'cr-die-face',
    <>
      <rect className="cr-die-body" x="1.5" y="1.5" width="33" height="33" rx="8.5" />
      {on.map((i) => (
        <circle key={i} className="cr-die-pip" cx={9 + (i % 3) * 9} cy={9 + Math.floor(i / 3) * 9} r="3.1" />
      ))}
    </>,
  );
}

/** 편지 뒷면(더미·뒤집기 전). */
export function CardBack() {
  return (
    <svg viewBox="0 0 50 70" preserveAspectRatio="none" className="cr-cardback" aria-hidden="true" focusable="false">
      <rect className="cr-cardback-f" width="50" height="70" rx="5" />
      <rect className="cr-cardback-l" x="3.5" y="3.5" width="43" height="63" rx="3.5" />
      <path className="cr-cardback-c" d="M14 42a6 6 0 0 1 2.6-11.4 8.4 8.4 0 0 1 16-1A6.6 6.6 0 0 1 36 42Z" />
      <path className="cr-cardback-w" d="M19.5 36.5q2.8-3 5.6 0t5.6 0" />
    </svg>
  );
}

const LETTER_ART: Record<CloudRouteLetter, ReactNode> = {
  TAILWIND: (
    <>
      <path className="cr-la-l" d="M5 16h22a5 5 0 1 0-5-5M5 25h30a5 5 0 1 1-5 5M5 34h14" />
      <path className="cr-la-a" d="M36 14l8 5-8 5Z" />
    </>
  ),
  SAIL_REPAIR: (
    <>
      <path className="cr-la-f" d="M22 5v30H8Z" />
      <path className="cr-la-f" d="M26 11v24h13Z" />
      <path className="cr-la-l" d="M6 39h36l-4 5H10Z" />
      <rect className="cr-la-a" x="12" y="25" width="7" height="6" rx="1" />
      <path className="cr-la-l" d="M13.500 27h4M13.500 29.500h4" />
    </>
  ),
  STAR_CHEST: (
    <>
      <path className="cr-la-s" d="M36 3l1.8 4.3 4.6.4-3.5 3 1 4.5L36 12.8l-3.900 2.400 1-4.500-3.500-3 4.600-.4Z" />
      <path className="cr-la-a" d="M7 24a17 12 0 0 1 34 0Z" />
      <path className="cr-la-f" d="M7 24h34v15a3 3 0 0 1-3 3H10a3 3 0 0 1-3-3Z" />
      <rect className="cr-la-s" x="20.5" y="20.5" width="7" height="9" rx="2" />
    </>
  ),
  HEADWIND: (
    <>
      <path className="cr-la-l" d="M43 16H21a5 5 0 1 1 5-5M43 25H13a5 5 0 1 0 5 5M43 34H29" />
      <path className="cr-la-a" d="M12 14l-8 5 8 5Z" />
    </>
  ),
  WHIRLPOOL_ALERT: (
    <>
      <path className="cr-la-l" d="M21 26a3 3 0 1 0 6 0a6 6 0 1 0-12 0a9 9 0 1 0 18 0a12 12 0 1 0-24 0" />
      <path className="cr-la-a" d="M37 4h5l-1 11h-3Z" />
      <circle className="cr-la-a" cx="39.5" cy="19" r="2.200" />
    </>
  ),
  BUOY: (
    <>
      <circle className="cr-la-f" cx="24" cy="24" r="17" />
      <path className="cr-la-a" d="M24 7a17 17 0 0 1 12 5l-7 7a7 7 0 0 0-5-2ZM41 24a17 17 0 0 1-5 12l-7-7a7 7 0 0 0 2-5ZM24 41a17 17 0 0 1-12-5l7-7a7 7 0 0 0 5 2ZM7 24a17 17 0 0 1 5-12l7 7a7 7 0 0 0-2 5Z" />
      <circle className="cr-la-hole" cx="24" cy="24" r="7" />
    </>
  ),
  INSPECTION: (
    <>
      <path className="cr-la-f" d="M6 40V20l11-9 11 9v20Z" />
      <path className="cr-la-a" d="M13 40v-9h8v9Z" />
      <circle className="cr-la-glass" cx="32" cy="19" r="9" />
      <path className="cr-la-l" d="M38.500 25.500l6 6" />
    </>
  ),
  ISLAND_FEAST: (
    <>
      <path className="cr-la-l" d="M4 8q20 12 40 0" />
      <path className="cr-la-a" d="M9 10.500l5 2-3.500 6ZM21 14h6l-3 7ZM34 12.500l5-2-1.500 8Z" />
      <path className="cr-la-f" d="M10 30h28v12H10Z" />
      <path className="cr-la-s" d="M10 30q4.700 5 9.300 0t9.400 0t9.300 0v-3H10Z" />
      <path className="cr-la-l" d="M24 27v-5" />
    </>
  ),
  WIND_SHARE: (
    <>
      <circle className="cr-la-s" cx="13" cy="17" r="9" />
      <circle className="cr-la-s" cx="35" cy="31" r="9" />
      <path className="cr-la-l" d="M24 11q9 0 11 8M24 37q-9 0-11-8" />
      <path className="cr-la-a" d="M31.500 17l3.700 4 3.300-4.500ZM16.500 31l-3.700-4-3.300 4.500Z" />
    </>
  ),
  GUST: (
    <>
      <path className="cr-la-f" d="M8 30a7 7 0 0 1 2.500-13.500 10 10 0 0 1 19-1.500A8 8 0 0 1 30 30Z" />
      <path className="cr-la-l" d="M20 36h16M24 42h10" />
      <path className="cr-la-a" d="M36 31l8 5-8 5Z" />
    </>
  ),
  LANTERN_GIFT: (
    <>
      <path className="cr-la-l" d="M24 4v6M24 38v6" />
      <rect className="cr-la-a" x="12" y="10" width="24" height="28" rx="10" />
      <path className="cr-la-l" d="M20 11v26M28 11v26" />
      <path className="cr-la-s" d="M24 20l-7-4v8ZM24 20l7-4v8Z" />
      <circle className="cr-la-s" cx="24" cy="20" r="2.200" />
    </>
  ),
  FREE_FLIGHT: (
    <>
      <path className="cr-la-f" d="M5 16h38v6a3 3 0 0 0 0 6v6H5v-6a3 3 0 0 0 0-6Z" />
      <path className="cr-la-l" d="M33 17v16" />
      <path className="cr-la-a" d="M11 27q7-9 17-6-3 2-6 2 2 1 4 1-3 3-8 3 2 1 3 1-5 2-10-1Z" />
    </>
  ),
};

/** 편지 앞면 그림 — 12장 모두 다르다. */
export function LetterArt({ letter }: { letter: CloudRouteLetter }) {
  return svg('0 0 48 48', 'cr-letter-art', LETTER_ART[letter]);
}

/** 작은 글줄 아이콘들 — 이모지를 대신한다. */
export function Icon({ name }: { name: 'coin' | 'envelope' | 'buoy' | 'swirl' | 'lantern' | 'gift' | 'die' | 'sound' | 'mute' | 'star' | 'clock' | 'bot' }) {
  switch (name) {
    case 'coin':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <circle className="cr-ico-coin" cx="9" cy="9" r="8" />
          <path className="cr-ico-coin-star" d="M9 4.5l1.3 2.9 3.1.3-2.3 2.1.7 3.1L9 11.3l-2.8 1.6.7-3.1-2.3-2.1 3.1-.3Z" />
        </>,
      );
    case 'envelope':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <rect className="cr-ico-f" x="1.5" y="3.500" width="15" height="11" rx="2" />
          <path className="cr-ico-l" d="M2.200 5.200L9 10l6.800-4.800" />
        </>,
      );
    case 'buoy':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <circle className="cr-ico-f" cx="9" cy="9" r="7.300" />
          <path className="cr-ico-red" d="M9 1.700a7.300 7.300 0 0 1 5.200 2.100l-3 3A3 3 0 0 0 9 6ZM16.300 9a7.300 7.300 0 0 1-2.100 5.200l-3-3A3 3 0 0 0 12 9ZM9 16.300a7.300 7.300 0 0 1-5.200-2.100l3-3A3 3 0 0 0 9 12ZM1.700 9a7.300 7.300 0 0 1 2.100-5.200l3 3A3 3 0 0 0 6 9Z" />
          <circle className="cr-ico-hole" cx="9" cy="9" r="3" />
        </>,
      );
    case 'swirl':
      return svg('0 0 18 18', 'cr-ico', <path className="cr-ico-l" d="M7.500 9a1.500 1.500 0 1 0 3 0a3 3 0 1 0-6 0a4.500 4.500 0 1 0 9 0a6 6 0 1 0-12 0" />);
    case 'lantern':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <path className="cr-ico-l" d="M9 1v2.500M9 14.500V17" />
          <rect className="cr-ico-red" x="4.500" y="3.500" width="9" height="11" rx="4" />
          <path className="cr-ico-l" d="M7.500 4v10M10.500 4v10" />
        </>,
      );
    case 'gift':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <rect className="cr-ico-red" x="2.500" y="7" width="13" height="9" rx="1.500" />
          <path className="cr-ico-l" d="M9 7v9M2 7h14" />
          <path className="cr-ico-l" d="M9 7C6 7 4.500 3 7 3s2 4 2 4c0 0-.5-4 2-4s1 4-2 4Z" />
        </>,
      );
    case 'die':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <rect className="cr-ico-f" x="1.500" y="1.500" width="15" height="15" rx="4" />
          <circle className="cr-ico-dot" cx="5.700" cy="5.700" r="1.500" />
          <circle className="cr-ico-dot" cx="9" cy="9" r="1.500" />
          <circle className="cr-ico-dot" cx="12.300" cy="12.300" r="1.500" />
        </>,
      );
    case 'sound':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <path className="cr-ico-dot" d="M2 7h3l4-3.500v11L5 11H2Z" />
          <path className="cr-ico-l" d="M11.500 6.500a3.500 3.500 0 0 1 0 5M13.500 4a7 7 0 0 1 0 10" />
        </>,
      );
    case 'mute':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <path className="cr-ico-dot" d="M2 7h3l4-3.500v11L5 11H2Z" />
          <path className="cr-ico-l" d="M12 6.500l4.500 5M16.500 6.500l-4.500 5" />
        </>,
      );
    case 'star':
      return svg('0 0 18 18', 'cr-ico', <path className="cr-ico-coin" d="M9 2l2 4.600 5 .5-3.800 3.300 1.100 4.900L9 12.800l-4.300 2.500 1.100-4.900L2 7.100l5-.5Z" />);
    case 'clock':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <circle className="cr-ico-f" cx="9" cy="10" r="6.500" />
          <path className="cr-ico-l" d="M9 6.500V10l2.500 1.500M7 1.500h4" />
        </>,
      );
    case 'bot':
      return svg(
        '0 0 18 18',
        'cr-ico',
        <>
          <rect className="cr-ico-f" x="2.500" y="5.500" width="13" height="10" rx="3" />
          <path className="cr-ico-l" d="M9 5.500V2.500" />
          <circle className="cr-ico-dot" cx="9" cy="2" r="1.200" />
          <circle className="cr-ico-dot" cx="6.500" cy="10" r="1.300" />
          <circle className="cr-ico-dot" cx="11.500" cy="10" r="1.300" />
        </>,
      );
  }
}
