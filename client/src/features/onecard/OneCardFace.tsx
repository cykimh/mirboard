import type { ReactNode } from 'react';
import { rankLabel, type OneCardCard, type OneCardSuit } from '@/types/onecard';
import { attackValue } from './onecardRules';

/**
 * 네온 파티 테마(D-260)의 카드 그림 면 — 전부 직접 그린 SVG 다(외부 소재 없음). 62×88 한 칸에 그리고 칩 크기를 따라 늘어난다.
 * 왼쪽 위 인덱스(숫자 + 작은 무늬)는 손패가 겹쳐 왼쪽 띠만 보일 때도 읽힌다(D-160 최대 2줄). 색은 CSS 가 무늬 클래스
 * (`.oc-s-*`)의 `color` 로 정하고, 보조 색은 `--oc-face-ink`. 장식이라 `aria-hidden` — 이름은 칩의 aria-label 이 읽는다.
 */

/** 무늬 그림 — 24×24 칸. 넷 다 직접 그린 경로다. */
export const SUIT_PATH: Record<OneCardSuit, string> = {
  HEART: 'M12 21C5 15.4 2 11.6 2 8a5 5 0 0 1 10-1.9A5 5 0 0 1 22 8c0 3.6-3 7.4-10 13Z',
  DIAMOND: 'M12 1.5 20.5 12 12 22.5 3.5 12Z',
  SPADE:
    'M12 1.8C8.2 6.6 3 9.6 3 14a4.4 4.4 0 0 0 7.9 2.7c-.1 2.4-.9 4-2.6 5.5h7.4c-1.7-1.5-2.5-3.1-2.6-5.5A4.4 4.4 0 0 0 21 14c0-4.4-5.2-7.4-9-12.2Z',
  CLUB: 'M12 2a4.4 4.4 0 0 1 3.5 7.1 4.4 4.4 0 1 1-2.4 7.6c.1 2.3.9 3.9 2.6 5.5H8.3c1.7-1.6 2.5-3.2 2.6-5.5a4.4 4.4 0 1 1-2.4-7.6A4.4 4.4 0 0 1 12 2Z',
};

/** 무늬 클래스 — 네 색(♠ 하늘·♥ 분홍·♦ 호박·♣ 라임). 칩·이전 카드 줄·무늬 고르기·연출이 같이 쓴다. */
export const SUIT_CLASS: Record<OneCardSuit, string> = {
  SPADE: 'oc-s-spade',
  HEART: 'oc-s-heart',
  DIAMOND: 'oc-s-diamond',
  CLUB: 'oc-s-club',
};

/** 무늬 한 개 — `size` px 칸에 맞춘 인라인 SVG(무늬 고르기·지정 배지·7 연출). 색은 무늬 클래스의 `color`. */
export function SuitGlyph({
  suit,
  size = 24,
  className = '',
}: {
  suit: OneCardSuit;
  size?: number;
  className?: string;
}) {
  return (
    <svg
      className={`oc-glyph ${SUIT_CLASS[suit]}${className ? ` ${className}` : ''}`}
      viewBox="0 0 24 24"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
    >
      <path d={SUIT_PATH[suit]} fill="currentColor" />
    </svg>
  );
}

/** 무늬를 (x, y) 에 `size` 크기로 — 뒤에 흐린 겹을 깔아 빛 번짐을 흉내 낸다(필터 없이). */
function Pip({ suit, x, y, size }: { suit: OneCardSuit; x: number; y: number; size: number }) {
  return (
    <g transform={`translate(${x} ${y}) scale(${size / 24})`}>
      <path d={SUIT_PATH[suit]} className="oc-f-glow" />
      <path d={SUIT_PATH[suit]} className="oc-f-fill" />
    </g>
  );
}

