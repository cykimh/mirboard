import { useId, type ReactNode } from 'react';
import { useCardAnimStore } from '@/features/theme/cardAnimStore';

/**
 * D-188 — 게임 아이콘 한 세트(직접 그린 SVG). 게임 id → 그림 매핑은 **이 파일 한 곳**에만 둔다.
 *
 * 한 세트 규칙: 48 그리드, 둥근 타일(1~47, 모서리 11), 왼쪽 위 광원(타일 그라데이션·윤기·오른쪽 아래 그림자), 외곽선 두께 2,
 * 그림은 크림색 + 강조색 하나. 색만 게임마다 다르다. 작게 그릴 때(`size` < {@link DETAIL_FROM})는 윤기·그림자·잔 장식을 뺀다.
 * 타일이 제 바탕색을 가지므로 라이트·다크 어디에 놓아도 같다.
 */

/** 이 크기부터 잔 장식(윤기·그림자·눈·이빨 등)을 그린다. 그 아래는 큰 덩어리만. */
const DETAIL_FROM = 28;

const CREAM = '#fff7e8';
const GOLD = '#f6c84c';

interface Tile {
  /** 타일 그라데이션 — 밝은 쪽(왼쪽 위). */
  hi: string;
  /** 타일 그라데이션 — 어두운 쪽(오른쪽 아래). */
  lo: string;
  /** 외곽선·눈구멍 같은 가장 짙은 색. */
  deep: string;
}

interface IconDef {
  tile: Tile;
  glyph: (ctx: { detail: boolean; deep: string }) => ReactNode;
}

/** 모든 그림이 같이 쓰는 외곽선(두께 2, 둥근 이음). */
const line = (deep: string) =>
  ({ stroke: deep, strokeWidth: 2, strokeLinejoin: 'round', strokeLinecap: 'round' }) as const;

