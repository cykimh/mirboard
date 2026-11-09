import { useCallback, useMemo, useRef } from 'react';
import { BOARD_SIZE, type OmokForbiddenPoint, type OmokMove, type OmokPoint, type OmokStone } from '@/types/omok';
import { coordName, key, stonesOf } from './omokRules';
import { useStonePicker } from './useStonePicker';

const STARS: OmokPoint[] = [
  { x: 3, y: 3 },
  { x: 11, y: 3 },
  { x: 7, y: 7 },
  { x: 3, y: 11 },
  { x: 11, y: 11 },
];
const LINES = Array.from({ length: BOARD_SIZE }, (_, i) => i);

interface Props {
  moves: OmokMove[];
  /** 흑 차례의 금수 자리(서버 계산). 흑 차례가 아니면 빈 목록. */
  forbidden: OmokForbiddenPoint[];
  /** 금수 ✕ 를 붉게(내가 흑이고 내 차례) 그릴지. */
  forbiddenIsMine: boolean;
  preview: OmokPoint | null;
  pending: OmokPoint | null;
  /** 미리보기·흐린 돌의 색 — 내 색. */
  myColor: OmokStone | null;
  winLine: OmokPoint[];
  showNumbers: boolean;
  /** 둘 수 있는가 — 내 차례·연결됨·진행 중. */
  enabled: boolean;
  singleClick: boolean;
  setPreview: (p: OmokPoint | null) => void;
  onConfirm: (p: OmokPoint) => void;
}

/**
 * 오목 판 (D-180). SVG 하나 — 교차점 225개를 DOM 요소로 만들지 않고 누른 화면 좌표를 가장 가까운 교차점으로 맞춘다. 좌표계는 교차점
 * 간격 1, (0,0) 교차점이 원점이고 판 가장자리에 반 칸 여백이 있다(`viewBox` −0.5 … 14.5).
 *
 * <p>두 단계 확정: 누르면 미리보기(반투명 돌 + 그 줄·열 안내선), 미리보기를 다시 누르면 확정. 끌면 미리보기가 따라온다
 * ({@link useStonePicker}). 키보드: 화살표로 미리보기를 옮기고 Enter·Space 로 확정한다.
 */
