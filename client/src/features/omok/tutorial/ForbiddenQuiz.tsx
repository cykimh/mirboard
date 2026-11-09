import { useState } from 'react';
import { MiniBoard } from './MiniBoard';

/**
 * "어디가 금수일까?" — 렌주 흑 차례 그림에서 보기 셋 중 금수 자리를 고른다(로컬 전용, 기록 없음). 그림은 `docs/rules-omok.md` §6.4
 * 고정 국면 B1(가로·세로 열린 3 이 만나는 33)과 같은 모양이다.
 */
const CHOICES = [
  { id: 'A', x: 4, y: 4, correct: true, why: '가로 3 과 세로 3 이 동시에 열린 3 이 돼요 — 33 금수예요.' },
  { id: 'B', x: 5, y: 4, correct: false, why: '가로로 띈 3 하나만 생겨요(세로는 아무것도 없어요) — 둘 수 있어요.' },
  { id: 'C', x: 4, y: 1, correct: false, why: '세로 3 하나만 생겨요 — 둘 수 있어요.' },
] as const;

export function ForbiddenQuiz() {
  const [picked, setPicked] = useState<string | null>(null);
  const choice = CHOICES.find((c) => c.id === picked) ?? null;
  return (
    <div className="om-quiz">
      <MiniBoard
        black={[
          { x: 4, y: 2 },
          { x: 4, y: 3 },
          { x: 2, y: 4 },
          { x: 3, y: 4 },
        ]}
        marks={CHOICES.map((c) => ({ x: c.x, y: c.y, text: c.id }))}
        label="흑 차례 — A·B·C 중 금수는?"
      />
      <div className="om-quiz-choices" role="group" aria-label="보기">
        {CHOICES.map((c) => (
          <button
            key={c.id}
            type="button"
            className={`om-badge${picked === c.id ? (c.correct ? ' om-quiz-right' : ' om-quiz-wrong') : ''}`}
            onClick={() => setPicked(c.id)}
            aria-pressed={picked === c.id}
          >
            {c.id}
          </button>
        ))}
      </div>
      <p className="om-quiz-answer" aria-live="polite">
        {choice ? `${choice.correct ? '정답! ' : '아니에요. '}${choice.why}` : '보기를 하나 골라 보세요.'}
      </p>
    </div>
  );
}
