import { SkullCardChip } from './SkullCardChip';
import { isLegalPlayHint } from './seatLayout';
import {
  type SkullCard,
  type SkullSuit,
  type TigressMode,
} from '@/types/skullking';

interface Props {
  hand: SkullCard[];
  selectedIndex: number | null;
  tigressDeclaration: TigressMode | null;
  leadSuit: SkullSuit | null;
  myTurn: boolean;
  onSelect: (index: number | null) => void;
  onDeclare: (mode: TigressMode | null) => void;
  onPlay: () => void;
}

/**
 * 내 손패 + 제출 액션. **인덱스로 선택**하는 것이 요점이다 — 중복 특수 카드(해적 5장·탈출
 * 5장·인어 2장)가 손패에 함께 올 수 있어 값으로 고르면 React key 가 중복되고 어느 장을
 * 골랐는지 표시할 수 없다. 서버는 카드를 값으로 검증하므로(교환 가능) 인덱스 선택이
 * "선택한 장 ≠ 서버가 뺀 장" 사고를 만들지는 않는다.
 *
 * <p>비-티그리스 카드도 '선택 → 제출' 2단인 이유는 오조작 방지다 — 한 번 클릭으로 바로
 * 나가면 잘못 낸 카드를 되돌릴 방법이 없다.
 */
export function SkullHandPanel({
  hand,
  selectedIndex,
  tigressDeclaration,
  leadSuit,
  myTurn,
  onSelect,
  onDeclare,
  onPlay,
}: Props) {
  const selected = selectedIndex !== null ? hand[selectedIndex] : null;
  const isTigress = selected?.special === 'TIGRESS';
  const canPlay =
    myTurn && selected !== null && (!isTigress || tigressDeclaration !== null);

  return (
    <section className="my-hand sk-hand" aria-label="내 손패">
      <div className="hand-cards overlap sk-hand-cards">
        {hand.map((card, i) => (
          <SkullCardChip
            key={i}
            card={card}
            selected={selectedIndex === i}
            dimmed={myTurn && !isLegalPlayHint(card, hand, leadSuit)}
            onClick={() => onSelect(selectedIndex === i ? null : i)}
          />
        ))}
        {hand.length === 0 && <span className="sk-hand-empty">손패 없음</span>}
      </div>

      <div className="action-bar sk-actions">
        {isTigress && (
          <div className="sk-tigress" role="group" aria-label="티그리스 선언">
            <span className="sk-tigress-label">티그리스로 낼 정체:</span>
            <button
              type="button"
              className={`sk-tigress-opt${tigressDeclaration === 'PIRATE' ? ' selected' : ''}`}
              aria-pressed={tigressDeclaration === 'PIRATE'}
              onClick={() => onDeclare('PIRATE')}
            >
              해적
            </button>
            <button
              type="button"
              className={`sk-tigress-opt${tigressDeclaration === 'ESCAPE' ? ' selected' : ''}`}
              aria-pressed={tigressDeclaration === 'ESCAPE'}
              onClick={() => onDeclare('ESCAPE')}
            >
              탈출
            </button>
          </div>
        )}

        <button
          type="button"
          className="sk-play"
          disabled={!canPlay}
          onClick={onPlay}
        >
          {!myTurn
            ? '내 차례 아님'
            : selected === null
              ? '카드를 고르세요'
              : isTigress && tigressDeclaration === null
                ? '해적/탈출을 선언하세요'
                : '카드 제출'}
        </button>
      </div>
    </section>
  );
}
