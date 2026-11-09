import type { ForbiddenKind, OmokPoint } from '@/types/omok';

/**
 * 튜토리얼 그림용 작은 판(정적, 입력 없음) — 큰 판의 일부(기본 9×9)를 잘라 보여 준다. 금수 ✕ 는 설명용으로 직접 준다(판정은 서버 몫).
 * 좌표는 이 그림 안의 칸이다.
 */
export function MiniBoard({
  size = 9,
  black,
  white = [],
  forbidden = [],
  marks = [],
  winLine = [],
  label,
}: {
  size?: number;
  black: OmokPoint[];
  white?: OmokPoint[];
  forbidden?: (OmokPoint & { kind?: ForbiddenKind })[];
  /** 글자 표시(퀴즈 보기 A·B·C 등). */
  marks?: (OmokPoint & { text: string })[];
  winLine?: OmokPoint[];
  label: string;
}) {
  const lines = Array.from({ length: size }, (_, i) => i);
  return (
    <figure className="om-mini">
      <svg className="om-board om-mini-board" viewBox={`-0.5 -0.5 ${size} ${size}`} role="img" aria-label={label}>
        <rect className="om-board-bg" x={-0.5} y={-0.5} width={size} height={size} />
        {lines.map((i) => (
          <g key={i}>
            <line className="om-grid" x1={0} y1={i} x2={size - 1} y2={i} />
            <line className="om-grid" x1={i} y1={0} x2={i} y2={size - 1} />
          </g>
        ))}
        {black.map((p) => (
          <circle key={`b${p.x},${p.y}`} className="om-stone om-stone-black" cx={p.x} cy={p.y} r={0.44} />
        ))}
        {white.map((p) => (
          <circle key={`w${p.x},${p.y}`} className="om-stone om-stone-white" cx={p.x} cy={p.y} r={0.44} />
        ))}
        {forbidden.map((f) => (
          <g key={`f${f.x},${f.y}`} className="om-forbidden om-forbidden-mine">
            <line x1={f.x - 0.2} y1={f.y - 0.2} x2={f.x + 0.2} y2={f.y + 0.2} />
            <line x1={f.x - 0.2} y1={f.y + 0.2} x2={f.x + 0.2} y2={f.y - 0.2} />
          </g>
        ))}
        {marks.map((m) => (
          <text
            key={`m${m.x},${m.y}`}
            className="om-mark"
            x={m.x}
            y={m.y}
            dominantBaseline="central"
            textAnchor="middle"
          >
            {m.text}
          </text>
        ))}
        {winLine.length >= 2 && (
          <line
            className="om-winline"
            x1={winLine[0].x}
            y1={winLine[0].y}
            x2={winLine[winLine.length - 1].x}
            y2={winLine[winLine.length - 1].y}
          />
        )}
      </svg>
      <figcaption className="om-mini-caption">{label}</figcaption>
    </figure>
  );
}