/** J — 고깔모자·선글라스 파티꾼. 아래는 건너뛰기 화살. */
function JackArt() {
  return (
    <>
      <path d="M40 28 31.5 45h17Z" className="oc-f-line" />
      <path d="M36.2 36h7.6M33.8 41h12.4" className="oc-f-ink" />
      <circle cx="40" cy="26.5" r="2.6" className="oc-f-inkfill" />
      <circle cx="40" cy="55" r="9.5" className="oc-f-line" />
      <rect x="31.8" y="51" width="7" height="5" rx="2" className="oc-f-fill" />
      <rect x="41.2" y="51" width="7" height="5" rx="2" className="oc-f-fill" />
      <path d="M38.8 52.6h2.4" className="oc-f-line" />
      <path d="M35.5 59.5q4.5 4 9 0" className="oc-f-ink" />
      <path d="M30 71l5.5 5.5L30 82M38 71l5.5 5.5L38 82M48.5 71v11" className="oc-f-ink oc-f-thick" />
    </>
  );
}

/** Q — 티아라를 쓴 여왕. 아래는 방향 바뀜 화살. */
function QueenArt() {
  return (
    <>
      <path d="M31 45 32.5 34l5 7L40 31l2.5 10 5-7L49 45Z" className="oc-f-line" />
      <circle cx="32.5" cy="33" r="1.7" className="oc-f-inkfill" />
      <circle cx="40" cy="30" r="1.9" className="oc-f-inkfill" />
      <circle cx="47.5" cy="33" r="1.7" className="oc-f-inkfill" />
      <circle cx="40" cy="55" r="9.5" className="oc-f-line" />
      <path d="M30.5 52q-4.5 8 .5 14M49.5 52q4.5 8-.5 14" className="oc-f-line" />
      <path d="M34.3 53.5q2 2.2 4 0M41.7 53.5q2 2.2 4 0" className="oc-f-ink" />
      <path d="M37.2 59.5q2.8 2.6 5.6 0" className="oc-f-ink" />
      <path d="M31 74h17l-3.5-3.5M49 80H32l3.5 3.5" className="oc-f-ink oc-f-thick" />
    </>
  );
}

/** K — 왕관·콧수염 임금. 아래는 한 번 더(+1). */
function KingArt() {
  return (
    <>
      <path d="M30.5 45V33l5 6 4.5-9 4.5 9 5-6v12Z" className="oc-f-line" />
      <path d="M30.5 41.5h19" className="oc-f-ink" />
      <circle cx="40" cy="29" r="1.9" className="oc-f-inkfill" />
      <circle cx="40" cy="55" r="9.5" className="oc-f-line" />
      <circle cx="36.3" cy="52.5" r="1.3" className="oc-f-inkfill" />
      <circle cx="43.7" cy="52.5" r="1.3" className="oc-f-inkfill" />
      <path d="M40 57.5q-3.5-3-8 1M40 57.5q3.5-3 8 1" className="oc-f-ink oc-f-thick" />
      <path d="M34.5 61.5q5.5 5 11 0" className="oc-f-line" />
      <path d="M36.5 72.5a5 5 0 1 0 4.6 3M36.5 72.5l-3.2-.4M36.5 72.5l-.6 3.2" className="oc-f-ink" />
      <text x="50" y="82" textAnchor="middle" className="oc-f-tag">
        +1
      </text>
    </>
  );
}

/** A — 큰 무늬에 폭죽 살. */
function AceArt({ suit }: { suit: OneCardSuit }) {
  const cx = 39;
  const cy = 57;
  const rays = Array.from({ length: 10 }, (_, i) => {
    const a = (i / 10) * Math.PI * 2;
    const p = (r: number) => `${(cx + Math.cos(a) * r).toFixed(1)} ${(cy + Math.sin(a) * r).toFixed(1)}`;
    return `M${p(20)}L${p(i % 2 === 0 ? 25 : 23)}`;
  }).join('');
  return (
    <>
      <path d={rays} className="oc-f-ink" />
      <Pip suit={suit} x={25} y={43} size={28} />
    </>
  );
}

