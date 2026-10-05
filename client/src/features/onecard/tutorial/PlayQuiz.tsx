import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { SUIT_LABEL, SUIT_SYMBOL, cardLabel, type OneCardCard, type OneCardSuit } from '@/types/onecard';
import { OneCardCardChip } from '../OneCardCardChip';

/**
 * D-129 — '낼 수 있을까?' 연습. 정답은 **정적 데이터**다(클라가 판정하지 않는다). `PlayQuiz.test` 가 문제마다 정답을
 * 클라 규칙 미러(`onecardRules.canPlay`)와 대조하므로 룰을 바꾸면 거기서 먼저 빨개진다. 근거는 문제별 주석의
 * `docs/rules-onecard.md` §다.
 */

export interface PlayQuestion {
  top: OneCardCard;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  candidate: OneCardCard;
  answer: boolean;
  explain: string;
}

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const BLACK_JOKER: OneCardCard = { suit: null, rank: 0, joker: 'BLACK' };

export const PLAY_QUESTIONS: PlayQuestion[] = [
  // §5.2 — 같은 숫자.
  {
    top: c('HEART', 9),
    declaredSuit: null,
    attackStack: 0,
    candidate: c('SPADE', 9),
    answer: true,
    explain: '숫자가 같으면 무늬가 달라도 낼 수 있습니다.',
  },
  // §5.2·§12-17 — 7 은 와일드가 아니다.
  {
    top: c('HEART', 9),
    declaredSuit: null,
    attackStack: 0,
    candidate: c('CLUB', 7),
    answer: false,
    explain: '7 은 무늬를 지정할 뿐 아무 데나 내는 카드가 아닙니다. 무늬나 숫자가 맞아야 합니다.',
  },
  // §5.1 — 7 로 지정된 무늬가 기준이다.
  {
    top: c('HEART', 7),
    declaredSuit: 'CLUB',
    attackStack: 0,
    candidate: c('CLUB', 4),
    answer: true,
    explain: '7 로 지정된 무늬(♣)가 기준 무늬가 됩니다.',
  },
  // §5.2-4 — 맨 위가 조커이고 공격받는 중이 아니면 아무 카드나.
  {
    top: BLACK_JOKER,
    declaredSuit: null,
    attackStack: 0,
    candidate: c('DIAMOND', 4),
    answer: true,
    explain: '맨 위가 조커이고 공격받는 중이 아니면 아무 카드나 낼 수 있습니다.',
  },
  // §6.2 — 다른 숫자로 반격하려면 기준 무늬가 같아야 한다.
  {
    top: c('HEART', 2),
    declaredSuit: null,
    attackStack: 2,
    candidate: c('SPADE', 1),
    answer: false,
    explain: '2 에 다른 숫자(A)로 반격하려면 무늬(♥)가 같아야 합니다. 모든 2, ♥A, 조커는 됩니다.',
  },
  // §6.2 — 조커는 무늬와 무관하고 A 보다 세다.
  {
    top: c('CLUB', 1),
    declaredSuit: null,
    attackStack: 3,
    candidate: BLACK_JOKER,
    answer: true,
    explain: '조커는 A 보다 세고 무늬와 무관해 반격할 수 있습니다. 반격하지 않으면 3장을 먹습니다.',
  },
];

function situation(q: PlayQuestion): string {
  const parts = [`맨 위 ${cardLabel(q.top)}`];
  if (q.declaredSuit) parts.push(`지정 무늬 ${SUIT_SYMBOL[q.declaredSuit]} ${SUIT_LABEL[q.declaredSuit]}`);
  parts.push(q.attackStack > 0 ? `공격받는 중 +${q.attackStack}` : '공격 없음');
  return parts.join(' · ');
}

export function PlayQuiz() {
  const [index, setIndex] = useState(0);
  const [picked, setPicked] = useState<boolean | null>(null);
  const q = PLAY_QUESTIONS[index];

  const feedback =
    picked === null
      ? `${cardLabel(q.candidate)}, 지금 낼 수 있을까요?`
      : picked === q.answer
        ? `정답! ${q.explain}`
        : `아니에요 — ${q.answer ? '낼 수 있습니다' : '낼 수 없습니다'}. ${q.explain}`;

  return (
    <div className="tutorial-practice">
      <p style={{ fontSize: '0.85rem', opacity: 0.7 }}>
        문제 {index + 1} / {PLAY_QUESTIONS.length}
      </p>
      <p>{situation(q)}</p>
      <div style={{ display: 'flex', gap: 16, justifyContent: 'center', alignItems: 'center', margin: '8px 0' }}>
        <OneCardCardChip card={q.top} compact />
        <span aria-hidden>←</span>
        <OneCardCardChip card={q.candidate} compact />
      </div>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
        <Button
          type="button"
          size="sm"
          variant={picked === true ? 'default' : 'outline'}
          aria-pressed={picked === true}
          onClick={() => setPicked(true)}
        >
          낼 수 있다
        </Button>
        <Button
          type="button"
          size="sm"
          variant={picked === false ? 'default' : 'outline'}
          aria-pressed={picked === false}
          onClick={() => setPicked(false)}
        >
          낼 수 없다
        </Button>
      </div>
      <p role="status" aria-live="polite" style={{ marginTop: 12, textAlign: 'center' }}>
        {feedback}
      </p>
      <div style={{ display: 'flex', justifyContent: 'center' }}>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => {
            setIndex((i) => (i + 1) % PLAY_QUESTIONS.length);
            setPicked(null);
          }}
        >
          다음 문제
        </Button>
      </div>
    </div>
  );
}
