import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { OneCardCard, OneCardSuit } from '@/types/onecard';
import { OneCardCardChip } from './OneCardCardChip';
import { OneCardFace, SUIT_CLASS, SUIT_PATH } from './OneCardFace';
import { OneCardFxLayer, OneCardParty } from './OneCardFx';
import { ONE_CARD_THEME, ONE_CARD_TOKENS_CLASS, neonTheme, oneCardThemeClass } from './oneCardTheme';
import { FX_LIFETIME_MS, attackLevel, specialEffectOf, type OneCardFxItem } from './useOneCardFx';

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const joker = (kind: 'BLACK' | 'COLOR'): OneCardCard => ({ suit: null, rank: 0, joker: kind });

/** D-260 — 네온 파티 테마 층·그림 면·연출 조각. 실제 모습(CSS)은 `onecardCssNamespace.test` 가 원문으로 본다. */
describe('원카드 테마 층 (D-260)', () => {
  it('테마 클래스는 상수 하나에서 나온다 — 포털 토큰 클래스는 늘 oc-tokens 로 시작한다', () => {
    expect(['neon', 'classic']).toContain(ONE_CARD_THEME);
    expect(oneCardThemeClass).toBe(neonTheme ? ' oc-theme-neon' : '');
    expect(ONE_CARD_TOKENS_CLASS).toBe(`oc-tokens${oneCardThemeClass}`);
  });

  it('칩은 무늬 클래스를 달고, 이름은 그림이 아니라 aria-label 이 읽는다', () => {
    const { container } = render(<OneCardCardChip card={c('HEART', 12)} />);
    const chip = container.querySelector('.oc-card')!;
    expect(chip).toHaveClass('oc-s-heart');
    expect(chip).toHaveAttribute('aria-label', '하트 Q');
    if (neonTheme) {
      expect(chip.querySelector('svg.oc-face')).toHaveAttribute('aria-hidden', 'true');
    } else {
      expect(chip.querySelector('.oc-card-rank')).toHaveTextContent('Q');
    }
  });
});

describe('OneCardFace — 직접 그린 그림 면', () => {
  it('무늬 넷의 그림과 클래스가 모두 있다', () => {
    for (const suit of ['SPADE', 'HEART', 'DIAMOND', 'CLUB'] as const) {
      expect(SUIT_PATH[suit]).toMatch(/^M/);
      expect(SUIT_CLASS[suit]).toMatch(/^oc-s-/);
    }
  });

  it('54장이 모두 62×88 한 칸에 그려진다 — 무늬 카드는 왼쪽 위에 숫자 인덱스', () => {
    for (const suit of ['SPADE', 'HEART', 'DIAMOND', 'CLUB'] as const) {
      for (let rank = 1; rank <= 13; rank++) {
        const { container, unmount } = render(<OneCardFace card={c(suit, rank)} />);
        const svg = container.querySelector('svg.oc-face')!;
        expect(svg).toHaveAttribute('viewBox', '0 0 62 88');
        const index = svg.querySelector('.oc-f-rank')!;
        expect(Number(index.getAttribute('x'))).toBeLessThanOrEqual(6);
        expect(index.textContent).toBe(rank === 1 ? 'A' : rank === 11 ? 'J' : rank === 12 ? 'Q' : rank === 13 ? 'K' : String(rank));
        unmount();
      }
    }
  });

  it('공격 카드는 먹일 장수를 그림에 적는다 (+2·+3·+5·+7)', () => {
    const tag = (card: OneCardCard) => render(<OneCardFace card={card} />).container.querySelector('.oc-f-tag')?.textContent;
    expect(tag(c('SPADE', 2))).toBe('+2');
    expect(tag(c('SPADE', 1))).toBe('+3');
    expect(tag(joker('BLACK'))).toBe('+5');
    expect(tag(joker('COLOR'))).toBe('+7');
    expect(render(<OneCardFace card={c('SPADE', 9)} />).container.querySelector('.oc-f-tag')).toBeNull();
  });

  it('조커 둘은 같은 어릿광대 캐릭터다 — 색은 칩 클래스가 가른다', () => {
    for (const kind of ['BLACK', 'COLOR'] as const) {
      const { container } = render(<OneCardFace card={joker(kind)} />);
      expect(container.querySelector('.oc-j-face')).not.toBeNull();
      expect(container.querySelectorAll('.oc-j-ball').length).toBeGreaterThanOrEqual(3);
      expect(container.querySelector('.oc-f-joker-name')).toHaveTextContent('JOKER');
    }
  });
});