export function OmokBoard({
  moves,
  forbidden,
  forbiddenIsMine,
  preview,
  pending,
  myColor,
  winLine,
  showNumbers,
  enabled,
  singleClick,
  setPreview,
  onConfirm,
}: Props) {
  const svgRef = useRef<SVGSVGElement | null>(null);
  const stones = useMemo(() => stonesOf(moves), [moves]);
  const last = moves.length > 0 ? moves[moves.length - 1] : null;

  const isEmpty = useCallback(
    (p: OmokPoint) => !stones.has(key(p)) && !(pending && pending.x === p.x && pending.y === p.y),
    [stones, pending],
  );
  const toBoard = useCallback((clientX: number, clientY: number) => {
    const svg = svgRef.current;
    if (!svg) return null;
    const r = svg.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return null;
    return {
      fx: ((clientX - r.left) / r.width) * BOARD_SIZE - 0.5,
      fy: ((clientY - r.top) / r.height) * BOARD_SIZE - 0.5,
      cell: r.width / BOARD_SIZE,
    };
  }, []);
  const picker = useStonePicker({ enabled, preview, isEmpty, setPreview, confirm: onConfirm, toBoard, singleClick });

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (!enabled) return;
    const at = preview ?? last ?? { x: 7, y: 7 };
    const delta: Record<string, [number, number]> = {
      ArrowLeft: [-1, 0],
      ArrowRight: [1, 0],
      ArrowUp: [0, -1],
      ArrowDown: [0, 1],
    };
    if (delta[e.key]) {
      e.preventDefault();
      const [dx, dy] = delta[e.key];
      const next = {
        x: Math.min(BOARD_SIZE - 1, Math.max(0, at.x + dx)),
        y: Math.min(BOARD_SIZE - 1, Math.max(0, at.y + dy)),
      };
      setPreview(next);
    } else if ((e.key === 'Enter' || e.key === ' ') && preview && isEmpty(preview)) {
      e.preventDefault();
      onConfirm(preview);
    } else if (e.key === 'Escape') {
      setPreview(null);
    }
  };

  const showPreview = enabled && preview !== null && isEmpty(preview);
  const label = `오목 판 — ${moves.length}수${last ? `, 마지막 ${coordName(last)}` : ''}${
    showPreview && preview ? `, 미리보기 ${coordName(preview)}` : ''
  }`;

  return (
    <svg
      ref={svgRef}
      className={`om-board${enabled ? ' om-board-active' : ''}`}
      viewBox={`-0.5 -0.5 ${BOARD_SIZE} ${BOARD_SIZE}`}
      role="img"
      aria-label={label}
      tabIndex={enabled ? 0 : -1}
      onKeyDown={onKeyDown}
      onPointerDown={picker.onPointerDown}
      onPointerMove={picker.onPointerMove}
      onPointerUp={picker.onPointerUp}
      onPointerCancel={picker.onPointerCancel}
    >
      <rect className="om-board-bg" x={-0.5} y={-0.5} width={BOARD_SIZE} height={BOARD_SIZE} />
      {LINES.map((i) => (
        <g key={i}>
          <line className="om-grid" x1={0} y1={i} x2={BOARD_SIZE - 1} y2={i} />
          <line className="om-grid" x1={i} y1={0} x2={i} y2={BOARD_SIZE - 1} />
        </g>
      ))}
      {STARS.map((p) => (
        <circle key={key(p)} className="om-star" cx={p.x} cy={p.y} r={0.09} />
      ))}

      {showPreview && preview && (
        <g className="om-guide" aria-hidden="true">
          <line x1={-0.5} y1={preview.y} x2={BOARD_SIZE - 0.5} y2={preview.y} />
          <line x1={preview.x} y1={-0.5} x2={preview.x} y2={BOARD_SIZE - 0.5} />
        </g>
      )}

      {forbidden.map((f) => (
        <g
          key={`f${key(f)}`}
          className={`om-forbidden${forbiddenIsMine ? ' om-forbidden-mine' : ''}`}
          data-kind={f.kind}
          aria-hidden="true"
        >
          <line x1={f.x - 0.18} y1={f.y - 0.18} x2={f.x + 0.18} y2={f.y + 0.18} />
          <line x1={f.x - 0.18} y1={f.y + 0.18} x2={f.x + 0.18} y2={f.y - 0.18} />
        </g>
      ))}

      {moves.map((m, i) => {
        const color = i % 2 === 0 ? 'BLACK' : 'WHITE';
        return (
          <g key={key(m)}>
            <circle
              className={`om-stone om-stone-${color === 'BLACK' ? 'black' : 'white'}`}
              cx={m.x}
              cy={m.y}
              r={0.44}
              data-testid="om-stone"
              data-color={color}
            />
          </g>
        );
      })}

      {last && !showNumbers && (
        <circle
          className={`om-last om-last-on-${moves.length % 2 === 1 ? 'black' : 'white'}`}
          cx={last.x}
          cy={last.y}
          r={0.12}
          aria-hidden="true"
        />
      )}

      {winLine.length >= 2 && (
        <line
          className="om-winline"
          x1={winLine[0].x}
          y1={winLine[0].y}
          x2={winLine[winLine.length - 1].x}
          y2={winLine[winLine.length - 1].y}
          aria-hidden="true"
        />
      )}

      {/* 수 번호는 승리 줄 위에 — 줄이 흑돌 위 번호를 가리지 않게. */}
      {showNumbers &&
        moves.map((m, i) => (
          <text
            key={`n${key(m)}`}
            className={`om-num om-num-${i % 2 === 0 ? 'black' : 'white'}`}
            x={m.x}
            y={m.y}
            dominantBaseline="central"
            textAnchor="middle"
          >
            {i + 1}
          </text>
        ))}

      {pending && myColor && (
        <circle
          className={`om-stone om-stone-${myColor === 'BLACK' ? 'black' : 'white'} om-pending`}
          cx={pending.x}
          cy={pending.y}
          r={0.44}
          aria-hidden="true"
        />
      )}
      {showPreview && preview && myColor && (
        <circle
          className={`om-stone om-stone-${myColor === 'BLACK' ? 'black' : 'white'} om-preview`}
          cx={preview.x}
          cy={preview.y}
          r={0.44}
          data-testid="om-preview"
          aria-hidden="true"
        />
      )}
    </svg>
  );
}
