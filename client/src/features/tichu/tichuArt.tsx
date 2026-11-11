import type { Special, Suit } from '@/types/tichu';

/**
 * 티츄 "옻칠 찻집" 그림 조각 (D-241). 전부 직접 그린 인라인 SVG — 외부 소재·다른 작품의 그림을 쓰지 않는다.
 * 같은 그림이 한 화면에 여러 번 놓이므로 `id`(그라데이션·필터)를 쓰지 않는다: 색은 단색 + 불투명도 겹침으로만 낸다.
 */

/** 여덟 갈래 별(긴 가지 넷·짧은 가지 넷) 꼭짓점. */
function starPoints(cx: number, cy: number, long: number, short: number, inner: number): string {
  const pts: string[] = [];
  for (let i = 0; i < 16; i++) {
    const r = i % 2 === 1 ? inner : i % 4 === 0 ? long : short;
    const a = (Math.PI / 8) * i - Math.PI / 2;
    pts.push(`${(cx + Math.cos(a) * r).toFixed(2)},${(cy + Math.sin(a) * r).toFixed(2)}`);
  }
  return pts.join(' ');
}
const STAR = starPoints(12, 12, 11.4, 7.4, 3.7);

/** 무늬 넷 — `currentColor` 로 칠한다(색은 카드의 무늬 클래스가 정한다). 옥=고리, 검=칼, 탑=삼층 지붕, 별=여덟 갈래. */
export function SuitGlyph({ suit, className }: { suit: Suit; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      {suit === 'JADE' && (
        <>
          <path
            fill="currentColor"
            fillRule="evenodd"
            d="M12 1.6a10.4 10.4 0 1 1 0 20.8 10.4 10.4 0 0 1 0-20.8Zm0 6.5a3.9 3.9 0 1 0 0 7.8 3.9 3.9 0 0 0 0-7.8Z"
          />
          <path fill="none" stroke="#fff" strokeOpacity="0.45" strokeWidth="1.1" strokeLinecap="round" d="M5.2 9.2a7.4 7.4 0 0 1 4.4-4.3" />
        </>
      )}
      {suit === 'SWORD' && (
        <>
          <path fill="currentColor" d="M12 0.8 15 6v8.2H9V6Z" />
          <path fill="#fff" fillOpacity="0.4" d="M12 2.6 13 6v8.2h-1Z" />
          <rect fill="currentColor" x="5.2" y="14.2" width="13.6" height="2.7" rx="1.35" />
          <rect fill="currentColor" x="10.5" y="16.9" width="3" height="4" />
          <circle fill="currentColor" cx="12" cy="21.9" r="1.9" />
        </>
      )}
      {suit === 'PAGODA' && (
        <>
          <rect fill="currentColor" x="11.2" y="0.6" width="1.6" height="3" />
          <path fill="currentColor" d="M12 2.6 18.2 7.3H5.8Z" />
          <rect fill="currentColor" x="9" y="7.3" width="6" height="2.3" />
          <path fill="currentColor" d="M12 8.4 20.4 13.6H3.6Z" />
          <rect fill="currentColor" x="7.6" y="13.6" width="8.8" height="2.3" />
          <path fill="currentColor" d="M12 14.6 22.6 20H1.4Z" />
          <path fill="currentColor" d="M5.6 20h12.8v3.4h-4.9v-1.2a1.5 1.5 0 0 0-3 0v1.2H5.6Z" />
        </>
      )}
      {suit === 'STAR' && (
        <>
          <polygon fill="currentColor" points={STAR} />
          <circle fill="#fff" fillOpacity="0.5" cx="12" cy="12" r="1.7" />
        </>
      )}
    </svg>
  );
}

const GOLD = '#e7c067';
const GOLD_DEEP = '#b98a2e';
const GOLD_PALE = '#f7e3a6';
const CRIMSON = '#d8392c';
const IVORY = '#f1e7cf';
const JADE = '#3fae84';
const JADE_DEEP = '#1f7d5d';
const INK = '#1a0d0b';

/** 구름 소용돌이 — 특수 카드 바탕 장식. */
function Cloud({ x, y, s = 1, o = 0.32 }: { x: number; y: number; s?: number; o?: number }) {
  return (
    <path
      transform={`translate(${x} ${y}) scale(${s})`}
      fill="none"
      stroke={GOLD}
      strokeOpacity={o}
      strokeWidth="1.1"
      strokeLinecap="round"
      d="M0 4c0-3 4-4 5-1 1-3 6-2 5 1 3 0 3 4 0 4H-1c-3 0-3-4 1-4Zm4 1.5a1.6 1.6 0 1 0 1.6 1.6"
    />
  );
}

