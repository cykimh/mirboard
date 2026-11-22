import { EraseIcon, UndoIcon } from './starSquareArt';
import { usesOf } from './starSquareMath';

interface Props {
  n: number;
  board: number[];
  enabled: boolean;
  canUndo: boolean;
  onNumber: (value: number) => void;
  onErase: () => void;
  onUndo: () => void;
}

/**
 * 숫자판(§13) — 1~N² 단추. 이미 쓴 숫자는 꺼진 별 모양(`data-used`)으로 보이고, 누르면 그 숫자가 고른 칸으로 <b>옮겨진다</b>(한 번씩만
 * 쓰는 퍼즐이라 옮기기가 지웠다 쓰기보다 짧다). 지우기·되돌리기는 숫자 아래 한 줄.
 */
export function NumberPad({ n, board, enabled, canUndo, onNumber, onErase, onUndo }: Props) {
  const uses = usesOf(board);
  const nn = n * n;
  return (
    <div className="sq-pad" data-n={n} style={{ ['--sq-pad-cols' as string]: n === 3 ? 5 : n === 4 ? 6 : 7 }}>
      <div className="sq-pad-numbers" role="group" aria-label="숫자">
        {Array.from({ length: nn }, (_, i) => i + 1).map((v) => {
          const used = uses.has(v);
          return (
            <button
              key={v}
              type="button"
              className={`sq-pad-number${used ? ' sq-pad-used' : ''}`}
              data-used={used || undefined}
              disabled={!enabled}
              aria-label={used ? `${v} (쓰는 중 — 누르면 고른 칸으로 옮겨요)` : `${v}`}
              onClick={() => onNumber(v)}
            >
              {v}
            </button>
          );
        })}
      </div>
      <div className="sq-pad-tools">
        <button type="button" className="sq-pad-tool" disabled={!enabled} onClick={onErase}>
          <EraseIcon />
          지우기
        </button>
        <button type="button" className="sq-pad-tool" disabled={!enabled || !canUndo} onClick={onUndo}>
          <UndoIcon />
          되돌리기
        </button>
      </div>
    </div>
  );
}
