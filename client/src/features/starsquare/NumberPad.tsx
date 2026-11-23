import { useEffect, useState } from 'react';
import { EraseIcon, ResetIcon, UndoIcon } from './starSquareArt';
import { usesOf } from './starSquareMath';

/** 전체 리셋의 두 번 누르기 — 첫 누름 뒤 이 시간 안에 다시 눌러야 지운다(D-294). */
export const RESET_CONFIRM_MS = 3_000;

interface Props {
  n: number;
  board: number[];
  enabled: boolean;
  canUndo: boolean;
  /** 지울 내 숫자가 있는가 — 없으면 [리셋]이 꺼진다. */
  canReset: boolean;
  onNumber: (value: number) => void;
  onErase: () => void;
  onUndo: () => void;
  /** 전체 리셋 — 두 번째 누름에만 불린다. */
  onReset: () => void;
}

/**
 * 숫자판(§13) — 1~N² 단추. 이미 쓴 숫자는 꺼진 별 모양(`data-used`)으로 보이고, 누르면 그 숫자가 고른 칸으로 <b>옮겨진다</b>(한 번씩만
 * 쓰는 퍼즐이라 옮기기가 지웠다 쓰기보다 짧다). 지우기·되돌리기·리셋은 숫자 아래 한 줄.
 *
 * <p><b>전체 리셋(D-294)</b>은 실수로 누르지 않게 두 번 눌러야 한다 — 첫 누름에 단추가 "한 번 더"로 바뀌고, {@link RESET_CONFIRM_MS} 안에 다시
 * 누르면 지운다. 그 사이 다른 단추를 누르거나 판이 바뀌면 풀린다. 확인 창을 쓰지 않는 것은 창이 판 키보드와 포커스를 끊기 때문이다.
 */
export function NumberPad({ n, board, enabled, canUndo, canReset, onNumber, onErase, onUndo, onReset }: Props) {
  const uses = usesOf(board);
  const nn = n * n;
  const [armed, setArmed] = useState(false);

  // 시간이 지나면 풀린다
  useEffect(() => {
    if (!armed) return;
    const t = window.setTimeout(() => setArmed(false), RESET_CONFIRM_MS);
    return () => window.clearTimeout(t);
  }, [armed]);
  // 판이 바뀌었거나(다른 입력·다시 받은 판) 지울 것이 없어졌으면 풀린다
  useEffect(() => {
    setArmed(false);
  }, [board, canReset]);

  const disarmThen = (fn: () => void) => () => {
    setArmed(false);
    fn();
  };

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
              onClick={disarmThen(() => onNumber(v))}
            >
              {v}
            </button>
          );
        })}
      </div>
      <div className="sq-pad-tools">
        <button type="button" className="sq-pad-tool" disabled={!enabled} onClick={disarmThen(onErase)}>
          <EraseIcon />
          지우기
        </button>
        <button type="button" className="sq-pad-tool" disabled={!enabled || !canUndo} onClick={disarmThen(onUndo)}>
          <UndoIcon />
          되돌리기
        </button>
        <button
          type="button"
          className={`sq-pad-tool sq-pad-reset${armed ? ' sq-pad-reset-armed' : ''}`}
          disabled={!canReset}
          data-armed={armed || undefined}
          aria-label={armed ? '한 번 더 누르면 내가 넣은 숫자를 모두 지워요' : '전체 리셋 — 내가 넣은 숫자를 모두 지워요'}
          onClick={() => {
            if (!armed) {
              setArmed(true);
              return;
            }
            setArmed(false);
            onReset();
          }}
        >
          <ResetIcon />
          {armed ? '한 번 더' : '리셋'}
        </button>
        {/* 첫 누름을 보조기기에 알린다(단추 이름이 바뀌는 것만으로는 읽지 않는 리더가 있다) */}
        <span className="sr-only" role="status">
          {armed ? '한 번 더 누르면 내가 넣은 숫자를 모두 지워요.' : ''}
        </span>
      </div>
    </div>
  );
}
