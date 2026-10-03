import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { cardLabel, type SkullCard, type TigressMode } from '@/types/skullking';
import { SkullCardChip } from '../SkullCardChip';

/**
 * D-121 — 인터랙티브 핸즈온 '누가 이길까?'. 정답이 **정적 데이터**로 박힌 4문제다 — 클라가
 * 트릭을 판정하지 않으므로 Server-Authoritative 와 무관하다. 근거는 문제별 주석의
 * `docs/rules-skullking.md` §다(룰을 바꾸면 TrickQuiz.test 가 정답을 붙잡고 있다).
 */

interface Played {
  card: SkullCard;
  declaredAs?: TigressMode;
}

interface Question {
  /** 정답 판단에 필요한 전제(누가 무엇이 없었는지). */
  premise: string;
  /** 낸 순서대로. 첫 장이 리드다. */
  cards: Played[];
  answer: number;
  explain: string;
}

const suit = (s: SkullCard['suit'], rank: number): Played => ({
  card: { suit: s, rank, special: null },
});
const special = (k: SkullCard['special'], declaredAs?: TigressMode): Played => ({
  card: { suit: null, rank: 0, special: k },
  declaredAs,
});

const QUESTIONS: Question[] = [
  // §7.1·§11 — 검정은 오프수트여도 이긴다(§13-⑦), 14 보너스는 단순 포함.
  {
    premise: '초록 12로 리드했습니다. 검정 2·노랑 14를 낸 사람은 초록이 없었습니다.',
    cards: [suit('GREEN', 12), suit('GREEN', 3), suit('BLACK', 2), suit('YELLOW', 14)],
    answer: 2,
    explain: '검정은 으뜸패라 리드 수트(초록)를 이깁니다. 예측을 맞혔다면 노랑 14로 +10 보너스.',
  },
  // §6.1·§7.1·§7.2 — 탈출 리드는 리드 수트를 보류, 처음 나온 색상 카드가 정한다.
  {
    premise: '탈출로 리드했습니다. 초록 13을 낸 사람은 보라가 없었습니다.',
    cards: [special('ESCAPE'), suit('PURPLE', 5), suit('PURPLE', 11), suit('GREEN', 13)],
    answer: 2,
    explain:
      '탈출 리드는 리드 수트를 보류하고, 처음 나온 보라 5가 리드 수트를 정합니다. 초록은 리드 수트가 아니라 13이어도 집니다.',
  },
  // §7·§11·§13-⑨ — 3자 예외, 포획 보너스는 관계 기준(40, 90 아님).
  {
    premise: '해적·스컬킹·인어가 한 트릭에 모두 나왔습니다.',
    cards: [special('PIRATE'), special('SKULL_KING'), special('MERMAID')],
    answer: 2,
    explain:
      '셋이 모두 나오면 인어가 이깁니다. 적중 시 인어로 스컬킹을 이긴 +40 뿐이고, 진 해적은 세지 않습니다.',
  },
  // §6.1·§7·§11·§13-⑩ — 해적 선언 티그리스는 해적, 캐릭터 리드는 리드 수트 없음.
  {
    premise: '티그리스를 해적으로 선언해 리드했습니다.',
    cards: [special('TIGRESS', 'PIRATE'), special('MERMAID'), suit('GREEN', 14)],
    answer: 0,
    explain:
      '해적으로 선언한 티그리스는 해적이라 인어를 이깁니다. 캐릭터 리드라 리드 수트가 없어 초록 14도 자유롭게 낸 카드입니다. 적중 시 인어 포획 +20, 초록 14 +10.',
  },
];

/** 칩의 접근명과 같은 형식 — 오답 해설의 "정답은 X" 에 쓴다. */
function labelOf(p: Played): string {
  const label = cardLabel(p.card);
  return p.declaredAs ? `${label} (${p.declaredAs === 'PIRATE' ? '해적' : '탈출'} 선언)` : label;
}

export function TrickQuiz() {
  const [index, setIndex] = useState(0);
  const [picked, setPicked] = useState<number | null>(null);
  const q = QUESTIONS[index];

  const feedback =
    picked === null
      ? '이길 카드를 골라 보세요.'
      : picked === q.answer
        ? `정답! ${q.explain}`
        : `정답은 ${labelOf(q.cards[q.answer])} — ${q.explain}`;

  return (
    <div className="tutorial-practice">
      <p style={{ fontSize: '0.85rem', opacity: 0.7 }}>문제 {index + 1} / {QUESTIONS.length}</p>
      <p>{q.premise}</p>
      {/* padding-top: 선택 칩이 위로 뜨는(translateY -6px) 만큼 본문 스크롤 상단에서 잘리지 않게. */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, justifyContent: 'center', paddingTop: 10 }}>
        {q.cards.map((p, i) => (
          <SkullCardChip
            key={`${index}-${i}`}
            card={p.card}
            declaredAs={p.declaredAs ?? null}
            selected={picked === i}
            onClick={() => setPicked(i)}
            compact
          />
        ))}
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
            setIndex((i) => (i + 1) % QUESTIONS.length);
            setPicked(null);
          }}
        >
          다음 문제
        </Button>
      </div>
    </div>
  );
}