const ICONS: Record<string, IconDef> = {
  // 티츄 — 붉은 옻칠 타일, 카드 위의 용(S 자 몸)과 여의주.
  TICHU: {
    tile: { hi: '#e0503a', lo: '#8a1612', deep: '#4f0b09' },
    glyph: ({ detail, deep }) => (
      <g transform="rotate(-8 24 24)">
        <rect x="12.5" y="7.5" width="23" height="33" rx="4.5" fill={CREAM} {...line(deep)} />
        <path
          d="M29.5 15.5c-6-3.2-11.2-.2-10.3 4.2s9.3 3 10.3 8.3-5 7.4-11 4.3"
          fill="none"
          stroke="#c62a1e"
          strokeWidth="4"
          strokeLinecap="round"
        />
        <path d="M26.6 12.6l7.4.6-1.6 6.2-5.6.4z" fill="#c62a1e" stroke="#c62a1e" strokeWidth="1.6" strokeLinejoin="round" />
        <path d="M28 12.4l-2.6-3.2M31 12.6l-.2-3.8" stroke="#c62a1e" strokeWidth="1.8" strokeLinecap="round" />
        <circle cx="17.6" cy="33" r="2.6" fill={GOLD} stroke={deep} strokeWidth="1" />
        {detail && <circle cx="30.4" cy="15.2" r="1" fill={GOLD} />}
      </g>
    ),
  },
  // 스컬킹 — 밤바다 타일, 금관을 쓴 해골.
  SKULL_KING: {
    tile: { hi: '#3a7690', lo: '#10283a', deep: '#071620' },
    glyph: ({ detail, deep }) => (
      <>
        <path
          d="M24 16c-7 0-11.5 4.6-11.5 10.5 0 3.4 1.5 5.9 3.8 7.6v3.9c0 1.1.9 2 2 2h11.4c1.1 0 2-.9 2-2v-3.9c2.3-1.7 3.8-4.2 3.8-7.6C35.5 20.6 31 16 24 16z"
          fill={CREAM}
          {...line(deep)}
        />
        <circle cx="19.4" cy="27.5" r="3" fill={deep} />
        <circle cx="28.6" cy="27.5" r="3" fill={deep} />
        {detail && (
          <>
            <path d="M24 31l-1.5 2.8h3z" fill={deep} />
            <path d="M21 36.5v3M24 36.5v3M27 36.5v3" stroke={deep} strokeWidth="1.3" strokeLinecap="round" />
          </>
        )}
        <path d="M14 17.5l1.3-10 5.2 4.8L24 6l3.5 6.3 5.2-4.8 1.3 10z" fill={GOLD} {...line(deep)} />
        {detail && <circle cx="24" cy="13.8" r="1.4" fill="#e0503a" />}
      </>
    ),
  },
  // 원카드 — 네온 보라 타일, 겹친 카드 두 장과 "1".
  ONE_CARD: {
    tile: { hi: '#6c47ea', lo: '#1f1260', deep: '#120a3c' },
    glyph: ({ detail, deep }) => (
      <>
        <rect x="9.5" y="11" width="20" height="28" rx="4" fill="#ff5fae" {...line(deep)} transform="rotate(-14 19.5 25)" />
        <g transform="rotate(8 29 24)">
          <rect x="18.5" y="8.5" width="21" height="31" rx="4.5" fill={CREAM} {...line(deep)} />
          <path d="M25.4 20.3l4.4-3.8h2.7v15h-3.8V22l-1.5 1.3z" fill="#4a2bc0" />
        </g>
        {detail && <path d="M10 5l1.1 3.4L14.5 9.5l-3.4 1.1L10 14l-1.1-3.4L5.5 9.5l3.4-1.1z" fill="#5ff0ff" />}
      </>
    ),
  },
  // 요트 — 살롱 펠트 초록 타일, 5 눈 주사위(뒤에 놋쇠 주사위).
  YACHT: {
    tile: { hi: '#2fa37c', lo: '#0b4433', deep: '#05261c' },
    glyph: ({ detail, deep }) => (
      <>
        {detail && (
          <g transform="rotate(16 34 13)">
            <rect x="27" y="6" width="14" height="14" rx="3.5" fill="#e9c25c" {...line(deep)} />
            <circle cx="34" cy="13" r="1.7" fill={deep} />
          </g>
        )}
        <g transform="rotate(-10 23 26)">
          <rect x="9.5" y="12.5" width="27" height="27" rx="6.5" fill={CREAM} {...line(deep)} />
          {[
            [16.2, 19.2],
            [29.8, 19.2],
            [23, 26],
            [16.2, 32.8],
            [29.8, 32.8],
          ].map(([cx, cy]) => (
            <circle key={`${cx}-${cy}`} cx={cx} cy={cy} r="2.6" fill={cx === 23 ? '#c8452f' : deep} />
          ))}
        </g>
      </>
    ),
  },
  // 오목 — 나뭇결 판 타일, 대각선으로 놓인 흑돌 다섯과 막아선 백돌 둘.
  OMOK: {
    tile: { hi: '#f1c873', lo: '#b9822f', deep: '#4a2f0c' },
    glyph: ({ detail, deep }) => (
      <>
        {detail && (
          <path
            d="M9 5v38M16.5 5v38M24 5v38M31.5 5v38M39 5v38M5 9h38M5 16.5h38M5 24h38M5 31.5h38M5 39h38"
            stroke={deep}
            strokeOpacity="0.38"
            strokeWidth="1"
          />
        )}
        {[
          [16.5, 24],
          [31.5, 24],
        ].map(([cx, cy]) => (
          <circle key={cx} cx={cx} cy={cy} r="3.7" fill="#fffdf6" stroke={deep} strokeWidth="1.2" />
        ))}
        {[9, 16.5, 24, 31.5, 39].map((cx) => (
          <circle key={cx} cx={cx} cy={48 - cx} r={detail ? 3.7 : 4.2} fill="#1d1a17" />
        ))}
        {detail &&
          [9, 16.5, 24, 31.5, 39].map((cx) => (
            <circle key={cx} cx={cx - 1.2} cy={48 - cx - 1.3} r="1" fill="#fff" fillOpacity="0.45" />
          ))}
      </>
    ),
  },
  // 구름항로 — 하늘색 타일, 줄무늬 열기구와 솜구름.
  CLOUD_ROUTE: {
    tile: { hi: '#8fd8ff', lo: '#3486dc', deep: '#143f7c' },
    glyph: ({ detail, deep }) => (
      <>
        {detail && (
          <path
            d="M3 43.5a4.5 4.5 0 0 1 5.2-4.4 6 6 0 0 1 11 1.4 4 4 0 0 1 1.8 7.5H3z"
            fill="#fff"
            fillOpacity="0.92"
          />
        )}
        <path d="M21 31.5l1 4M27 31.5l-1 4" stroke={deep} strokeWidth="1.4" strokeLinecap="round" />
        <path
          d="M24 6.5c-7.2 0-12.5 5.2-12.5 11.8 0 5.7 4.7 9.7 8.8 13.2h7.4c4.1-3.5 8.8-7.5 8.8-13.2C36.5 11.7 31.2 6.5 24 6.5z"
          fill={CREAM}
          {...line(deep)}
        />
        <path
          d="M24 7.5c-3.2 3-4.8 6.8-4.8 11 0 4.7 1.5 8.4 2.6 12h4.4c1.1-3.6 2.6-7.3 2.6-12 0-4.2-1.6-8-4.8-11z"
          fill="#ff7a5c"
        />
        <rect x="20.5" y="35" width="7" height="6" rx="1.5" fill="#e9b45f" {...line(deep)} />
      </>
    ),
  },
  // 씨앗포 — 새잎 연두 타일, 포구에서 새싹이 돋는 클레이 포.
  ARTILLERY: {
    tile: { hi: '#bfe35a', lo: '#5e9c22', deep: '#27480c' },
    glyph: ({ detail, deep }) => (
      <>
        <path d="M33.2 18.5L36 12" stroke={deep} strokeWidth="2" strokeLinecap="round" />
        <path d="M35.8 12.5c-5 .6-8-1.7-8.2-6 4.8-1.2 8.2 1 8.2 6z" fill="#f2ffc4" {...line(deep)} />
        <path d="M36.2 12c-.3-4.3 2.2-6.8 6.3-7 1 4.3-1.7 7-6.3 7z" fill="#f2ffc4" {...line(deep)} />
        <rect x="15" y="24.5" width="24" height="11" rx="5.5" fill={CREAM} {...line(deep)} transform="rotate(-40 20 30)" />
        <circle cx="19" cy="33" r="7.5" fill={CREAM} {...line(deep)} />
        <circle cx="19" cy="33" r="2.6" fill={detail ? '#ffb02e' : deep} stroke={detail ? deep : 'none'} strokeWidth="1" />
      </>
    ),
  },
};

