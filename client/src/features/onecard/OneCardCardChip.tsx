import { SUIT_SYMBOL, cardLabel, rankLabel, type OneCardCard } from '@/types/onecard';

interface Props {
  card: OneCardCard;
  selected?: boolean;
  /** 지금 낼 수 없는 카드 흐리게 (표시 전용 — 판정은 서버). */
  dimmed?: boolean;
  onClick?: () => void;
  /** 튜토리얼·작은 자리용. */
  compact?: boolean;
}

/** 무늬 색 — ♥♦ 빨강, ♠♣ 검정, 조커는 따로. */
function toneOf(card: OneCardCard): string {
  if (card.joker === 'COLOR') return 'oc-card-joker-color';
  if (card.joker === 'BLACK') return 'oc-card-joker-black';
  return card.suit === 'HEART' || card.suit === 'DIAMOND' ? 'oc-card-red' : 'oc-card-black';
}

/**
 * 원카드 카드 한 장 — 이미지 없이 무늬 기호와 숫자로 그린다(D-129). `.card-chip` 공용 CSS 로 크기·모서리·그림자를
 * 받고, 색과 배치만 `.oc-card*` 가 정한다.
 */
export function OneCardCardChip({
  card,
  selected = false,
  dimmed = false,
  onClick,
  compact = false,
}: Props) {
  const className = [
    'card-chip',
    'oc-card',
    toneOf(card),
    compact ? 'oc-card-compact' : '',
    selected ? 'oc-card-selected' : '',
    dimmed ? 'oc-card-dimmed' : '',
  ]
    .filter(Boolean)
    .join(' ');
  const label = cardLabel(card);

  const body = card.joker ? (
    <>
      <span className="oc-card-joker-mark" aria-hidden>
        ★
      </span>
      <span className="oc-card-name" aria-hidden>
        {card.joker === 'COLOR' ? '컬러' : '흑백'}
      </span>
      <span className="oc-card-name" aria-hidden>
        조커
      </span>
    </>
  ) : (
    <>
      <span className="oc-card-rank" aria-hidden>
        {rankLabel(card.rank)}
      </span>
      <span className="oc-card-suit" aria-hidden>
        {SUIT_SYMBOL[card.suit!]}
      </span>
    </>
  );

  if (!onClick) {
    return (
      <span className={className} role="img" aria-label={label}>
        {body}
      </span>
    );
  }
  return (
    <button
      type="button"
      className={className}
      aria-label={label}
      aria-pressed={selected}
      onClick={onClick}
    >
      {body}
    </button>
  );
}