function DragonArt() {
  const body = 'M43 25C57 31 53 46 33 46 15 46 11 61 26 67c11 4 19-2 24 6';
  return (
    <>
      <Cloud x={5} y={70} s={0.9} />
      <Cloud x={43} y={52} s={0.8} o={0.24} />
      {/* 여의주 */}
      <circle cx="14" cy="34" r="5.2" fill={CRIMSON} />
      <circle cx="12.4" cy="32.3" r="1.6" fill="#ffd9c7" fillOpacity="0.85" />
      <path d="M8 27c1-3 3-4 5-4M20 28c1-2 1-4 0-6" fill="none" stroke={CRIMSON} strokeWidth="1.3" strokeLinecap="round" />
      {/* 몸 — 금빛 몸통 위에 비늘 점선과 등줄 */}
      <path d={body} fill="none" stroke={GOLD_DEEP} strokeWidth="9" strokeLinecap="round" />
      <path d={body} fill="none" stroke={GOLD} strokeWidth="6.4" strokeLinecap="round" />
      <path d={body} fill="none" stroke={CRIMSON} strokeWidth="6.4" strokeDasharray="0.9 4.6" strokeOpacity="0.75" />
      <path d={body} fill="none" stroke={GOLD_PALE} strokeWidth="1.3" strokeLinecap="round" strokeOpacity="0.9" />
      {/* 꼬리 불꽃 */}
      <path d="M50 73l5-3-1 5 4 1-5 3v-4Z" fill={CRIMSON} />
      {/* 발 */}
      <path d="M22 50l-4 5m4-5-1 6m1-6 3 5M44 59l4 4m-4-4 6 1m-6-1 2 6" fill="none" stroke={GOLD} strokeWidth="1.5" strokeLinecap="round" />
      {/* 갈기 */}
      <path d="M45 19l8-4-3 6h7l-6 4 5 4-8-1Z" fill={CRIMSON} />
      {/* 뿔 */}
      <path d="M39 15l2-9 2 9Zm5 2 5-7-1 9Z" fill={GOLD_PALE} />
      {/* 머리 — 왼쪽을 보는 긴 주둥이 */}
      <path
        d="M47 24c0-7-7-11-15-9l-10 4c-1.5.5-1.5 2 0 2.3l6 1-4 3c-1 1 0 2 1 2l10-1c5 4 12 3 12-2.3Z"
        fill={GOLD}
        stroke={GOLD_DEEP}
        strokeWidth="1"
        strokeLinejoin="round"
      />
      <circle cx="37.5" cy="19.5" r="1.9" fill={INK} />
      <circle cx="37" cy="19" r="0.6" fill="#fff" />
      <circle cx="23.5" cy="20.2" r="0.8" fill={INK} />
      {/* 수염 */}
      <path d="M24 23c-6 1-7 6-12 5M27 27c-3 4-1 8-5 10" fill="none" stroke={GOLD_PALE} strokeWidth="1.1" strokeLinecap="round" />
    </>
  );
}

