import { useState } from 'react';
import type { Card, Suit } from '@/types/tichu';
import { cardAssetSrc, cardLabel } from '@/types/tichu';
import { useColorblindStore } from '@/features/theme/colorblindStore';

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
 * M1/A1 (D-40/D-45) — 실제 카드 이미지(`/cards/*.svg`)를 렌더하고, 자산 누락/로드
 * 실패 시 onError 로 기존 텍스트 글리프 모드로 폴백하는 이중 모드. 특수카드(개/용/
 * 봉황/마작)는 폴백 시 `cardLabel` 이모지로 표시.
 */
export function CardChip({ card, selected, onClick }: CardChipProps) {
  const [imageFailed, setImageFailed] = useState(false);
  const colorblind = useColorblindStore((s) => s.enabled);
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
      {imageFailed ? (
        <>
          {glyph && <span className="suit-glyph" style={{ color }}>{glyph}</span>}
          <span className="rank" style={{ color }}>{label}</span>
        </>
      ) : (
        <>
          {/* A5 색약 모드 — 슈트를 색 외 글리프로도 식별. */}
          {colorblind && glyph && (
            <span className="cb-suit" style={{ color }} aria-hidden="true">{glyph}</span>
          )}
          <img
            className="card-face"
            src={cardAssetSrc(card)}
            alt={label}
            draggable={false}
            onError={() => setImageFailed(true)}
          />
        </>
      )}
    </button>
  );
}
