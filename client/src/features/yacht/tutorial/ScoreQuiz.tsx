import { useState } from 'react';
import type { Category } from '@/types/yacht';
import { CATEGORY_LABEL, DIE_FACE } from '../yachtLabels';

interface Question {
  dice: [number, number, number, number, number];
  options: { category: Category; points: number }[];
  best: Category;
  why: string;
}

/** 튜토리얼 퀴즈 — "어디에 넣을까?" 점수는 룰 §5 표대로 미리 적어 둔 값이다(클라는 점수를 계산하지 않는다). */
export const QUESTIONS: Question[] = [
  {
    dice: [3, 3, 3, 5, 5],
    options: [
      { category: 'THREES', points: 9 },
      { category: 'FULL_HOUSE', points: 19 },
      { category: 'CHOICE', points: 19 },
    ],
    best: 'FULL_HOUSE',
    why: '풀 하우스는 이런 모양에서만 점수가 납니다. 초이스는 아무 때나 쓸 수 있으니 아껴 두세요.',
  },
  {
    dice: [2, 3, 4, 5, 6],
    options: [
      { category: 'SMALL_STRAIGHT', points: 15 },
      { category: 'LARGE_STRAIGHT', points: 30 },
      { category: 'CHOICE', points: 20 },
    ],
    best: 'LARGE_STRAIGHT',
    why: '연속 5개는 라지 스트레이트 30점 — 스몰(15점)보다 두 배입니다.',
  },
  {
    dice: [4, 4, 4, 4, 4],
    options: [
      { category: 'FOURS', points: 20 },
      { category: 'FOUR_OF_A_KIND', points: 20 },
      { category: 'YACHT', points: 50 },
    ],
    best: 'YACHT',
    why: '5개가 모두 같으면 요트 50점 — 게임에서 가장 큰 칸입니다.',
  },
];

export function ScoreQuiz() {
  const [index, setIndex] = useState(0);
  const [picked, setPicked] = useState<Category | null>(null);
  const q = QUESTIONS[index];
  return (
    <div className="yt-quiz">
      <p className="yt-quiz-dice" aria-label={`주사위 ${q.dice.join(', ')}`}>
        {q.dice.map((d, i) => (
          <span key={i} aria-hidden="true">
            {DIE_FACE[d]}
          </span>
        ))}
      </p>
      <div className="yt-quiz-options" role="group" aria-label="어디에 넣을까요?">
        {q.options.map((o) => (
          <button
            type="button"
            key={o.category}
            className={`yt-quiz-option${picked === o.category ? (o.category === q.best ? ' yt-quiz-right' : ' yt-quiz-wrong') : ''}`}
            onClick={() => setPicked(o.category)}
          >
            {CATEGORY_LABEL[o.category]} {o.points}점
          </button>
        ))}
      </div>
      <p className="yt-quiz-feedback" aria-live="polite">
        {picked === null ? '' : picked === q.best ? `정답! ${q.why}` : `아쉬워요. ${q.why}`}
      </p>
      {picked !== null && index < QUESTIONS.length - 1 && (
        <button
          type="button"
          className="yt-quiz-next"
          onClick={() => {
            setIndex(index + 1);
            setPicked(null);
          }}
        >
          다음 문제
        </button>
      )}
    </div>
  );
}
