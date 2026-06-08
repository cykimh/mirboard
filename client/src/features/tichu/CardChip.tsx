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

/**
 * Phase 13(#3) — 트럼프 SVG 대신 문양 + 숫자만 표시 (이전 텍스트 글리프 스타일).
 * 특수카드(개/용/봉황/마작)는 `cardLabel` 이모지로. 실제 카드 assets 는 추후
 * 일괄 교체 예정 — 그때 다시 이미지 모드 도입.
 */
export function CardChip({ card, selected, onClick }: CardChipProps) {
  const color = card.suit ? SUIT_COLOR[card.suit] : '#aaa';
  const glyph = card.suit ? SUIT_GLYPH[card.suit] : '';
  const label = cardLabel(card);

  return (
    <button
      type="button"
      onClick={onClick}
      className={`card-chip ${selected ? 'selected' : ''}`}
      style={{ borderColor: color }}
      aria-pressed={selected}
      aria-label={`${card.suit ?? card.special ?? ''} ${label}`}
    >
      {glyph && <span className="suit-glyph" style={{ color }}>{glyph}</span>}
      <span className="rank" style={{ color }}>{label}</span>
    </button>
  );
}
