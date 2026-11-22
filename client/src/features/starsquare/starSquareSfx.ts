import type { TableCueCall } from '@/features/sound/tableSfx';
import { lineSums, looksComplete } from './starSquareMath';
import type { StarSquareRoomState } from './starSquareStore';

/** 남의 완성(별똥별)은 내 것보다 작게. */
export const RIVAL_SOLVED_GAIN = 0.45;
/** 별 켜는 소리 뒤, 별자리 선·완성 반짝임이 따라 울리기까지(초) — 같은 순간에 겹쳐 뭉개지지 않게. */
export const LINE_AFTER_PLACE_SEC = 0.07;

/**
 * 별자리 수판 이벤트 → 효과음(D-267 방식 — 실제로 적용한 공개 이벤트에서만, 스냅샷·첫 렌더는 울리지 않는다). 순수 함수.
 * 소리 톤은 공용 `tableSfx`(D-271 — 둥근 사인·마림바풍, 흰 잡음·sawtooth·square 없음, `sfxOut` 출구)를 쓴다.
 *
 * <ul>
 *   <li>`PUZZLE_STARTED` — 밤하늘이 켜지는 세 음(`star.open`). 같은 퍼즐의 늦은 프레임은 조용히.</li>
 *   <li>`SOLVED` — 별똥별(`star.solved`). 내 것은 또렷하게, 남의 것은 작게.</li>
 *   <li>`CLOSING` — 새벽 종 두 번(`star.closing`). 이미 마감 중이거나 내가 다 푼 뒤면 조용히(재촉할 사람이 아니다).</li>
 *   <li>`MATCH_ENDED` — 내 순위로 이김·짐(관전자는 조용히).</li>
 * </ul>
 */
export function starSquareCues(
  envelope: { type: string; payload: unknown },
  before: StarSquareRoomState,
  after: StarSquareRoomState,
): TableCueCall[] {
  const me = after.mySeat;
  switch (envelope.type) {
    case 'PUZZLE_STARTED': {
      if (before.phase === 'PLAYING' && before.puzzleIndex === after.puzzleIndex) return [];
      return [{ cue: 'star.open' }];
    }
    case 'SOLVED': {
      const seat = (envelope.payload as { seat: number }).seat;
      return [{ cue: 'star.solved', gain: seat === me ? 1 : RIVAL_SOLVED_GAIN }];
    }
    case 'CLOSING': {
      if (before.closing || after.mySolved) return [];
      return [{ cue: 'star.closing', gain: me >= 0 ? 1 : 0.6 }];
    }
    case 'MATCH_ENDED': {
      if (me < 0 || !after.result) return [];
      const mine = after.result.placements.find((p) => p.seat === me);
      if (!mine || before.result) return [];
      return [{ cue: mine.rank === 1 && !mine.deserted ? 'match.win' : 'match.lose' }];
    }
    default:
      return [];
  }
}

/** 내 손끝 입력에 필요한 상태 조각 — 스토어 전체를 받지 않아도 되게. */
export type LocalBoardState = Pick<StarSquareRoomState, 'board' | 'boardPuzzle' | 'history' | 'size' | 'clock' | 'seats' | 'phase'>;

const okLines = (grid: readonly number[], n: number) => lineSums(grid, n).filter((l) => l.state === 'ok').length;

/**
 * 내 판의 <b>로컬 입력</b> → 효과음. 스토어의 앞뒤 상태만 보고 정한다(순수 함수 — 게임판의 `useStarSquareFx` 가 구독해서 부른다).
 *
 * <p>내 입력(`place`·`undo`)은 판·되돌리기 기록만 바꾸고 시계·좌석은 그대로 둔다. 스냅샷·퍼즐 시작은 시계를 새로 만들므로 그것으로
 * 가른다 — 다시 받은 판이 바뀌어도 울리지 않는다.
 *
 * <ul>
 *   <li>숫자 넣기·옮기기 — `star.place`, 지우기(채운 칸이 줄어듦) — `star.erase`, 되돌리기 — `star.undo`</li>
 *   <li>맞은 줄이 새로 생김 — `star.line`(늘어난 줄 수만큼 음이 붙는다)</li>
 *   <li>판이 완성처럼 보임 — `star.complete`(줄 소리 대신)</li>
 * </ul>
 */
export function starSquareLocalCues(before: LocalBoardState, after: LocalBoardState): TableCueCall[] {
  if (after.board === before.board) return [];
  if (after.phase !== 'PLAYING' || after.boardPuzzle !== before.boardPuzzle) return [];
  if (after.clock !== before.clock || after.seats !== before.seats) return [];
  const n = after.size;
  if (n <= 0 || after.board.length !== n * n || before.board.length !== n * n) return [];

  const cues: TableCueCall[] = [];
  const undone =
    after.history.length === before.history.length - 1 && before.history[before.history.length - 1] === after.board;
  if (undone) {
    cues.push({ cue: 'star.undo' });
  } else {
    const count = (g: readonly number[]) => g.reduce((acc, v) => acc + (v ? 1 : 0), 0);
    cues.push({ cue: count(after.board) < count(before.board) ? 'star.erase' : 'star.place' });
  }

  const gained = okLines(after.board, n) - okLines(before.board, n);
  if (looksComplete(after.board, n) && !looksComplete(before.board, n)) {
    cues.push({ cue: 'star.complete', delaySec: LINE_AFTER_PLACE_SEC });
  } else if (gained > 0) {
    cues.push({ cue: 'star.line', level: Math.min(3, gained), delaySec: LINE_AFTER_PLACE_SEC });
  }
  return cues;
}
