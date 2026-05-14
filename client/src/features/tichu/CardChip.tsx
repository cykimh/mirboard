import type { Card, Suit } from '@/types/tichu';
import { cardLabel } from '@/types/tichu';

const SUIT_COLOR: Record<Suit, string> = {
  JADE: '#3fb979',
  SWORD: '#5b8def',
  PAGODA: '#e09246',
  STAR: '#e85d75',
};

const SUIT_GLYPH: Record<Suit, string> = {
  JADE: '◆',
  SWORD: '⚔',
  PAGODA: '⛩',
  STAR: '★',
};

interface CardChipProps {
  card: Card;
  selected?: boolean;
  onClick?: () => void;
}

export function CardChip({ card, selected, onClick }: CardChipProps) {
  const color = card.suit ? SUIT_COLOR[card.suit] : '#aaa';
  const glyph = card.suit ? SUIT_GLYPH[card.suit] : '';
  return (
    <button
      type="button"
      onClick={onClick}
      className={`card-chip ${selected ? 'selected' : ''}`}
      style={{ borderColor: color }}
      aria-pressed={selected}
    >
      <span style={{ color }}>{glyph}</span>
      <span className="rank">{cardLabel(card)}</span>
    </button>
  );
}
