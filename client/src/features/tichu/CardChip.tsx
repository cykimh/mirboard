import { useState } from 'react';
import type { Card, Special, Suit } from '@/types/tichu';
import { cardAssetSrc, cardLabel } from '@/types/tichu';
import { CardBackArt, SpecialArt, SuitGlyph } from './tichuArt';
import { lacquerTheme, tichuCardClass } from './tichuTheme';

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

/** 옻칠 테마 — 특수 카드의 모서리 표기(겹친 손패에서 왼쪽 띠만 보여도 무엇인지 읽힌다). */
const SPECIAL_INDEX: Record<Special, string> = {
  MAHJONG: '1',
  DOG: '개',
  PHOENIX: '봉',
  DRAGON: '용',
};

interface CardChipProps {
  card: Card;
  selected?: boolean;
  onClick?: () => void;
}

/**
 * 옻칠 테마(D-241) 카드 앞면. 일반 카드는 상아 바탕에 왼쪽 위 모서리 표기(숫자 + 작은 무늬)와 가운데 큰 무늬,
 * 특수 카드는 검은 칠기 바탕에 직접 그린 그림 + 모서리 한 글자. 크기는 카드 폭을 따라간다(CSS 컨테이너 단위).
 */
function LacquerFace({ card }: { card: Card }) {
  if (card.special) {
    return (
      <span className={`tc-face tc-face-special tc-sp-${card.special.toLowerCase()}`}>
        <SpecialArt special={card.special} className="tc-art" />
        <span className="tc-index">
          <span className="tc-rank">{SPECIAL_INDEX[card.special]}</span>
        </span>
      </span>
    );
  }
  if (!card.suit) return <span className="tc-face" />;
  const label = cardLabel(card);
  return (
    <span className={`tc-face tc-suit-${card.suit.toLowerCase()}`}>
      <span className="tc-index">
        <span className={`tc-rank${label.length > 1 ? ' tc-rank-wide' : ''}`}>{label}</span>
        <SuitGlyph suit={card.suit} className="tc-pip" />
      </span>
      <SuitGlyph suit={card.suit} className="tc-emblem" />
    </span>
  );
}

/**
 * 카드 칩. 일반(슈트) 카드는 트럼프 핍 일러스트 대신 직관적인 "숫자 + 슈트 문양"으로
 * 렌더한다(좌측 정렬이라 손패가 겹쳐도 왼쪽에서 랭크/슈트가 읽힘). 슈트 글리프가
 * 비색(非色) 단서다(D-146 — 색약 모드는 없앴다). 특수카드(개/용/봉황/마작)는 고유
 * 일러스트가 더 직관적이라 SVG 이미지를 유지(로드 실패 시 이모지 폴백).
 *
 * D-241 — 옻칠 테마(`tichuTheme.TICHU_THEME`)면 앞면을 {@link LacquerFace} 로 그린다. 버튼·선택·aria 는 같다.
 */
export function CardChip({ card, selected, onClick }: CardChipProps) {
  const [imageFailed, setImageFailed] = useState(false);
  const color = card.suit ? SUIT_COLOR[card.suit] : '#aaa';
  const glyph = card.suit ? SUIT_GLYPH[card.suit] : '';
  const label = cardLabel(card);
  const isSuited = card.suit != null && !card.special;

  if (lacquerTheme) {
    return (
      <button
        type="button"
        onClick={onClick}
        className={`card-chip${tichuCardClass} ${selected ? 'selected' : ''}`}
        aria-pressed={selected}
        aria-label={`${card.suit ?? card.special ?? ''} ${label}`}
      >
        <LacquerFace card={card} />
      </button>
    );
  }

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
        <span className="card-simple" style={{ color }}>
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

/**
 * 연출용 카드(D-241) — 버튼이 아닌 그림만. 경기장 연출 층(쓸려 가는 트릭 더미)처럼 누를 수 없는 자리에 쓴다.
 * 옻칠 테마 전용이다(연출 층이 테마일 때만 그려진다).
 */
export function CardFaceArt({ card }: { card: Card }) {
  return (
    <span className={`card-chip${tichuCardClass} tc-card-static`}>
      <LacquerFace card={card} />
    </span>
  );
}

/** 카드 뒷면(D-241) — 붉은 옻칠 + 금박 문양. `className` 으로 크기를 정한다. */
export function CardBack({ className = '' }: { className?: string }) {
  return (
    <span className={`tc-back ${className}`.trim()} aria-hidden="true">
      <CardBackArt className="tc-back-art" />
    </span>
  );
}