function PhoenixArt() {
  const wing = 'M28 40C18 37 9 29 5 15c7 6 13 8 18 10-4-5-6-10-6-16 6 8 11 15 13 23Z';
  const wingLines = 'M26 37C19 33 13 27 9 19M27 33c-3-6-6-11-8-17';
  const mirror = 'matrix(-1 0 0 1 60 0)';
  return (
    <>
      <Cloud x={41} y={70} s={0.8} o={0.22} />
      {/* 꼬리 깃 셋 */}
      <path d="M30 46C20 57 13 64 14 76" fill="none" stroke={CRIMSON} strokeWidth="3.6" strokeLinecap="round" />
      <path d="M30 46c0 13 3 21 1 32" fill="none" stroke={GOLD} strokeWidth="3.6" strokeLinecap="round" />
      <path d="M30 46c10 11 17 18 16 30" fill="none" stroke={CRIMSON} strokeWidth="3.6" strokeLinecap="round" />
      <path d="M30 46C20 57 13 64 14 76M30 46c10 11 17 18 16 30" fill="none" stroke={GOLD_PALE} strokeWidth="0.9" strokeLinecap="round" strokeOpacity="0.8" />
      {[
        [14, 76],
        [31, 78],
        [46, 76],
      ].map(([x, y]) => (
        <g key={x}>
          <circle cx={x} cy={y} r="3.6" fill={GOLD} />
          <circle cx={x} cy={y} r="1.7" fill={JADE} />
        </g>
      ))}
      {/* 날개 */}
      <path d={wing} fill={CRIMSON} />
      <path d={wing} fill={CRIMSON} transform={mirror} />
      <path d={wingLines} fill="none" stroke={GOLD} strokeWidth="1.2" strokeLinecap="round" />
      <path d={wingLines} fill="none" stroke={GOLD} strokeWidth="1.2" strokeLinecap="round" transform={mirror} />
      {/* 몸·목·머리 */}
      <ellipse cx="30" cy="39" rx="5.2" ry="8.6" fill={GOLD} stroke={GOLD_DEEP} strokeWidth="0.8" />
      <path d="M30 33c0-7 3-11 7-13" fill="none" stroke={GOLD} strokeWidth="4.4" strokeLinecap="round" />
      <circle cx="38" cy="18.5" r="3.9" fill={GOLD} stroke={GOLD_DEEP} strokeWidth="0.8" />
      <path d="M41.3 17l5.7 1.8-5.7 1.8Z" fill={CRIMSON} />
      <circle cx="38.8" cy="17.8" r="0.9" fill={INK} />
      {/* 볏 */}
      <path d="M36 15c-1-5 1-8 3-10M38 14.5c1-4 4-6 7-7M34.5 16c-3-3-3-6-2-9" fill="none" stroke={CRIMSON} strokeWidth="1.5" strokeLinecap="round" />
      <circle cx="39.3" cy="4.6" r="1.2" fill={GOLD_PALE} />
      <circle cx="45.6" cy="7.2" r="1.2" fill={GOLD_PALE} />
      <circle cx="32.3" cy="6.6" r="1.2" fill={GOLD_PALE} />
    </>
  );
}

function DogArt() {
  return (
    <>
      <Cloud x={41} y={14} s={0.8} o={0.24} />
      {/* 방석 */}
      <ellipse cx="32" cy="74" rx="22" ry="4.5" fill={CRIMSON} />
      <path d="M12 74h40" stroke={GOLD} strokeWidth="1" strokeLinecap="round" />
      {/* 꼬리 */}
      <path d="M47 63c9-3 10-13 3-17" fill="none" stroke={IVORY} strokeWidth="4.6" strokeLinecap="round" />
      {/* 앉은 몸 — 왼쪽을 본다 */}
      <path
        d="M20 31c-4 0-8-2-9-5 2-2 5-3 7-4 0-6 4-10 10-9l2-6 4 7c4 3 6 8 5 14 7 6 11 18 10 32 0 6-2 10-5 12H20c-2 0-2-3 1-4l4-1c-1-9-2-17-3-25-1-4-2-8-2-11Z"
        fill={IVORY}
        stroke="#b9ab8a"
        strokeWidth="0.8"
        strokeLinejoin="round"
      />
      {/* 귀 안쪽·뒷다리 선 */}
      <path d="M30 10.5l1.8 5-3.3-1.2Z" fill="#d9a9a0" />
      <path d="M34 72c-2-10 3-16 10-14M27 72c0-3 1-5 3-6" fill="none" stroke="#a8997a" strokeWidth="1.3" strokeLinecap="round" />
      {/* 눈·코·입 */}
      <circle cx="22.5" cy="21.5" r="1.5" fill={INK} />
      <circle cx="11.8" cy="25.6" r="1.7" fill={INK} />
      <path d="M13 27.5c2 1.5 4 1.5 6 1" fill="none" stroke="#6b5a45" strokeWidth="0.9" strokeLinecap="round" />
      {/* 목줄·방울 */}
      <path d="M20.5 32c6 3.5 13 2.5 18.5-2" fill="none" stroke={CRIMSON} strokeWidth="3" strokeLinecap="round" />
      <circle cx="28" cy="37.5" r="3.2" fill={GOLD} stroke={GOLD_DEEP} strokeWidth="0.8" />
      <path d="M26.3 38h3.4M28 38v2.3" stroke={GOLD_DEEP} strokeWidth="0.9" strokeLinecap="round" />
    </>
  );
}

