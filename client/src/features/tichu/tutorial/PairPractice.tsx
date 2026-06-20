import { useState } from 'react';
import type { Card } from '@/types/tichu';
import { CardChip } from '@/features/tichu/CardChip';

/** A2 — 인터랙티브 핸즈온: 같은 숫자 2장으로 페어 만들기. */
const DEMO: Card[] = [
  { suit: 'JADE', rank: 5, special: null },
  { suit: 'STAR', rank: 5, special: null },
  { suit: 'SWORD', rank: 9, special: null },
  { suit: 'PAGODA', rank: 13, special: null },
];

export function PairPractice() {
  const [selected, setSelected] = useState<number[]>([]);

  const toggle = (i: number) =>
    setSelected((prev) =>
      prev.includes(i)
        ? prev.filter((x) => x !== i)
        : prev.length < 2
          ? [...prev, i]
          : prev,
    );

  const isPair =
    selected.length === 2 && DEMO[selected[0]].rank === DEMO[selected[1]].rank;

  return (
    <div className="tutorial-practice">
      <div className="tutorial-cards" style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
        {DEMO.map((c, i) => (
          <CardChip key={i} card={c} selected={selected.includes(i)} onClick={() => toggle(i)} />
        ))}
      </div>
      <p role="status" aria-live="polite" style={{ marginTop: 12, textAlign: 'center' }}>
        {isPair ? '페어 완성! 🎉 같은 숫자 2장이 페어입니다.' : '같은 숫자 카드 2장을 골라보세요.'}
      </p>
    </div>
  );
}