/** 7 — 무늬 둘레에 도는 화살 둘(무늬 바꾸기). */
function SevenArt({ suit }: { suit: OneCardSuit }) {
  return (
    <>
      <path d="M22 54a18 18 0 0 1 30-9M52 45l.6-5M52 45l-5-.2" className="oc-f-ink" />
      <path d="M57 61a18 18 0 0 1-30 9M27 70l-.6 5M27 70l5 .2" className="oc-f-ink" />
      <Pip suit={suit} x={27} y={45} size={25} />
    </>
  );
}

/** 조커 — 방울 달린 세 갈래 모자의 어릿광대. 흑백·컬러는 CSS 가 갈래 색(`.oc-j-*`)으로 가른다. */
function JokerArt({ plus }: { plus: number }) {
  return (
    <>
      <text x="4.5" y="13" className="oc-f-tag oc-f-tag-joker">
        +{plus}
      </text>
      <path d="M22 43Q12 37 11 25q9 6 18 14Z" className="oc-j-a" />
      <path d="M40 43q10-6 11-18-9 6-18 14Z" className="oc-j-c" />
      <path d="M25 41 31 16l6 25Z" className="oc-j-b" />
      <circle cx="11" cy="23.5" r="2.7" className="oc-j-ball" />
      <circle cx="51" cy="23.5" r="2.7" className="oc-j-ball" />
      <circle cx="31" cy="13.5" r="2.7" className="oc-j-ball" />
      <rect x="20.5" y="40" width="21" height="4.5" rx="2.2" className="oc-j-ball" />
      <circle cx="31" cy="54.5" r="9.5" className="oc-j-face" />
      <path d="M27 49.5l1.5 3-1.5 3-1.5-3ZM35 49.5l1.5 3-1.5 3-1.5-3Z" className="oc-j-eye" />
      <path d="M25 57.5q6 7 12 0Z" className="oc-j-eye" />
      <path d="M15 73l4-6.5 4 6.5 4-6.5 4 6.5 4-6.5 4 6.5 4-6.5 4 6.5Z" className="oc-j-b" />
      <path d="M15 73l4-6.5 4 6.5ZM31 73l4-6.5 4 6.5Z" className="oc-j-a" />
      <path d="M23 73l4-6.5 4 6.5ZM39 73l4-6.5 4 6.5Z" className="oc-j-c" />
      <text x="31" y="84" textAnchor="middle" className="oc-f-joker-name">
        JOKER
      </text>
    </>
  );
}

function courtArt(card: OneCardCard, suit: OneCardSuit): ReactNode {
  switch (card.rank) {
    case 11:
      return <JackArt />;
    case 12:
      return <QueenArt />;
    case 13:
      return <KingArt />;
    case 1:
      return <AceArt suit={suit} />;
    case 7:
      return <SevenArt suit={suit} />;
    default:
      return <Pip suit={suit} x={23} y={41} size={33} />;
  }
}

export function OneCardFace({ card }: { card: OneCardCard }) {
  let art: ReactNode;
  if (card.joker) {
    art = <JokerArt plus={attackValue(card)} />;
  } else {
    const suit = card.suit!;
    const label = rankLabel(card.rank);
    const attack = attackValue(card);
    const wide = label.length > 1;
    art = (
      <>
        <text x={wide ? 3 : 5} y="23" className={`oc-f-rank${wide ? ' oc-f-rank-wide' : ''}`}>
          {label}
        </text>
        <Pip suit={suit} x={5} y={27} size={13} />
        {attack > 0 && (
          <text x="57.5" y="13" textAnchor="end" className="oc-f-tag">
            +{attack}
          </text>
        )}
        {courtArt(card, suit)}
      </>
    );
  }
  return (
    <svg className="oc-face" viewBox="0 0 62 88" aria-hidden="true" focusable="false">
      {art}
    </svg>
  );
}