/** 모르는 게임 — 회색 타일의 말(폰). */
const FALLBACK: IconDef = {
  tile: { hi: '#8d99ab', lo: '#3f4958', deep: '#1c232d' },
  glyph: ({ deep }) => (
    <>
      <path d="M14 39c0-6 3.5-8 5-13h10c1.5 5 5 7 5 13z" fill={CREAM} {...line(deep)} />
      <circle cx="24" cy="17" r="6.5" fill={CREAM} {...line(deep)} />
    </>
  ),
};

/** 아이콘이 있는 게임 id 목록(테스트·비교 그림용). */
export const GAME_ICON_IDS: readonly string[] = Object.keys(ICONS);

/** 이 게임에 전용 아이콘이 있는가. 없으면 {@link GameIcon} 은 기본 아이콘을 그린다. */
export function hasGameIcon(gameId: string | null | undefined): boolean {
  return gameId != null && Object.prototype.hasOwnProperty.call(ICONS, gameId);
}

interface Props {
  /** 카탈로그의 게임 id(`TICHU` 등). 모르는 값이면 기본 아이콘. */
  gameId: string | null | undefined;
  /** 한 변 길이(px). 기본 24. */
  size?: number;
  /** 있으면 그림에 이름을 붙인다(`role="img"`). 없으면 장식으로 숨긴다 — 옆에 게임 이름 글자가 있을 때. */
  title?: string;
  /**
   * 조상에 tailwind `group` 이 있을 때, 그 조상에 마우스를 올리면 살짝 떠오르고 반짝이며 누르면 눌린다(허브 카드).
   * 연출 끔(카드 애니메이션 설정)·움직임 줄이기면 움직이지 않는다.
   */
  interactive?: boolean;
  className?: string;
}

