/**
 * 담바둑 그림 조각 (D-279) — 직접 그린 인라인 SVG(외부 에셋 없음). **id 를 쓰지 않는다**(같은 그림이 한 화면에 여러 번 뜨고 튜토리얼 포털에도
 * 그려지므로 — 티츄 `tichuArt.tsx` 와 같은 규칙). 색은 `currentColor`·CSS 변수로 받는다(`.db-c-black` 등이 정한다).
 *
 * <p>모양으로 종류를 가른다(색만으로 가르지 않는다 — 색약): 곧은돌은 둥근 돌 + 곧은 결 한 줄, 꺾은돌은 모서리 둥근 네모 + ㄱ자 결.
 */
import type { DambadukStoneKind } from '@/types/dambaduk';

export function StoneArt({ kind }: { kind: DambadukStoneKind }) {
  return (
    <svg className="db-stone-art" viewBox="0 0 100 100" aria-hidden="true" focusable="false">
      {kind === 'STRAIGHT' ? (
        <>
          <circle className="db-stone-shadow" cx="52" cy="56" r="38" />
          <circle className="db-stone-body" cx="50" cy="50" r="38" />
          <ellipse className="db-stone-gloss" cx="38" cy="36" rx="14" ry="9" />
          <path className="db-stone-mark" d="M33 50h34" />
        </>
      ) : (
        <>
          <rect className="db-stone-shadow" x="16" y="20" width="72" height="72" rx="18" />
          <rect className="db-stone-body" x="14" y="14" width="72" height="72" rx="18" />
          <ellipse className="db-stone-gloss" cx="36" cy="32" rx="13" ry="8" />
          <path className="db-stone-mark" d="M35 36h26v28" />
        </>
      )}
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

/** 담 한 칸 — 먹선 위에 쌓은 낮은 돌담(납작한 돌 셋). 좌표는 판 그림 단위(칸 = 100). */
export function WallArt({ x1, y1, x2, y2, fresh = false, pending = false }: WallArtProps) {
  const vertical = x1 === x2;
  const len = vertical ? y2 - y1 : x2 - x1;
  const thick = 16;
  const x = vertical ? x1 - thick / 2 : x1;
  const y = vertical ? y1 : y1 - thick / 2;
  const w = vertical ? thick : len;
  const h = vertical ? len : thick;
  // 돌 셋 — 길이 방향으로 나눈다.
  const parts = [0, 1, 2].map((i) => {
    const a = (len / 3) * i + 3;
    const b = len / 3 - 6;
    return vertical
      ? { x: x + 1, y: y + a, w: thick - 2, h: b }
      : { x: x + a, y: y + 1, w: b, h: thick - 2 };
  });
  return (
    <g className={`db-wall${fresh ? ' db-wall-fresh' : ''}${pending ? ' db-wall-pending' : ''}`}>
      <rect className="db-wall-base" x={x} y={y} width={w} height={h} rx="5" />
      {parts.map((p, i) => (
        <rect key={i} className="db-wall-stone" x={p.x} y={p.y} width={p.w} height={p.h} rx="4" />
      ))}
    </g>
  );
}