describe('연출 판단 — 표시 전용', () => {
  it('J·Q·K 효과 — 2명이면 J 는 한 번 더, Q 는 효과 없음 (rules §8, D-173)', () => {
    expect(specialEffectOf(c('SPADE', 13), 4)).toBe('again');
    expect(specialEffectOf(c('SPADE', 13), 2)).toBe('again');
    expect(specialEffectOf(c('SPADE', 11), 3)).toBe('skip');
    expect(specialEffectOf(c('SPADE', 11), 2)).toBe('again');
    expect(specialEffectOf(c('SPADE', 12), 3)).toBe('reverse');
    expect(specialEffectOf(c('SPADE', 12), 2)).toBeNull();
    expect(specialEffectOf(c('SPADE', 7), 4)).toBeNull();
    expect(specialEffectOf(joker('COLOR'), 4)).toBeNull();
  });

  it('공격 누적 단계는 누적을 따라 오르고 4 에서 멈춘다', () => {
    expect(attackLevel(0)).toBe(0);
    expect([2, 3, 5, 7, 10, 12, 13, 30].map(attackLevel)).toEqual([1, 1, 2, 2, 3, 3, 4, 4]);
  });

  it('연출 조각은 2초 안에 걷힌다 — 다음 차례를 가리지 않는다', () => {
    for (const ms of Object.values(FX_LIFETIME_MS)) expect(ms).toBeLessThanOrEqual(2000);
  });
});

describe('OneCardFxLayer — 장식 조각', () => {
  const items: OneCardFxItem[] = [
    { id: 1, kind: 'attack', total: 5, level: 2, left: 10, top: 20 },
    { id: 2, kind: 'suit', suit: 'CLUB', left: 10, top: 20 },
    { id: 3, kind: 'special', effect: 'skip', left: 10, top: 20 },
    { id: 4, kind: 'shock', left: 10, top: 20, dx: 30, dy: -40 },
    { id: 5, kind: 'pour', left: 50, top: 60 },
    { id: 6, kind: 'race', outcome: 'CAUGHT', leftCss: '40%', topCss: '30%' },
  ];

  it('없으면 아무것도 그리지 않는다', () => {
    expect(render(<OneCardFxLayer items={[]} raceSize="MEDIUM" />).container).toBeEmptyDOMElement();
  });

  it('조각마다 그리고, 전부 보조기기에 숨긴다', () => {
    const { container } = render(<OneCardFxLayer items={items} raceSize="SMALL" />);
    expect(container.querySelector('.oc-fx-attack[data-level="2"] .oc-fx-attack-num')).toHaveTextContent('+5');
    expect(container.querySelector('.oc-fx-suit .oc-glyph')).toHaveClass('oc-s-club');
    expect(container.querySelector('.oc-fx-special-skip .oc-fx-special-label')).toHaveTextContent('건너뛰기');
    expect(container.querySelector('.oc-fx-shock')).not.toBeNull();
    const hit = container.querySelector<HTMLElement>('.oc-fx-hit')!;
    expect(hit.style.left).toBe('40px');
    expect(hit.style.top).toBe('-20px');
    expect(container.querySelectorAll('.oc-fx-pour-card')).toHaveLength(7);
    for (const el of container.children) expect(el).toHaveAttribute('aria-hidden', 'true');
  });

  it('경쟁 폭죽은 버튼과 같은 크기 레이어에 뜬다 — 가장자리 보정 변수를 같이 쓴다', () => {
    const { container } = render(<OneCardFxLayer items={items} raceSize="SMALL" />);
    const layer = container.querySelector('.oc-fx-race-layer')!;
    expect(layer).toHaveClass('oc-race-layer', 'oc-race-size-small');
    expect(layer.querySelector('.oc-fx-race-catch .oc-fx-race-text')).toHaveTextContent('잡기!');
    expect(layer.querySelectorAll('.oc-fx-spark')).toHaveLength(12);
    expect(layer.querySelector('button')).toBeNull();
  });

  it('파티 — 조명 셋은 모두에게, 색종이 표식은 1위일 때만', () => {
    const lose = render(<OneCardParty win={false} />).container.querySelector('.oc-party')!;
    expect(lose).not.toHaveClass('oc-party-win');
    expect(lose.querySelectorAll('.oc-party-beam')).toHaveLength(3);
    expect(render(<OneCardParty win />).container.querySelector('.oc-party')).toHaveClass('oc-party-win');
  });
});
