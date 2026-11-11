import { useId } from 'react';
import type { ForbiddenKind, OmokPoint } from '@/types/omok';
import { OmokDefs, StoneBody, StoneShadow, artId } from '../OmokArt';

/**
 * 튜토리얼 그림용 작은 판(정적, 입력 없음) — 큰 판의 일부(기본 9×9)를 잘라 보여 준다. 금수 ✕ 는 설명용으로 직접 준다(판정은 서버 몫).
 * 좌표는 이 그림 안의 칸이다. 돌·판은 큰 판과 같은 그림 조각을 쓴다(D-192) — 연출은 없다.
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
  const id = artId(useId());
  const stones = [
    ...black.map((p) => ({ ...p, color: 'BLACK' as const })),
    ...white.map((p) => ({ ...p, color: 'WHITE' as const })),
  ];
  return (
    <figure className="om-mini">
      <svg className="om-board om-mini-board" viewBox={`-0.5 -0.5 ${size} ${size}`} role="img" aria-label={label}>
        <OmokDefs id={id} />
        <rect className="om-board-bg" x={-0.5} y={-0.5} width={size} height={size} />
        {lines.map((i) => (
          <g key={i}>
            <line className="om-grid" x1={0} y1={i} x2={size - 1} y2={i} />
            <line className="om-grid" x1={i} y1={0} x2={i} y2={size - 1} />
          </g>
        ))}
        {stones.map((p) => (
          <g key={`s${p.x},${p.y}`} transform={`translate(${p.x} ${p.y})`}>
            <StoneShadow id={id} />
          </g>
        ))}
        {stones.map((p, i) => (
          <g
            key={`${p.color}${p.x},${p.y}`}
            className={`om-stone om-stone-${p.color === 'BLACK' ? 'black' : 'white'}`}
            transform={`translate(${p.x} ${p.y})`}
          >
            <StoneBody id={id} color={p.color} seed={i + 3} />
          </g>
        ))}
        {forbidden.map((f) => (
          <g key={`f${f.x},${f.y}`} className="om-forbidden om-forbidden-mine" transform={`translate(${f.x} ${f.y})`}>
            <circle className="om-art om-forbidden-ring" r={0.3} />
            <line x1={-0.2} y1={-0.2} x2={0.2} y2={0.2} />
            <line x1={-0.2} y1={0.2} x2={0.2} y2={-0.2} />
          </g>
        ))}
        {marks.map((m) => (
          <g key={`m${m.x},${m.y}`}>
            <circle className="om-art om-mark-plate" cx={m.x} cy={m.y} r={0.36} />
            <text className="om-mark" x={m.x} y={m.y} dominantBaseline="central" textAnchor="middle">
              {m.text}
            </text>
          </g>
        ))}
        {winLine.length >= 2 && (
          <>
            <line
              className="om-art om-winline-groove"
              x1={winLine[0].x}
              y1={winLine[0].y}
              x2={winLine[winLine.length - 1].x}
              y2={winLine[winLine.length - 1].y}
            />
            <line
              className="om-winline"
              x1={winLine[0].x}
              y1={winLine[0].y}
              x2={winLine[winLine.length - 1].x}
              y2={winLine[winLine.length - 1].y}
            />
          </>
        )}
      </svg>
      <figcaption className="om-mini-caption">{label}</figcaption>
    </figure>
  );
}