export function GameIcon({ gameId, size = 24, title, interactive = false, className }: Props) {
  const animOn = useCardAnimStore((s) => s.enabled);
  const known = hasGameIcon(gameId);
  const def = known ? ICONS[gameId as string] : FALLBACK;
  const detail = size >= DETAIL_FROM;
  // defs id — 그림·단순형까지 넣어, 따로 그린 정적 마크업(비교 그림)에서 useId 가 겹쳐도 서로 다른 그림의 색을 집지 않는다.
  const uid = `gi${useId().replace(/[^a-zA-Z0-9_-]/g, '')}-${known ? gameId : 'x'}-${detail ? 'd' : 's'}`;
  const live = interactive && animOn;
  const { hi, lo, deep } = def.tile;
  const cls = [
    live &&
      'transition-transform duration-200 ease-out motion-safe:group-hover:-translate-y-0.5 motion-safe:group-hover:-rotate-3 motion-safe:group-hover:scale-110 motion-safe:group-active:scale-95',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      data-game-icon={known ? (gameId as string) : 'DEFAULT'}
      className={cls || undefined}
      style={{ display: 'block', flexShrink: 0 }}
      {...(title ? { role: 'img', 'aria-label': title } : { 'aria-hidden': true })}
    >
      {title && <title>{title}</title>}
      <defs>
        <linearGradient id={`${uid}-t`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={hi} />
          <stop offset="1" stopColor={lo} />
        </linearGradient>
        <clipPath id={`${uid}-c`}>
          <rect x="1" y="1" width="46" height="46" rx="11" />
        </clipPath>
        {detail && (
          <filter id={`${uid}-s`} x="-20%" y="-20%" width="150%" height="150%">
            <feDropShadow dx="1" dy="1.8" stdDeviation="0.3" floodColor="#000" floodOpacity="0.3" />
          </filter>
        )}
      </defs>
      <rect x="1" y="1" width="46" height="46" rx="11" fill={`url(#${uid}-t)`} />
      <g clipPath={`url(#${uid}-c)`}>
        {/* 윤기 — 왼쪽 위 광원. */}
        {detail && <path d="M1 1h46v13C33 13 14 18 1 28z" fill="#fff" fillOpacity="0.14" />}
        <g filter={detail ? `url(#${uid}-s)` : undefined}>{def.glyph({ detail, deep })}</g>
        {live && (
          <g transform="rotate(20 24 24)">
            <rect
              x="-18"
              y="-12"
              width="11"
              height="72"
              fill="#fff"
              fillOpacity="0.38"
              className="opacity-0 transition-transform duration-700 ease-out motion-safe:group-hover:translate-x-[84px] motion-safe:group-hover:opacity-100"
            />
          </g>
        )}
      </g>
      {/* 테두리 — 밝은 바탕에서는 바깥 어두운 선이, 어두운 바탕에서는 안쪽 밝은 선이 타일을 떼어 놓는다. */}
      <rect x="1.5" y="1.5" width="45" height="45" rx="10.5" fill="none" stroke="#000" strokeOpacity="0.22" />
      <rect x="2.5" y="2.5" width="43" height="43" rx="9.5" fill="none" stroke="#fff" strokeOpacity="0.26" />
    </svg>
  );
}
