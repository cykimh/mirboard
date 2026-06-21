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
 * 카드 칩. 일반(슈트) 카드는 트럼프 핍 일러스트 대신 직관적인 "숫자 + 슈트 문양"으로
 * 렌더한다(좌측 정렬이라 손패가 겹쳐도 왼쪽에서 랭크/슈트가 읽힘). 슈트 글리프가
 * 비색(非色) 단서라 색약 모드(.cb)는 글리프만 강조. 특수카드(개/용/봉황/마작)는 고유
 * 일러스트가 더 직관적이라 SVG 이미지를 유지(로드 실패 시 이모지 폴백).
 */
export function CardChip({ card, selected, onClick }: CardChipProps) {
  const [imageFailed, setImageFailed] = useState(false);
  const colorblind = useColorblindStore((s) => s.enabled);
  const color = card.suit ? SUIT_COLOR[card.suit] : '#aaa';
  const glyph = card.suit ? SUIT_GLYPH[card.suit] : '';
  const label = cardLabel(card);
  const isSuited = card.suit != null && !card.special;

  return (
    <button
      type="button"
      onClick={onClick}
      className={`card-chip ${selected ? 'selected' : ''}`}
      style={{ borderColor: color }}
      aria-pressed={selected}
      aria-label={`${card.suit ?? card.special ?? ''} ${label}`}
    >
      {isSuited ? (
        <span className={`card-simple${colorblind ? ' cb' : ''}`} style={{ color }}>
          <span className="cs-rank">{label}</span>
          <span className="cs-suit" aria-hidden="true">{glyph}</span>
        </span>
      ) : imageFailed ? (
        <span className="rank" style={{ color }}>{label}</span>
      ) : (
        <img
          className="card-face"
          src={cardAssetSrc(card)}
          alt={label}
          draggable={false}
          onError={() => setImageFailed(true)}
        />
      )}
    </button>
  );
}
