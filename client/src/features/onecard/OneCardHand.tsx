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
  /** D-154 — 더블클릭으로 바로 낼 카드. 7(무늬 지정)·낼 수 없는 카드·내 차례 아님은 부르지 않는다. */
  onPlayCard?: (card: OneCardCard) => void;
  /** D-160 — 내 차례 강조 색(내 좌석 색). */
  accent?: string;
}

/** D-160 — 이 장수까지는 한 줄. */
export const HAND_ONE_ROW_MAX = 8;

/** D-160 — 손패는 최대 2줄: {@link HAND_ONE_ROW_MAX} 장까지 한 줄, 넘으면 반씩(윗줄이 한 장 더). 줄이 넘치면 CSS 가 겹친다. */
export function handRows<T>(cards: T[]): T[][] {
  if (cards.length <= HAND_ONE_ROW_MAX) return [cards];
  const top = Math.ceil(cards.length / 2);
  return [cards.slice(0, top), cards.slice(top)];
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
  onPlayCard,
  accent,
}: Props) {
  const selected = selectedKey ? (hand.find((c) => cardKey(c) === selectedKey) ?? null) : null;
  const needSuit = selected !== null && isSuitChange(selected);
  const canAct = myTurn && !raceOpen;
  const canSubmit = canAct && selected !== null && (!needSuit || suitChoice !== null);

  // 경쟁 중에는 서버 불변식상 차례 좌석이 없다(turnSeat −1) — 그래서 `myTurn` 이 아니라 `raceOpen` 을 먼저 봐야 "경쟁 중"이 뜬다.
  const playLabel = raceOpen
    ? '경쟁 중'
    : !myTurn
      ? '내 차례 아님'
      : selected === null
        ? '카드를 고르세요'
        : needSuit && suitChoice === null
          ? '무늬를 고르세요'
          : '카드 내기';

  return (
    <section
      className={`my-hand oc-hand${myTurn ? ' oc-hand-myturn' : ''}`}
      style={accent ? { ['--oc-accent' as string]: accent } : undefined}
      aria-label="내 손패"
    >
      <div className="hand-cards oc-hand-cards">
        {handRows(sortForDisplay(hand)).map((row, i) => (
          <div key={i} className="oc-hand-row">
            {row.map((card) => {
              const key = cardKey(card);
              return (
                <span key={key} className="oc-hand-slot">
                  <OneCardCardChip
                    card={card}
                    selected={selectedKey === key}
                    dimmed={canAct && !canPlay(card, topCard, declaredSuit, attackStack)}
                    onClick={() => onSelect(selectedKey === key ? null : key)}
                    onDoubleClick={() => {
                      // 더블클릭 앞의 두 번 누름이 고르기를 켰다 껐다 하므로 결과와 상관없이 다시 고른다.
                      onSelect(key);
                      if (
                        onPlayCard &&
                        canAct &&
                        !isSuitChange(card) &&
                        canPlay(card, topCard, declaredSuit, attackStack)
                      ) {
                        onPlayCard(card);
                      }
                    }}
                  />
                </span>
              );
            })}
          </div>
        ))}
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
