import type { Grid } from '@/types/starsquare';
import { lineSums, looksComplete, usesOf, type LineState, type LineSum } from './starSquareMath';

const STATE_MARK: Record<LineState, string> = {
  empty: '',
  partial: '',
  under: '▽',
  over: '△',
  ok: '✦',
};

const STATE_LABEL: Record<LineState, string> = {
  empty: '비었음',
  partial: '채우는 중',
  under: '모자람',
  over: '넘침',
  ok: '맞음',
};

interface Props {
  n: number;
  board: Grid;
  clues: Grid;
  /** 고른 칸(−1 = 없음). */
  selected: number;
  /** 내 판을 고칠 수 있는가 — 아니면 칸을 누를 수 없다(관전·완성·결과 보기). */
  editable: boolean;
  onSelect?: (cell: number) => void;
  /** 판 이름(접근성). */
  label?: string;
  /** 작게 — 결과 비교용 미니 판(합 칩·별자리 선 없이). */
  mini?: boolean;
}

/** 줄의 첫 칸 중심 → 끝 칸 중심(판 좌표 0~N). 별자리 선을 긋는다. */
function lineEnds(sum: LineSum, n: number) {
  const first = sum.line.cells[0];
  const last = sum.line.cells[sum.line.cells.length - 1];
  const cx = (c: number) => (c % n) + 0.5;
  const cy = (c: number) => Math.floor(c / n) + 0.5;
  return { x1: cx(first), y1: cy(first), x2: cx(last), y2: cy(last) };
}

/**
 * 별자리 수판의 판(§13). 칸은 별, 숫자는 별의 밝기. 줄 합 칩은 가로 줄 끝(오른쪽)·세로 줄 끝(아래)·두 대각선(아래 줄)에 놓고, 맞은 줄은
 * 판 위 SVG 층에 <b>별자리 선</b>으로 잇는다(§3.2). 합은 내 판에서 더하기만 한다 — 판정은 서버(§5).
 */
export function StarGrid({ n, board, clues, selected, editable, onSelect, label = '내 판', mini = false }: Props) {
  const sums = lineSums(board, n);
  const uses = usesOf(board);
  const rows = sums.slice(0, n);
  const cols = sums.slice(n, 2 * n);
  const diag = sums[2 * n];
  const anti = sums[2 * n + 1];
  const ok = sums.filter((s) => s.state === 'ok');
  // 화면 전용 — 별자리 선에 이어진 별(칸)과 판 전체가 별자리가 된 모습. 판정이 아니다(판정은 서버).
  const linked = new Set<number>();
  for (const s of ok) for (const c of s.line.cells) linked.add(c);
  const complete = looksComplete(board, n);

  const chip = (s: LineSum, where: string) => (
    <span
      key={`${s.line.kind}-${s.line.index}`}
      className={`sq-sum sq-sum-${s.state} sq-sum-${where}`}
      data-state={s.state}
      aria-label={`${where === 'row' ? `${s.line.index + 1}행` : where === 'col' ? `${s.line.index + 1}열` : where === 'diag' ? '↘ 대각선' : '↙ 대각선'} 합 ${s.sum} ${STATE_LABEL[s.state]}`}
    >
      <span className="sq-sum-value">{s.sum || ''}</span>
      <span className="sq-sum-mark" aria-hidden="true">
        {STATE_MARK[s.state]}
      </span>
    </span>
  );

  return (
    <div
      className={`sq-grid-wrap${mini ? ' sq-grid-mini' : ''}${editable ? ' sq-grid-editable' : ''}${complete ? ' sq-grid-complete' : ''}`}
      style={{ ['--sq-n' as string]: n }}
      data-n={n}
    >
      <div className="sq-grid-frame">
        <div className="sq-grid" role="grid" aria-label={label} aria-rowcount={n} aria-colcount={n}>
          {Array.from({ length: n }, (_, r) => (
            <div key={r} className="sq-row" role="row">
              {Array.from({ length: n }, (_, c) => {
                const i = r * n + c;
                const v = board[i] ?? 0;
                const isClue = clues[i] !== 0;
                const dup = v !== 0 && (uses.get(v) ?? 0) > 1;
                const cls = ['sq-cell'];
                if (isClue) cls.push('sq-cell-clue');
                if (v !== 0) cls.push('sq-cell-lit');
                if (dup) cls.push('sq-cell-dup');
                if (linked.has(i)) cls.push('sq-cell-linked');
                if (!mini && i === selected && editable) cls.push('sq-cell-selected');
                return (
                  <button
                    key={c}
                    type="button"
                    role="gridcell"
                    className={cls.join(' ')}
                    tabIndex={mini ? -1 : i === selected ? 0 : -1}
                    disabled={mini || !editable || isClue}
                    aria-selected={!mini && i === selected && editable}
                    aria-label={`${r + 1}행 ${c + 1}열 ${v ? v : '빈 칸'}${isClue ? ' (단서)' : ''}${dup ? ' (겹침)' : ''}`}
                    data-cell={i}
                    onClick={() => onSelect?.(i)}
                  >
                    <span className="sq-cell-value">{v || ''}</span>
                  </button>
                );
              })}
              {!mini && chip(rows[r], 'row')}
            </div>
          ))}
          {!mini && (
            <div className="sq-row sq-row-sums" role="row">
              {cols.map((s) => chip(s, 'col'))}
              <span className="sq-sum-corner" aria-hidden="true" />
            </div>
          )}
        </div>
        {/* 맞은 줄은 별자리 선으로 잇는다 — 판 좌표(0~N) 위의 SVG 층, 눌림 통과 */}
        {ok.length > 0 && (
          <svg className="sq-constellation" viewBox={`0 0 ${n} ${n}`} aria-hidden="true" preserveAspectRatio="none">
            {ok.map((s) => {
              const e = lineEnds(s, n);
              return (
                <line
                  key={`${s.line.kind}-${s.line.index}`}
                  className={`sq-constellation-line sq-constellation-${s.line.kind}`}
                  x1={e.x1}
                  y1={e.y1}
                  x2={e.x2}
                  y2={e.y2}
                />
              );
            })}
          </svg>
        )}
      </div>
      {!mini && (
        <div className="sq-diag-sums">
          <span className="sq-diag-label" aria-hidden="true">
            ↘
          </span>
          {chip(diag, 'diag')}
          <span className="sq-diag-label" aria-hidden="true">
            ↙
          </span>
          {chip(anti, 'anti')}
        </div>
      )}
    </div>
  );
}
