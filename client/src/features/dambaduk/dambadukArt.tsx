/**
 * 담바둑 그림 조각 (D-279) — 직접 그린 인라인 SVG(외부 에셋 없음). **id 를 쓰지 않는다**(같은 그림이 한 화면에 여러 번 뜨고 튜토리얼 포털에도
 * 그려지므로 — 티츄 `tichuArt.tsx` 와 같은 규칙). 색은 `currentColor`·CSS 변수로 받는다(`.db-c-black` 등이 정한다).
 *
 * <p>모양으로 종류를 가른다(색만으로 가르지 않는다 — 색약): 곧은돌은 둥근 돌 + 곧은 결 한 줄, 꺾은돌은 모서리 둥근 네모 + ㄱ자 결.
 *
 * <p>그라데이션은 SVG `<defs>`(id 필요) 대신 CSS 로 입힌다 — 클래식 테마는 `svg.db-stone-art` 자체의 바탕(방사형 그라데이션 + 둥근
 * 모서리)이 돌 몸통이고, 여기 그린 납작한 몸통(`db-stone-shadow·body·gloss`)은 테마를 끈 모습에서만 보인다. 각인은 어두운 홈
 * (`db-stone-mark`)과 그 아래 밝은 턱(`db-stone-mark-lip`) 두 줄로 파인 느낌을 낸다.
 */
import type { CSSProperties } from 'react';
import type { DambadukStoneKind } from '@/types/dambaduk';

export function StoneArt({ kind }: { kind: DambadukStoneKind }) {
  const straight = kind === 'STRAIGHT';
  const mark = straight ? 'M32 50h36' : 'M35 36h28v28';
  return (
    <svg
      className={`db-stone-art db-stone-art-${straight ? 'straight' : 'bent'}`}
      viewBox="0 0 100 100"
      aria-hidden="true"
      focusable="false"
    >
      {straight ? (
        <>
          <circle className="db-stone-shadow" cx="52" cy="56" r="38" />
          <circle className="db-stone-body" cx="50" cy="50" r="38" />
          <ellipse className="db-stone-gloss" cx="38" cy="36" rx="14" ry="9" />
        </>
      ) : (
        <>
          <rect className="db-stone-shadow" x="16" y="20" width="72" height="72" rx="18" />
          <rect className="db-stone-body" x="14" y="14" width="72" height="72" rx="18" />
          <ellipse className="db-stone-gloss" cx="36" cy="32" rx="13" ry="8" />
        </>
      )}
      <g className="db-stone-marks">
        <path className="db-stone-mark-lip" d={mark} transform="translate(1.6 2.2)" />
        <path className="db-stone-mark" d={mark} />
      </g>
    </svg>
  );
}

interface WallArtProps {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  /** 방금 세운 담 — 쌓이는 연출. */
  fresh?: boolean;
  /** 보낸 담(응답 전) — 흐리게. */
  pending?: boolean;
}

/** 담 두께의 절반(판 그림 단위 — 칸 = 100). */
const HALF = 10;
/** 양 끝이 먹선 교차점을 덮도록 넘기는 길이. */
const OVER = 5;

/**
 * 돌 놓는 무늬 — `[시작, 끝, 줄]`(길이 0~100 기준). 줄 0 = 담 두께 전체를 덮는 큰 돌, 1·2 = 반쪽 두께의 납작한 돌(위·아래 줄).
 * 큰 돌과 엇갈린 반쪽 돌을 섞어, 작게 보여도 "겹쳐 쌓은 돌"로 읽히게 한다. 면 위치마다 무늬가 달라 담이 줄지어도 판박이가 아니다.
 */
type Piece = readonly [number, number, 0 | 1 | 2];
const PATTERNS: readonly (readonly Piece[])[] = [
  [
    [-4, 20, 0],
    [22, 52, 1],
    [22, 40, 2],
    [42, 70, 2],
    [54, 70, 1],
    [72, 104, 0],
  ],
  [
    [-4, 24, 1],
    [-4, 14, 2],
    [16, 46, 2],
    [26, 46, 1],
    [48, 74, 0],
    [76, 104, 1],
    [76, 92, 2],
    [94, 104, 2],
  ],
  [
    [-4, 30, 0],
    [32, 60, 1],
    [32, 48, 2],
    [50, 82, 2],
    [62, 82, 1],
    [84, 104, 0],
  ],
];
const LANE: Record<0 | 1 | 2, readonly [number, number]> = {
  0: [-8.4, 8.4],
  1: [-8.4, -0.8],
  2: [0.8, 8.4],
};
const TONES = ['a', 'b', 'c'] as const;

/** 담 한 칸 — 먹선 위에 쌓은 낮은 돌담(납작한 돌을 겹친 모양). 좌표는 판 그림 단위(칸 = 100). */
export function WallArt({ x1, y1, x2, y2, fresh = false, pending = false }: WallArtProps) {
  const vertical = x1 === x2;
  const len = vertical ? y2 - y1 : x2 - x1;
  const k = len / 100;
  /** 길이 방향 a0~a1, 두께 방향 b0~b1 → 판 좌표의 네모. */
  const box = (a0: number, a1: number, b0: number, b1: number) =>
    vertical
      ? { x: x1 + b0, y: y1 + a0 * k, width: b1 - b0, height: (a1 - a0) * k }
      : { x: x1 + a0 * k, y: y1 + b0, width: (a1 - a0) * k, height: b1 - b0 };
  const pattern = PATTERNS[Math.abs(Math.round(x1 / 100) * 3 + Math.round(y1 / 100) * 5 + (vertical ? 1 : 0)) % PATTERNS.length];
  const base = box(-OVER, 100 + OVER, -HALF, HALF);
  return (
    <g className={`db-wall${fresh ? ' db-wall-fresh' : ''}${pending ? ' db-wall-pending' : ''}`}>
      <rect className="db-wall-shadow" {...base} x={base.x + 2} y={base.y + 4} rx="6" />
      <rect className="db-wall-base" {...base} rx="6" />
      {pattern.map(([a0, a1, lane], i) => (
        <rect
          key={i}
          className={`db-wall-stone db-wall-stone-${TONES[(i + lane) % TONES.length]}`}
          {...box(a0, a1, LANE[lane][0], LANE[lane][1])}
          rx="3"
          style={{ '--db-i': i } as CSSProperties}
        />
      ))}
    </g>
  );
}
