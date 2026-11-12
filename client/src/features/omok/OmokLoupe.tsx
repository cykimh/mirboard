import { useId, useMemo } from 'react';
import { BOARD_SIZE, type OmokMove, type OmokPoint, type OmokStone } from '@/types/omok';
import { coordName, stonesOf } from './omokRules';
import { OmokDefs, StoneBody, artId } from './OmokArt';

/** 돋보기에 보이는 칸 — 미리보기 둘레 2칸(5×5). */
const R = 2;
const STAR_LINES = [3, 9, 15];

/**
 * 돋보기 (D-193) — 19줄 판은 휴대폰에서 칸이 19px 남짓이라 손가락이 미리보기 돌과 둘레를 가린다. 미리보기 둘레 5×5 칸을 크게 그려
 * 판의 **반대쪽 모서리**(손가락이 없는 쪽)에 띄우고 좌표 글자를 붙인다. 그림만 — 입력은 판이 받는다. 넓은 판에서는 CSS 가 숨긴다
 * (컨테이너 쿼리).
 */
export function OmokLoupe({
  preview,
  moves,
  myColor,
  forbidden,
}: {
  preview: OmokPoint;
  moves: OmokMove[];
  myColor: OmokStone;
  /** 미리보기 자리가 내 금수면 그 종류 문구(붉은 ✕ 로 그린다). */
  forbidden: boolean;
}) {
  const id = artId(useId());
  const stones = useMemo(() => stonesOf(moves), [moves]);
  const near: { x: number; y: number; color: OmokStone }[] = [];
  for (let y = preview.y - R; y <= preview.y + R; y++) {
    for (let x = preview.x - R; x <= preview.x + R; x++) {
      const c = stones.get(`${x},${y}`);
      if (c) near.push({ x, y, color: c });
    }
  }
  const lo = (v: number) => Math.max(0, v - R - 0.5);
  const hi = (v: number) => Math.min(BOARD_SIZE - 1, v + R + 0.5);
  const lines = Array.from({ length: 2 * R + 3 }, (_, i) => i - R - 1);
  const side = `${preview.y < BOARD_SIZE / 2 ? 'bottom' : 'top'}-${preview.x < BOARD_SIZE / 2 ? 'right' : 'left'}`;
  const name = coordName(preview);

  return (
    <figure className={`om-loupe om-loupe-${side}`} aria-hidden="true" data-testid="om-loupe">
      <svg viewBox={`${preview.x - R - 0.5} ${preview.y - R - 0.5} ${2 * R + 1} ${2 * R + 1}`}>
        <OmokDefs id={id} />
        <rect className="om-loupe-bg" x={preview.x - R - 0.5} y={preview.y - R - 0.5} width={2 * R + 1} height={2 * R + 1} />
        {lines.map((d) => {
          const y = preview.y + d;
          const x = preview.x + d;
          return (
            <g key={d}>
              {y >= 0 && y < BOARD_SIZE && (
                <line className="om-grid" x1={lo(preview.x)} y1={y} x2={hi(preview.x)} y2={y} />
              )}
              {x >= 0 && x < BOARD_SIZE && (
                <line className="om-grid" x1={x} y1={lo(preview.y)} x2={x} y2={hi(preview.y)} />
              )}
            </g>
          );
        })}
        {STAR_LINES.flatMap((sy) => STAR_LINES.map((sx) => ({ sx, sy })))
          .filter(({ sx, sy }) => Math.abs(sx - preview.x) <= R && Math.abs(sy - preview.y) <= R)
          .map(({ sx, sy }) => (
            <circle key={`${sx},${sy}`} className="om-star" cx={sx} cy={sy} r={0.09} />
          ))}
        {near.map((s, i) => (
          <g key={`${s.x},${s.y}`} className={`om-stone om-stone-${s.color === 'BLACK' ? 'black' : 'white'}`} transform={`translate(${s.x} ${s.y})`}>
            <StoneBody id={id} color={s.color} seed={i} />
          </g>
        ))}
        <g transform={`translate(${preview.x} ${preview.y})`}>
          {forbidden ? (
            <g className="om-loupe-no">
              <line x1={-0.25} y1={-0.25} x2={0.25} y2={0.25} />
              <line x1={-0.25} y1={0.25} x2={0.25} y2={-0.25} />
            </g>
          ) : (
            <g className="om-loupe-stone">
              <StoneBody id={id} color={myColor} />
            </g>
          )}
          <circle className="om-loupe-ring" r={0.56} />
        </g>
      </svg>
      <figcaption className="om-loupe-label">{name}</figcaption>
    </figure>
  );
}
