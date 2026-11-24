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
  /** 담 넘기로 방금 지운 담(D-307) — 그 자리에서 부서지듯 흩어진다. 연출이 없으면 보이지 않는다. */
  gone?: boolean;
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
export function WallArt({ x1, y1, x2, y2, fresh = false, pending = false, gone = false }: WallArtProps) {
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
    <g className={`db-wall${fresh ? ' db-wall-fresh' : ''}${pending ? ' db-wall-pending' : ''}${gone ? ' db-wall-gone' : ''}`}>
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

/**
 * 돌가루·먹점 — `[길이 위치(0~100), 옆(-1·1), 튀는 거리, 길이 방향으로 밀리는 양, 크기, 늦음(초)]`. 정해 둔 값이라 그릴 때마다 같다
 * (난수를 쓰지 않는다 — 그리기는 순수하게).
 */
const GRIT: readonly (readonly [number, -1 | 1, number, number, number, number])[] = [
  [4, -1, 62, -30, 9, 0],
  [13, 1, 88, -18, 7, 0.03],
  [25, -1, 104, -10, 11, 0.01],
  [33, 1, 58, 6, 8, 0.05],
  [43, -1, 78, 4, 7, 0.04],
  [50, 1, 112, 0, 12, 0],
  [57, -1, 56, -6, 10, 0.06],
  [66, 1, 82, 12, 7, 0.02],
  [75, -1, 98, 16, 11, 0.03],
  [83, 1, 64, 22, 9, 0.01],
  [91, -1, 86, 28, 8, 0.05],
  [97, 1, 94, 34, 10, 0.02],
];

/**
 * D-307 — 담이 "쿵" 닿는 순간의 연출 조각: 번쩍이는 빛무리, 담 둘레의 빛 테두리, 먹 번짐 고리, 양옆으로 튀는 돌가루·먹점. 담 그림(`WallArt`)과 따로
 * 그린다 — 담은 떨어지며 움직이고 이 조각들은 닿는 자리에 머문다. 기본은 전부 투명이고 연출(`.db-fx-rich`)이 보여 준다.
 */
export function WallImpactArt({ x1, y1, x2, y2 }: Pick<WallArtProps, 'x1' | 'y1' | 'x2' | 'y2'>) {
  const vertical = x1 === x2;
  const len = vertical ? y2 - y1 : x2 - x1;
  const k = len / 100;
  const cx = (x1 + x2) / 2;
  const cy = (y1 + y2) / 2;
  /** 길이 방향 a, 두께 방향 b → 판 좌표. */
  const at = (a: number, b: number) => (vertical ? { x: x1 + b, y: y1 + a * k } : { x: x1 + a * k, y: y1 + b });
  const halo = (pad: number) =>
    vertical
      ? { x: x1 - HALF - pad, y: y1 - OVER - pad, width: (HALF + pad) * 2, height: len + (OVER + pad) * 2 }
      : { x: x1 - OVER - pad, y: y1 - HALF - pad, width: len + (OVER + pad) * 2, height: (HALF + pad) * 2 };
  return (
    <g className="db-wall-fx">
      {/* 번쩍 — 옅은 빛 세 겹을 겹쳐 가운데가 밝은 빛무리로(그라데이션 id 없이). */}
      {[1, 0.72, 0.46].map((f) => (
        <ellipse
          key={f}
          className="db-wall-flash"
          cx={cx}
          cy={cy}
          rx={(vertical ? 58 : 104) * f}
          ry={(vertical ? 104 : 58) * f}
        />
      ))}
      <ellipse className="db-wall-ring" cx={cx} cy={cy} rx={vertical ? 46 : 92} ry={vertical ? 92 : 46} />
      <rect className="db-wall-glow db-wall-glow-wide" {...halo(11)} rx="19" />
      <rect className="db-wall-glow" {...halo(4)} rx="11" />
      {GRIT.map(([a, side, far, drift, size, late], i) => {
        const p = at(a, side * (HALF + 2));
        const d = vertical ? { dx: side * far, dy: drift } : { dx: drift, dy: side * far };
        const style = { '--db-dx': `${d.dx}px`, '--db-dy': `${d.dy}px`, '--db-late': `${late}s` } as CSSProperties;
        // 짝수 = 돌가루(둥근 알), 홀수 = 먹점(튀는 쪽으로 길쭉한 방울).
        return i % 2 === 0 ? (
          <circle key={i} className="db-wall-grit" cx={p.x} cy={p.y} r={size} style={style} />
        ) : (
          <ellipse
            key={i}
            className="db-wall-grit db-wall-grit-ink"
            cx={p.x}
            cy={p.y}
            rx={vertical ? size * 1.4 : size * 0.55}
            ry={vertical ? size * 0.55 : size * 1.4}
            style={style}
          />
        );
      })}
    </g>
  );
}