function MahjongArt() {
  const petals = Array.from({ length: 8 }, (_, i) => {
    const a = (Math.PI / 4) * i;
    return [32 + Math.cos(a) * 8.6, 45 + Math.sin(a) * 8.6] as const;
  });
  return (
    <>
      <Cloud x={5} y={72} s={0.8} o={0.24} />
      <path d="M50 12l1.2 3 3 1.2-3 1.2-1.2 3-1.2-3-3-1.2 3-1.2Z" fill={GOLD_PALE} />
      <path d="M11 46l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8Z" fill={GOLD_PALE} fillOpacity="0.7" />
      {/* 패 — 옥빛 밑판 위 상아 윗판(살짝 기울임) */}
      <g transform="rotate(7 32 46)">
        <rect x="16.5" y="26" width="31" height="44" rx="5" fill={JADE_DEEP} />
        <rect x="16.5" y="23" width="31" height="43" rx="5" fill={IVORY} stroke="#c9bb97" strokeWidth="0.8" />
        {/* 일통 — 겹고리 꽃 */}
        <circle cx="32" cy="45" r="12.5" fill="none" stroke={CRIMSON} strokeWidth="2.6" />
        {petals.map(([x, y], i) => (
          <circle key={i} cx={x.toFixed(2)} cy={y.toFixed(2)} r="1.9" fill={i % 2 ? JADE_DEEP : CRIMSON} />
        ))}
        <circle cx="32" cy="45" r="5.2" fill={JADE_DEEP} />
        <circle cx="32" cy="45" r="2.3" fill={GOLD} />
      </g>
    </>
  );
}

/** 특수 카드 그림 — 검은 칠기 바탕(카드 CSS) 위에 얹는 금·주홍 그림. 60×84. */
export function SpecialArt({ special, className }: { special: Special; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 60 84" preserveAspectRatio="xMidYMid meet" aria-hidden="true" focusable="false">
      {special === 'DRAGON' && <DragonArt />}
      {special === 'PHOENIX' && <PhoenixArt />}
      {special === 'DOG' && <DogArt />}
      {special === 'MAHJONG' && <MahjongArt />}
    </svg>
  );
}

/** 카드 뒷면 금박 문양 — 붉은 옻칠 바탕은 CSS(`.tc-back`)가 칠한다. */
export function CardBackArt({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 60 84" preserveAspectRatio="none" aria-hidden="true" focusable="false">
      <rect x="3" y="3" width="54" height="78" rx="4.5" fill="none" stroke={GOLD} strokeWidth="1.6" />
      <rect x="6.5" y="6.5" width="47" height="71" rx="3" fill="none" stroke={GOLD} strokeWidth="0.7" strokeOpacity="0.7" />
      <path
        d="M6.5 17A10.5 10.5 0 0 0 17 6.5M43 6.5A10.5 10.5 0 0 0 53.5 17M53.5 67A10.5 10.5 0 0 0 43 77.5M17 77.5A10.5 10.5 0 0 0 6.5 67"
        fill="none"
        stroke={GOLD}
        strokeWidth="0.9"
      />
      <circle cx="30" cy="42" r="13.5" fill="none" stroke={GOLD} strokeWidth="1.2" />
      {[0, 45, 90, 135].map((deg) => (
        <ellipse key={deg} cx="30" cy="42" rx="3.6" ry="11" fill="none" stroke={GOLD} strokeWidth="0.9" transform={`rotate(${deg} 30 42)`} />
      ))}
      <circle cx="30" cy="42" r="2.6" fill={GOLD} />
      <path d="M30 13l3.5 4.5-3.5 4.5-3.5-4.5ZM30 62l3.5 4.5-3.5 4.5-3.5-4.5Z" fill={GOLD} fillOpacity="0.85" />
      <path d="M12 42h4M44 42h4" stroke={GOLD} strokeWidth="1.1" strokeLinecap="round" />
    </svg>
  );
}

/** 등불 — 승리 연출. */
export function LanternArt({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 40 64" aria-hidden="true" focusable="false">
      <path d="M20 0v8" stroke={GOLD_DEEP} strokeWidth="1.4" />
      <rect x="13" y="7" width="14" height="4" rx="1.5" fill="#2a1512" stroke={GOLD} strokeWidth="0.8" />
      <ellipse cx="20" cy="28" rx="15" ry="17.5" fill="#c9302a" />
      <ellipse cx="20" cy="28" rx="9" ry="13" fill="#ffb347" fillOpacity="0.55" />
      <ellipse cx="20" cy="29" rx="4.5" ry="7" fill="#ffe7a8" fillOpacity="0.8" />
      <path d="M20 10.5v35M11 13.5c-5 9-5 20 0 29M29 13.5c5 9 5 20 0 29" fill="none" stroke={GOLD} strokeWidth="0.8" strokeOpacity="0.75" />
      <rect x="13" y="44.5" width="14" height="4" rx="1.5" fill="#2a1512" stroke={GOLD} strokeWidth="0.8" />
      <path d="M17 48.5v9M20 48.5v13M23 48.5v9" stroke={GOLD} strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  );
}
