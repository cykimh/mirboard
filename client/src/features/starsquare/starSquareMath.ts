/**
 * 별자리 수판 화면용 셈(`docs/rules-starsquare.md` §1·§5.1). <b>판정이 아니다</b> — 완성·순위·점수는 서버가 정한다. 여기서는 내 판의 줄 합
 * 표시와 "완성처럼 보이면 바로 보내기"(§7-1)만 한다.
 */

/** 마방진 합 M = N(N²+1)/2 — 3·4·5 → 15·34·65. */
export const magicSumOf = (n: number) => (n * (n * n + 1)) / 2;

export type LineKind = 'row' | 'col' | 'diag' | 'anti';

export interface Line {
  kind: LineKind;
  /** 가로·세로 번호(대각선은 0). */
  index: number;
  cells: number[];
}

const LINES = new Map<number, Line[]>();

/** 2N+2 줄 — 서버 `Lines` 와 같은 순서(가로 0..N−1, 세로, ↘, ↙). */
export function linesOf(n: number): Line[] {
  const cached = LINES.get(n);
  if (cached) return cached;
  const out: Line[] = [];
  for (let i = 0; i < n; i++) out.push({ kind: 'row', index: i, cells: Array.from({ length: n }, (_, j) => i * n + j) });
  for (let i = 0; i < n; i++) out.push({ kind: 'col', index: i, cells: Array.from({ length: n }, (_, j) => j * n + i) });
  out.push({ kind: 'diag', index: 0, cells: Array.from({ length: n }, (_, j) => j * n + j) });
  out.push({ kind: 'anti', index: 0, cells: Array.from({ length: n }, (_, j) => j * n + (n - 1 - j)) });
  LINES.set(n, out);
  return out;
}

/** 줄 합 칩의 상태 — 색만으로 구분하지 않게 모양도 같이 그린다(§3.2). */
export type LineState = 'empty' | 'partial' | 'under' | 'over' | 'ok';

export interface LineSum {
  line: Line;
  sum: number;
  state: LineState;
}

/** 값별 쓰인 횟수(0 제외). */
export function usesOf(grid: readonly number[]): Map<number, number> {
  const uses = new Map<number, number>();
  for (const v of grid) if (v) uses.set(v, (uses.get(v) ?? 0) + 1);
  return uses;
}

/**
 * 줄마다 합과 상태. `ok` 는 서버의 "맞은 줄"과 같은 규칙이다 — 칸이 모두 차 있고, 그 값들이 판에서 한 번만 쓰였고, 합이 M(§5.1).
 * 다 찼지만 중복이 낀 줄은 합이 M 이어도 `under`/`over` 가 아니라 `partial` 로 둔다(맞은 줄이 아님 — 화면이 꼼수를 칭찬하지 않게).
 */
export function lineSums(grid: readonly number[], n: number): LineSum[] {
  const m = magicSumOf(n);
  const uses = usesOf(grid);
  return linesOf(n).map((line) => {
    let sum = 0;
    let full = true;
    let unique = true;
    for (const c of line.cells) {
      const v = grid[c] ?? 0;
      sum += v;
      if (!v) full = false;
      else if ((uses.get(v) ?? 0) > 1) unique = false;
    }
    let state: LineState;
    if (sum === 0) state = 'empty';
    else if (sum > m) state = 'over';
    else if (!full) state = 'partial';
    else if (!unique) state = 'partial';
    else state = sum === m ? 'ok' : 'under';
    return { line, sum, state };
  });
}

/** 서버 진행도와 같은 셈(§5.1) — 채운 칸·맞은 줄. */
export function progressOf(grid: readonly number[], n: number): { filled: number; lines: number } {
  const uses = usesOf(grid);
  const filled = grid.filter((v) => v !== 0 && uses.get(v) === 1).length;
  const lines = lineSums(grid, n).filter((l) => l.state === 'ok').length;
  return { filled, lines };
}

/** 완성처럼 보이는가 — 다 찼고 모든 줄이 맞음. 바로 보내기(§7-1)에만 쓴다(판정은 서버). */
export function looksComplete(grid: readonly number[], n: number): boolean {
  if (grid.length !== n * n) return false;
  const p = progressOf(grid, n);
  return p.filled === n * n && p.lines === 2 * n + 2;
}

/** mm:ss — 음수는 0. */
export function clockText(ms: number): string {
  const s = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
