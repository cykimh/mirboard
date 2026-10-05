import {
  SUIT_LABEL,
  SUIT_SYMBOL,
  cardKey,
  type OneCardCard,
  type OneCardSuit,
} from '@/types/onecard';
import { canPlay, drawCount, isSuitChange, sortForDisplay } from './onecardRules';
import { OneCardCardChip } from './OneCardCardChip';

const SUITS: OneCardSuit[] = ['SPADE', 'HEART', 'DIAMOND', 'CLUB'];

interface Props {
  hand: OneCardCard[];
  selectedKey: string | null;
  suitChoice: OneCardSuit | null;
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  myTurn: boolean;
  raceOpen: boolean;
  onSelect: (key: string | null) => void;
  onSuit: (suit: OneCardSuit) => void;
  onPlay: () => void;
  onDraw: () => void;
}

/**
 * 내 손패 + 내기·먹기. 스컬킹처럼 '고르기 → 내기' 2단이다(잘못 낸 카드는 되돌릴 수 없다). 7 을 고르면 지정할 무늬를
 * 함께 고른다. 낼 수 없는 카드는 내 차례에만 흐리게 한다 — 표시 전용이고 판정은 서버다. 경쟁 창이 열린 동안은
 * 서버가 내기·먹기를 거절하므로(`RACE_IN_PROGRESS`) 버튼을 미리 막는다.
 */
export function OneCardHand({
  hand,
  selectedKey,
  suitChoice,
  topCard,
  declaredSuit,
  attackStack,
  myTurn,
  raceOpen,
  onSelect,
  onSuit,
  onPlay,
  onDraw,
}: Props) {
  const selected = selectedKey ? (hand.find((c) => cardKey(c) === selectedKey) ?? null) : null;
  const needSuit = selected !== null && isSuitChange(selected);
  const canAct = myTurn && !raceOpen;
  const canSubmit = canAct && selected !== null && (!needSuit || suitChoice !== null);

  const playLabel = !myTurn
    ? '내 차례 아님'
    : raceOpen
      ? '경쟁 중'
      : selected === null
        ? '카드를 고르세요'
        : needSuit && suitChoice === null
          ? '무늬를 고르세요'
          : '카드 내기';

  return (
    <section className="my-hand oc-hand" aria-label="내 손패">
      <div className="hand-cards oc-hand-cards">
        {sortForDisplay(hand).map((card) => {
          const key = cardKey(card);
          return (
            <OneCardCardChip
              key={key}
              card={card}
              selected={selectedKey === key}
              dimmed={canAct && !canPlay(card, topCard, declaredSuit, attackStack)}
              onClick={() => onSelect(selectedKey === key ? null : key)}
            />
          );
        })}
        {hand.length === 0 && <span className="oc-hand-empty">손패 없음</span>}
      </div>

      <div className="action-bar oc-actions">
        {needSuit && (
          <div className="oc-suits" role="group" aria-label="7 로 지정할 무늬">
            {SUITS.map((suit) => (
              <button
                key={suit}
                type="button"
                className={`oc-suit-opt${suit === 'HEART' || suit === 'DIAMOND' ? ' oc-suit-red' : ''}${
                  suitChoice === suit ? ' selected' : ''
                }`}
                aria-pressed={suitChoice === suit}
                aria-label={SUIT_LABEL[suit]}
                onClick={() => onSuit(suit)}
              >
                {SUIT_SYMBOL[suit]}
              </button>
            ))}
          </div>
        )}
        <button type="button" className="oc-play" disabled={!canSubmit} onClick={onPlay}>
          {playLabel}
        </button>
        <button type="button" className="oc-draw" disabled={!canAct} onClick={onDraw}>
          먹기 ({drawCount(attackStack)}장)
        </button>
      </div>
    </section>
  );
}
