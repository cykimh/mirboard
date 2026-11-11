import {
  SPECIAL_LABEL,
  SUIT_LABEL,
  type SkullCard,
  type TigressMode,
} from '@/types/skullking';
import { SpecialArt, SuitEmblem } from './SkullCardArt';
import { cabinTheme } from './skullKingTheme';

/** 색상별 표시색 — 검정(으뜸패)은 다른 3색과 확실히 구별돼야 한다 (§7.1). */
const SUIT_STYLE: Record<string, { bg: string; fg: string }> = {
  GREEN: { bg: 'var(--sk-suit-green)', fg: 'var(--sk-suit-green-fg, #0b2a12)' },
  PURPLE: { bg: 'var(--sk-suit-purple)', fg: 'var(--sk-suit-purple-fg, #f4eaff)' },
  YELLOW: { bg: 'var(--sk-suit-yellow)', fg: 'var(--sk-suit-yellow-fg, #2e2405)' },
  BLACK: { bg: 'var(--sk-suit-black)', fg: 'var(--sk-suit-black-fg, #f3f3f6)' },
};

/** D-231 — 겹친 손패의 왼쪽 조각에서도 특수 카드가 읽히게 왼쪽 위 봉랍에 찍는 한 글자(CSS `attr()` — DOM 글자가 아니다). */
const SPECIAL_MARK: Record<string, string> = {
  PIRATE: '해',
  MERMAID: '인',
  SKULL_KING: '왕',
  TIGRESS: '티',
  ESCAPE: '탈',
};

const SPECIAL_GLYPH: Record<string, string> = {
  PIRATE: '🏴‍☠️',
  MERMAID: '🧜',
  SKULL_KING: '💀',
  TIGRESS: '🐯',
  ESCAPE: '🏳️',
};

interface Props {
  card: SkullCard;
  /** 티그리스 선언 — 판정 근거라 카드에 함께 표시한다 (§13-②③⑩). */
  declaredAs?: TigressMode | null;
  selected?: boolean;
  /** follow 의무 힌트로 흐리게 (표시 전용 — 권위는 서버). */
  dimmed?: boolean;
  onClick?: () => void;
  /** D-154 — 손패 카드 더블클릭(바로 내기). */
  onDoubleClick?: () => void;
  /** 트릭 레일용 소형. */
  compact?: boolean;
}

/**
 * 스컬킹 카드 한 장. 티츄 `CardChip` 을 쓰지 않는 이유는 카드 모델이 완전히 다르기
 * 때문이다(4색 1~14 + 특수 5종 vs 4수트 2~14 + 특수 4종). 다만 `.card-chip` CSS 는
 * 크기·모서리·그림자를 그대로 재사용한다.
 */
export function SkullCardChip({
  card,
  declaredAs = null,
  selected = false,
  dimmed = false,
  onClick,
  onDoubleClick,
  compact = false,
}: Props) {
  const isSpecial = card.special !== null;
  const style = isSpecial
    ? { bg: 'var(--sk-special-bg)', fg: 'var(--sk-special-fg)' }
    : SUIT_STYLE[card.suit!];

  const label = isSpecial
    ? SPECIAL_LABEL[card.special!]
    : `${SUIT_LABEL[card.suit!]} ${card.rank}`;
  const aria = declaredAs
    ? `${label} (${declaredAs === 'PIRATE' ? '해적' : '탈출'} 선언)`
    : label;

  const className = [
    'card-chip',
    'sk-card',
    compact ? 'sk-card-compact' : '',
    selected ? 'sk-card-selected' : '',
    dimmed ? 'sk-card-dimmed' : '',
    isSpecial ? 'sk-card-special' : 'sk-card-suit',
    // D-231 — 테마가 종류별 양피지 색·무늬별 테두리를 고른다.
    isSpecial ? `sk-sp-${card.special!.toLowerCase().replace('_', '-')}` : `sk-suit-${card.suit!.toLowerCase()}`,
  ]
    .filter(Boolean)
    .join(' ');

  const mark = isSpecial ? SPECIAL_MARK[card.special!] : undefined;

  const body = (
    <>
      {isSpecial ? (
        <>
          {cabinTheme ? (
            <span className="sk-card-art" aria-hidden>
              <SpecialArt kind={card.special!} />
            </span>
          ) : (
            <span className="sk-card-glyph" aria-hidden>
              {SPECIAL_GLYPH[card.special!]}
            </span>
          )}
          <span className="sk-card-name">{SPECIAL_LABEL[card.special!]}</span>
        </>
      ) : (
        <>
          <span className="sk-card-rank">{card.rank}</span>
          {cabinTheme && (
            <span className="sk-card-art sk-card-emblem" aria-hidden>
              <SuitEmblem suit={card.suit!} />
            </span>
          )}
        </>
      )}
      {declaredAs && (
        <span className="sk-card-declared">
          {declaredAs === 'PIRATE' ? '해적' : '탈출'}
        </span>
      )}
    </>
  );

  if (!onClick) {
    return (
      <span
        className={className}
        style={{ background: style.bg, color: style.fg }}
        aria-label={aria}
        data-mark={mark}
      >
        {body}
      </span>
    );
  }

  return (
    <button
      type="button"
      className={className}
      style={{ background: style.bg, color: style.fg }}
      aria-label={aria}
      aria-pressed={selected}
      data-mark={mark}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
    >
      {body}
    </button>
  );
}
