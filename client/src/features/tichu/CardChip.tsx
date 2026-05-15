import { useState } from 'react';
import type { Card, Suit } from '@/types/tichu';
import { cardAssetSrc, cardLabel } from '@/types/tichu';

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
 * Phase 8F — 카드 표시 컴포넌트. 정적 SVG (`/cards/{suit}-{rank}.svg`) 가
 * 있으면 이미지를, 없으면 (onError) 기존 텍스트 글리프로 graceful fallback.
 * 기본 자산은 `scripts/generate-cards.mjs` 가 생성한 트럼프 풍 SVG.
 */
export function CardChip({ card, selected, onClick }: CardChipProps) {
  const [imageFailed, setImageFailed] = useState(false);
  const color = card.suit ? SUIT_COLOR[card.suit] : '#aaa';
  const glyph = card.suit ? SUIT_GLYPH[card.suit] : '';
  const label = cardLabel(card);
  const src = cardAssetSrc(card);

  return (
    <button
      type="button"
      onClick={onClick}
      className={`card-chip ${selected ? 'selected' : ''} ${imageFailed ? '' : 'card-chip-img'}`}
      style={imageFailed ? { borderColor: color } : undefined}
      aria-pressed={selected}
      aria-label={`${card.suit ?? card.special ?? ''} ${label}`}
    >
      {imageFailed ? (
        <>
          <span style={{ color }}>{glyph}</span>
          <span className="rank">{label}</span>
        </>
      ) : (
        <img
          src={src}
          alt=""
          onError={() => setImageFailed(true)}
          draggable={false}
          style={{ display: 'block' }}
        />
      )}
    </button>
  );
}
