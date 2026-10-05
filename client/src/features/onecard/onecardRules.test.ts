import { describe, expect, it } from 'vitest';
import {
  attackStrength,
  attackValue,
  baseSuitOf,
  canPlay,
  drawCount,
  isAttack,
  isSuitChange,
  sortForDisplay,
} from './onecardRules';
import type { OneCardCard, OneCardSuit } from '@/types/onecard';

/**
 * 서버 `PlayRules`·`PlayingCard` 의 클라 미러. 표시용이지만 틀리면 낼 수 있는 카드를 흐리게 그려 사람을 속이므로
 * `docs/rules-onecard.md` §5·§6.2 의 표를 그대로 옮겨 고정한다.
 */

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const BLACK: OneCardCard = { suit: null, rank: 0, joker: 'BLACK' };
const COLOR: OneCardCard = { suit: null, rank: 0, joker: 'COLOR' };

describe('공격 값과 세기 (§1·§6.2)', () => {
  it('2·A·흑백 조커·컬러 조커만 공격이고 값은 2·3·5·7 이다', () => {
    expect([c('HEART', 2), c('HEART', 1), BLACK, COLOR].map(attackValue)).toEqual([2, 3, 5, 7]);
    expect(isAttack(c('HEART', 7))).toBe(false);
    expect(isAttack(c('HEART', 13))).toBe(false);
  });

  it('세기는 2 < A < 흑백 조커 < 컬러 조커', () => {
    expect([c('CLUB', 2), c('CLUB', 1), BLACK, COLOR].map(attackStrength)).toEqual([1, 2, 3, 4]);
    expect(attackStrength(c('CLUB', 9))).toBe(0);
  });

  it('무늬 지정은 7 만', () => {
    expect(isSuitChange(c('SPADE', 7))).toBe(true);
    expect(isSuitChange(c('SPADE', 8))).toBe(false);
    expect(isSuitChange(BLACK)).toBe(false);
  });
});

describe('공격받는 중이 아닐 때 (§5.2)', () => {
  const top = c('HEART', 9);

  it('기준 무늬·같은 숫자·조커는 낼 수 있다', () => {
    expect(canPlay(c('HEART', 3), top, null, 0)).toBe(true);
    expect(canPlay(c('SPADE', 9), top, null, 0)).toBe(true);
    expect(canPlay(COLOR, top, null, 0)).toBe(true);
  });

  it('무늬도 숫자도 다르면 낼 수 없다 — 7 도 와일드가 아니다', () => {
    expect(canPlay(c('SPADE', 4), top, null, 0)).toBe(false);
    expect(canPlay(c('CLUB', 7), top, null, 0)).toBe(false);
  });

  it('7 로 지정한 무늬가 기준이 되고 숫자 일치는 그대로다', () => {
    const seven = c('HEART', 7);
    expect(baseSuitOf(seven, 'CLUB')).toBe('CLUB');
    expect(canPlay(c('CLUB', 4), seven, 'CLUB', 0)).toBe(true);
    expect(canPlay(c('HEART', 4), seven, 'CLUB', 0)).toBe(false);
    expect(canPlay(c('DIAMOND', 7), seven, 'CLUB', 0)).toBe(true);
  });

  it('맨 위가 조커면 아무 카드나 낼 수 있다', () => {
    expect(baseSuitOf(BLACK, null)).toBeNull();
    expect(canPlay(c('DIAMOND', 4), BLACK, null, 0)).toBe(true);
  });

  it('맨 위 카드가 아직 없으면 막지 않는다', () => {
    expect(canPlay(c('DIAMOND', 4), null, null, 0)).toBe(true);
  });
});

describe('공격받는 중일 때 (§5.3·§6.2 표)', () => {
  it('2 에는 모든 2·기준 무늬의 A·조커', () => {
    const top = c('HEART', 2);
    expect(canPlay(c('SPADE', 2), top, null, 2)).toBe(true);
    expect(canPlay(c('HEART', 1), top, null, 2)).toBe(true);
    expect(canPlay(c('SPADE', 1), top, null, 2)).toBe(false);
    expect(canPlay(BLACK, top, null, 2)).toBe(true);
    expect(canPlay(COLOR, top, null, 2)).toBe(true);
  });

  it('A 에는 모든 A·조커 — 2 는 약해서 안 된다', () => {
    const top = c('HEART', 1);
    expect(canPlay(c('CLUB', 1), top, null, 3)).toBe(true);
    expect(canPlay(c('HEART', 2), top, null, 3)).toBe(false);
    expect(canPlay(BLACK, top, null, 3)).toBe(true);
  });

  it('흑백 조커에는 컬러 조커만, 컬러 조커에는 아무것도', () => {
    expect(canPlay(COLOR, BLACK, null, 5)).toBe(true);
    expect(canPlay(c('HEART', 1), BLACK, null, 5)).toBe(false);
    expect(canPlay(BLACK, COLOR, null, 7)).toBe(false);
  });

  it('일반 카드와 7·J·Q·K 는 반격 수단이 아니다', () => {
    const top = c('HEART', 2);
    for (const rank of [3, 7, 11, 12, 13]) {
      expect(canPlay(c('HEART', rank), top, null, 2), String(rank)).toBe(false);
    }
  });
});

describe('먹기 장수 (§7)', () => {
  it('공격받는 중이면 누적, 아니면 1장', () => {
    expect(drawCount(0)).toBe(1);
    expect(drawCount(7)).toBe(7);
  });
});

describe('손패 표시 순서', () => {
  it('무늬(♠♥♦♣)별 숫자 오름차순, 조커는 맨 뒤 — 원본은 건드리지 않는다', () => {
    const hand = [COLOR, c('CLUB', 2), c('HEART', 13), BLACK, c('SPADE', 9), c('HEART', 1)];
    const sorted = sortForDisplay(hand);

    expect(sorted).toEqual([c('SPADE', 9), c('HEART', 1), c('HEART', 13), c('CLUB', 2), BLACK, COLOR]);
    expect(hand[0]).toBe(COLOR);
  });
});
